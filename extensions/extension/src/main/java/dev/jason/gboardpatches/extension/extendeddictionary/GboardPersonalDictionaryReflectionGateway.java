package dev.jason.gboardpatches.extension.extendeddictionary;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gboard 18.0.3 PersonalDictionary.db written in bulk through Gboard's own SQLiteOpenHelper
 * (qcg.b of a qcj instance), followed by Gboard's own change notification (qcg.f()).
 */
final class GboardPersonalDictionaryReflectionGateway
        implements PersonalDictionaryGateway, AutoCloseable {
    private static final String TAG = "GboardExtendedDictionary";
    private static final String DATABASE_CLASS = "qcj";
    private static final String DATABASE_BASE_CLASS = "qcg";
    private static final String LANGUAGE_TAG_CLASS = "rph";
    static final String ENTRY_TABLE = "entry";
    private static final List<String> ENTRY_COLUMNS =
            Arrays.asList("_id", "word", "shortcut", "locale");
    private static final List<String> POS_COLUMNS = Arrays.asList("_id", "pos_tag");

    private static final Object PREFLIGHT_LOCK = new Object();
    private static ClassLoader preflightLoader;
    private static Api preflightApi;
    private static boolean preflightDone;

    interface TagNormalizer {
        String normalize(String localeTag);
    }

    private final SQLiteOpenHelper helper;
    private final String posTable;
    private final Runnable changeNotifier;
    private final TagNormalizer tagNormalizer;
    private final Map<String, String> normalizedTags = new HashMap<>();
    private Map<Long, String> rowsById;
    private Set<String> rowKeys;
    private boolean rowsStale;

    GboardPersonalDictionaryReflectionGateway(SQLiteOpenHelper helper, String posTable,
            Runnable changeNotifier, TagNormalizer tagNormalizer) {
        this.helper = helper;
        this.posTable = posTable;
        this.changeNotifier = changeNotifier;
        this.tagNormalizer = tagNormalizer;
    }

    static boolean isSupported(ClassLoader classLoader) {
        return preflight(classLoader) != null;
    }

    /** Returns null when this Gboard build does not expose the expected API or schema. */
    static GboardPersonalDictionaryReflectionGateway open(Context context,
            ClassLoader classLoader) {
        return open(context, preflight(classLoader));
    }

    static GboardPersonalDictionaryReflectionGateway open(Context context, Api api) {
        if (api == null || context == null) {
            return null;
        }
        GboardPersonalDictionaryReflectionGateway gateway = null;
        try {
            Object database = api.databaseConstructor.newInstance(context);
            gateway = new GboardPersonalDictionaryReflectionGateway(
                    (SQLiteOpenHelper) api.helperField.get(database),
                    (String) api.posTableField.get(null),
                    () -> invoke(api.notifyChanged, null),
                    tag -> String.valueOf(invoke(api.fromTag, null, tag)));
            if (gateway.hasExpectedSchema()) {
                return gateway;
            }
            Log.w(TAG, "Personal dictionary schema is not the expected one");
        } catch (Throwable failure) {
            Log.w(TAG, "Personal dictionary database could not be opened: "
                    + failure.getClass().getName());
        }
        if (gateway != null) {
            gateway.close();
        }
        return null;
    }

    static Api preflight(ClassLoader classLoader) {
        synchronized (PREFLIGHT_LOCK) {
            if (!preflightDone || preflightLoader != classLoader) {
                preflightApi = resolve(classLoader,
                        DATABASE_CLASS, DATABASE_BASE_CLASS, LANGUAGE_TAG_CLASS);
                preflightLoader = classLoader;
                preflightDone = true;
            }
            return preflightApi;
        }
    }

    static Api resolve(ClassLoader classLoader, String databaseClass, String baseClass,
            String languageTagClass) {
        if (classLoader == null) {
            return null;
        }
        try {
            Class<?> database = Class.forName(databaseClass, false, classLoader);
            Class<?> base = Class.forName(baseClass, false, classLoader);
            Class<?> languageTag = Class.forName(languageTagClass, false, classLoader);
            Field helper = base.getDeclaredField("b");
            Field posTable = base.getDeclaredField("a");
            Method notifyChanged = base.getDeclaredMethod("f");
            Method fromTag = languageTag.getMethod("g", String.class);
            if (!base.isAssignableFrom(database)
                    || !SQLiteOpenHelper.class.isAssignableFrom(helper.getType())
                    || Modifier.isStatic(helper.getModifiers())
                    || posTable.getType() != String.class
                    || !Modifier.isStatic(posTable.getModifiers())
                    || notifyChanged.getReturnType() != void.class
                    || !Modifier.isStatic(notifyChanged.getModifiers())
                    || fromTag.getReturnType() != languageTag
                    || !Modifier.isStatic(fromTag.getModifiers())) {
                return null;
            }
            helper.setAccessible(true);
            posTable.setAccessible(true);
            notifyChanged.setAccessible(true);
            return new Api(database.getConstructor(Context.class), helper, posTable,
                    notifyChanged, fromTag);
        } catch (Throwable failure) {
            Log.w(TAG, "Personal dictionary API is unavailable: " + failure.getClass().getName());
            return null;
        }
    }

    boolean hasExpectedSchema() {
        SQLiteDatabase db = helper.getWritableDatabase();
        return columns(db, ENTRY_TABLE).containsAll(ENTRY_COLUMNS)
                && posTable != null && !posTable.isEmpty()
                && columns(db, posTable).containsAll(POS_COLUMNS);
    }

    private static Set<String> columns(SQLiteDatabase db, String table) {
        Set<String> columns = new HashSet<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + quoted(table) + ")", null)) {
            int name = cursor.getColumnIndex("name");
            while (name >= 0 && cursor.moveToNext()) {
                columns.add(cursor.getString(name));
            }
        }
        return columns;
    }

    @Override
    public boolean contains(String word, String shortcut, String localeTag) {
        if (rowKeys == null) {
            loadRows();
        }
        return rowKeys.contains(key(word, shortcut, normalizedTag(localeTag)));
    }

    @Override
    public boolean exists(long id, String word, String shortcut, String localeTag,
            String posTag) {
        if (rowsById == null || rowsStale) {
            loadRows();
        }
        String tag = normalizedTag(localeTag);
        String expectedPos = ExtendedDictionaryImporter.JAPANESE_LOCALE_TAG.equals(tag)
                ? posTag : "";
        return (key(word, shortcut, tag) + '\u0000' + nonNull(expectedPos))
                .equals(rowsById.get(id));
    }

    @Override
    public long[] insertAll(List<ManagedEntryStore.Entry> entries,
            ExtendedDictionaryImporter.Progress progress, AtomicBoolean cancel) {
        long[] ids = new long[entries.size()];
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int index = 0; index < entries.size(); index++) {
                if (cancel.get()) {
                    throw new CancellationException();
                }
                ManagedEntryStore.Entry entry = entries.get(index);
                String localeTag = normalizedTag(entry.localeTag);
                ContentValues row = new ContentValues();
                row.put("word", entry.word);
                row.put("shortcut", entry.shortcut);
                row.put("locale", localeTag);
                ids[index] = db.insertOrThrow(ENTRY_TABLE, null, row);
                if (ExtendedDictionaryImporter.JAPANESE_LOCALE_TAG.equals(localeTag)
                        && !entry.posTag.isEmpty()) {
                    ContentValues pos = new ContentValues();
                    pos.put("_id", ids[index]);
                    pos.put("pos_tag", entry.posTag);
                    // A stale POS row left behind under a reused rowid is overwritten, as
                    // Gboard's own writer (qcj.k) updates instead of inserting in that case.
                    if (db.insertWithOnConflict(posTable, null, pos,
                            SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                        throw new IllegalStateException("POS row could not be written");
                    }
                }
                progress.onProgress(index + 1, entries.size());
            }
            if (cancel.get()) {
                throw new CancellationException();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        rowsStale = true;
        notifyChanged();
        return ids;
    }

    @Override
    public void deleteAll(List<ManagedEntryStore.Entry> entries,
            ExtendedDictionaryImporter.Progress progress) {
        if (entries.isEmpty()) {
            return;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int index = 0; index < entries.size(); index++) {
                String[] id = {String.valueOf(entries.get(index).id)};
                db.delete(ENTRY_TABLE, "_id = ?", id);
                db.delete(posTable, "_id = ?", id);
                progress.onProgress(index + 1, entries.size());
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        rowsStale = true;
        notifyChanged();
    }

    /** Runs after commit; a failed notification must not look like a failed write. */
    private void notifyChanged() {
        try {
            changeNotifier.run();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Personal dictionary change notification failed: "
                    + failure.getClass().getName());
        }
    }

    @Override
    public void close() {
        try {
            helper.close();
        } catch (Throwable failure) {
            Log.w(TAG, "Personal dictionary database could not be closed", failure);
        }
    }

    // ponytail: rows are snapshotted per gateway; one gateway serves one import/remove pass.
    private void loadRows() {
        Map<Long, String> byId = new HashMap<>();
        Set<String> keys = new HashSet<>();
        String pos = quoted(posTable);
        // Same LEFT JOIN as Gboard's own qcj.c() reader.
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT entry._id, word, shortcut, locale, " + pos + ".pos_tag FROM entry"
                        + " LEFT JOIN " + pos + " ON entry._id = " + pos + "._id", null)) {
            while (cursor.moveToNext()) {
                String key = key(cursor.getString(1), cursor.getString(2), cursor.getString(3));
                byId.put(cursor.getLong(0), key + '\u0000' + nonNull(cursor.getString(4)));
                keys.add(key);
            }
        }
        rowsById = byId;
        rowKeys = keys;
        rowsStale = false;
    }

    @Override
    public String normalizedTag(String localeTag) {
        String raw = localeTag == null ? "" : localeTag;
        String cached = normalizedTags.get(raw);
        if (cached == null) {
            cached = tagNormalizer.normalize(raw);
            normalizedTags.put(raw, cached);
        }
        return cached;
    }

    private static String key(String word, String shortcut, String localeTag) {
        return nonNull(word) + '\u0000' + nonNull(shortcut) + '\u0000' + nonNull(localeTag);
    }

    /** SQL identifier quoting for the table name read from Gboard's qcg.a constant. */
    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Personal dictionary call failed", cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Personal dictionary call is not accessible", failure);
        }
    }

    static final class Api {
        final Constructor<?> databaseConstructor;
        final Field helperField;
        final Field posTableField;
        final Method notifyChanged;
        final Method fromTag;

        Api(Constructor<?> databaseConstructor, Field helperField, Field posTableField,
                Method notifyChanged, Method fromTag) {
            this.databaseConstructor = databaseConstructor;
            this.helperField = helperField;
            this.posTableField = posTableField;
            this.notifyChanged = notifyChanged;
            this.fromTag = fromTag;
        }
    }
}
