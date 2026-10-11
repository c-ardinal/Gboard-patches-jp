package dev.jason.gboardpatches.extension.extendeddictionary;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Gboard 18.0.3 PersonalDictionary.db accessed through Gboard's own qcj/qca/rph classes. */
final class GboardPersonalDictionaryReflectionGateway
        implements PersonalDictionaryGateway, AutoCloseable {
    private static final String TAG = "GboardExtendedDictionary";
    private static final String DATABASE_CLASS = "qcj";
    private static final String ENTRY_CLASS = "qca";
    private static final String LANGUAGE_TAG_CLASS = "rph";
    private static final long NEW_ENTRY_ID = -1L;

    private static final Object PREFLIGHT_LOCK = new Object();
    private static ClassLoader preflightLoader;
    private static Api preflightApi;
    private static boolean preflightDone;

    private final Api api;
    private final Object database;
    private final Map<String, String> normalizedTags = new HashMap<>();
    private Map<Long, String> rowsById;
    private Set<String> rowKeys;
    private boolean rowsStale;

    private GboardPersonalDictionaryReflectionGateway(Api api, Object database) {
        this.api = api;
        this.database = database;
    }

    static boolean isSupported(ClassLoader classLoader) {
        return preflight(classLoader) != null;
    }

    /** Returns null when this Gboard build does not expose the expected API. */
    static GboardPersonalDictionaryReflectionGateway open(Context context,
            ClassLoader classLoader) {
        return open(context, preflight(classLoader));
    }

    static GboardPersonalDictionaryReflectionGateway open(Context context, Api api) {
        if (api == null || context == null) {
            return null;
        }
        try {
            return new GboardPersonalDictionaryReflectionGateway(api,
                    api.databaseConstructor.newInstance(context));
        } catch (Throwable failure) {
            Log.w(TAG, "Personal dictionary database could not be opened", failure);
            return null;
        }
    }

    static Api preflight(ClassLoader classLoader) {
        synchronized (PREFLIGHT_LOCK) {
            if (!preflightDone || preflightLoader != classLoader) {
                preflightApi = resolve(classLoader,
                        DATABASE_CLASS, ENTRY_CLASS, LANGUAGE_TAG_CLASS);
                preflightLoader = classLoader;
                preflightDone = true;
            }
            return preflightApi;
        }
    }

    static Api resolve(ClassLoader classLoader, String databaseClass, String entryClass,
            String languageTagClass) {
        if (classLoader == null) {
            return null;
        }
        try {
            Class<?> database = Class.forName(databaseClass, false, classLoader);
            Class<?> entry = Class.forName(entryClass, false, classLoader);
            Class<?> languageTag = Class.forName(languageTagClass, false, classLoader);
            if (!AutoCloseable.class.isAssignableFrom(database)) {
                return null;
            }
            Method insert = database.getMethod("a", entry);
            Method rows = database.getMethod("c");
            Method delete = database.getMethod("e", long.class);
            Method fromTag = languageTag.getMethod("g", String.class);
            if (insert.getReturnType() != long.class
                    || !Cursor.class.isAssignableFrom(rows.getReturnType())
                    || delete.getReturnType() != void.class
                    || fromTag.getReturnType() != languageTag
                    || !Modifier.isStatic(fromTag.getModifiers())) {
                return null;
            }
            return new Api(database.getConstructor(Context.class),
                    entry.getConstructor(long.class, String.class, String.class,
                            languageTag, String.class),
                    insert, rows, delete, fromTag);
        } catch (Throwable failure) {
            Log.w(TAG, "Personal dictionary API is unavailable: " + failure.getClass().getName());
            return null;
        }
    }

    @Override
    public boolean contains(String word, String shortcut, String localeTag) {
        if (rowKeys == null) {
            loadRows();
        }
        return rowKeys.contains(key(word, shortcut, normalizedTag(localeTag)));
    }

    @Override
    public long insert(String word, String shortcut, String localeTag, String posTag) {
        Object tag = invoke(api.fromTag, null, localeTag);
        Object entry;
        try {
            entry = api.entryConstructor.newInstance(NEW_ENTRY_ID, word, shortcut, tag, posTag);
        } catch (Throwable failure) {
            throw new IllegalStateException("Personal dictionary entry could not be created",
                    failure);
        }
        long id = (Long) invoke(api.insert, database, entry);
        if (id > 0L) {
            if (rowKeys != null) {
                rowKeys.add(key(word, shortcut, String.valueOf(tag)));
            }
            rowsStale = true;
        }
        return id;
    }

    @Override
    public void delete(long id) {
        invoke(api.delete, database, id);
        if (rowsById != null) {
            rowsById.remove(id);
        }
    }

    @Override
    public boolean exists(long id, String word, String shortcut, String localeTag) {
        if (rowsById == null || rowsStale) {
            loadRows();
        }
        return key(word, shortcut, normalizedTag(localeTag)).equals(rowsById.get(id));
    }

    @Override
    public void close() {
        try {
            ((AutoCloseable) database).close();
        } catch (Throwable failure) {
            Log.w(TAG, "Personal dictionary database could not be closed", failure);
        }
    }

    // ponytail: rows are snapshotted once per gateway; one gateway serves one import/remove pass.
    private void loadRows() {
        Map<Long, String> byId = new HashMap<>();
        Set<String> keys = new HashSet<>();
        try (Cursor cursor = (Cursor) invoke(api.rows, database)) {
            int idColumn = cursor.getColumnIndexOrThrow("_id");
            int wordColumn = cursor.getColumnIndexOrThrow("word");
            int shortcutColumn = cursor.getColumnIndexOrThrow("shortcut");
            int localeColumn = cursor.getColumnIndexOrThrow("locale");
            while (cursor.moveToNext()) {
                String key = key(cursor.getString(wordColumn), cursor.getString(shortcutColumn),
                        cursor.getString(localeColumn));
                byId.put(cursor.getLong(idColumn), key);
                keys.add(key);
            }
        }
        rowsById = byId;
        rowKeys = keys;
        rowsStale = false;
    }

    private String normalizedTag(String localeTag) {
        String raw = localeTag == null ? "" : localeTag;
        String cached = normalizedTags.get(raw);
        if (cached == null) {
            cached = String.valueOf(invoke(api.fromTag, null, raw));
            normalizedTags.put(raw, cached);
        }
        return cached;
    }

    private static String key(String word, String shortcut, String localeTag) {
        return nonNull(word) + '\u0000' + nonNull(shortcut) + '\u0000' + nonNull(localeTag);
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
        final Constructor<?> entryConstructor;
        final Method insert;
        final Method rows;
        final Method delete;
        final Method fromTag;

        Api(Constructor<?> databaseConstructor, Constructor<?> entryConstructor, Method insert,
                Method rows, Method delete, Method fromTag) {
            this.databaseConstructor = databaseConstructor;
            this.entryConstructor = entryConstructor;
            this.insert = insert;
            this.rows = rows;
            this.delete = delete;
            this.fromTag = fromTag;
        }
    }
}
