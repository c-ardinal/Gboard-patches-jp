package dev.jason.gboardpatches.extension.extendeddictionary;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Personal dictionary rows created by this feature, in an extension-owned database that the
 * Patches Backup &amp; Restore export never reads (it only reads its registered preference files).
 */
final class ManagedEntryStore implements AutoCloseable {
    static final String DATABASE_NAME = "gboard_patches_extended_dictionary.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "managed_entry";
    /** Legacy (pre-database) storage: one JSON array in this preference file. */
    static final String PREF_FILE = "gboard_patches_extended_dictionary";
    static final String PREF_KEY_ENTRIES = "managed_entries";

    private final SQLiteOpenHelper helper;
    private final SharedPreferences legacyPreferences;
    private boolean legacyMigrated;

    ManagedEntryStore(SQLiteOpenHelper helper, SharedPreferences legacyPreferences) {
        this.helper = helper;
        this.legacyPreferences = legacyPreferences;
    }

    static ManagedEntryStore open(Context context) {
        Context appContext = context.getApplicationContext();
        Context storageContext = appContext != null ? appContext : context;
        return new ManagedEntryStore(new Helper(storageContext),
                storageContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE));
    }

    static final class Entry {
        final long id;
        final String word;
        final String shortcut;
        final String localeTag;
        final String posTag;

        Entry(long id, String word, String shortcut, String localeTag, String posTag) {
            this.id = id;
            this.word = word;
            this.shortcut = shortcut;
            this.localeTag = localeTag;
            this.posTag = posTag;
        }
    }

    List<Entry> load() {
        migrateLegacy();
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(TABLE,
                new String[] {"id", "word", "shortcut", "locale", "pos_tag"},
                null, null, null, null, "id")) {
            while (cursor.moveToNext()) {
                entries.add(new Entry(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4)));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    int count() {
        migrateLegacy();
        return (int) DatabaseUtils.queryNumEntries(helper.getReadableDatabase(), TABLE);
    }

    /** Replaces the whole list atomically. */
    void save(List<Entry> entries) {
        migrateLegacy();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE, null, null);
            insert(db, entries);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void close() {
        helper.close();
    }

    /** Moves the legacy JSON list into the database once; corrupt JSON fails closed. */
    private void migrateLegacy() {
        if (legacyMigrated) {
            return;
        }
        String raw = legacyPreferences.getString(PREF_KEY_ENTRIES, null);
        if (raw != null) {
            List<Entry> legacy = parseLegacy(raw);
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                insert(db, legacy);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (!legacyPreferences.edit().remove(PREF_KEY_ENTRIES).commit()) {
                throw new IllegalStateException("Legacy dictionary entries could not be cleared");
            }
        }
        legacyMigrated = true;
    }

    private static List<Entry> parseLegacy(String raw) {
        try {
            JSONArray array = new JSONArray(raw);
            List<Entry> entries = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                entries.add(new Entry(item.getLong("id"), item.getString("word"),
                        item.getString("shortcut"), item.getString("locale"),
                        item.getString("pos")));
            }
            return entries;
        } catch (JSONException failure) {
            throw new IllegalStateException("Managed dictionary entries are unreadable", failure);
        }
    }

    /** INSERT OR REPLACE: a duplicate id keeps the last entry and can never abort a save. */
    private static void insert(SQLiteDatabase db, List<Entry> entries) {
        try (SQLiteStatement statement = db.compileStatement("INSERT OR REPLACE"
                + " INTO " + TABLE + "(id, word, shortcut, locale, pos_tag) VALUES (?, ?, ?, ?, ?)")) {
            for (Entry entry : entries) {
                statement.clearBindings();
                statement.bindLong(1, entry.id);
                statement.bindString(2, entry.word);
                statement.bindString(3, entry.shortcut);
                statement.bindString(4, entry.localeTag);
                statement.bindString(5, entry.posTag);
                statement.executeInsert();
            }
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + "(id INTEGER PRIMARY KEY, word TEXT NOT NULL,"
                    + " shortcut TEXT NOT NULL, locale TEXT NOT NULL, pos_tag TEXT NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
