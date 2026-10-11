package dev.jason.gboardpatches.extension.extendeddictionary;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Personal dictionary rows created by this feature; kept out of Patches backups on purpose. */
final class ManagedEntryStore {
    static final String PREF_FILE = "gboard_patches_extended_dictionary";
    static final String PREF_KEY_ENTRIES = "managed_entries";

    private final SharedPreferences preferences;

    ManagedEntryStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    static ManagedEntryStore open(Context context) {
        return new ManagedEntryStore(context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE));
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
        String raw = preferences.getString(PREF_KEY_ENTRIES, null);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(raw);
            List<Entry> entries = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                entries.add(new Entry(item.getLong("id"), item.getString("word"),
                        item.getString("shortcut"), item.getString("locale"),
                        item.getString("pos")));
            }
            return Collections.unmodifiableList(entries);
        } catch (JSONException failure) {
            throw new IllegalStateException("Managed dictionary entries are unreadable", failure);
        }
    }

    void save(List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(new JSONObject()
                        .put("id", entry.id)
                        .put("word", entry.word)
                        .put("shortcut", entry.shortcut)
                        .put("locale", entry.localeTag)
                        .put("pos", entry.posTag));
            }
        } catch (JSONException failure) {
            throw new IllegalStateException("Managed dictionary entries cannot be encoded", failure);
        }
        if (!preferences.edit().putString(PREF_KEY_ENTRIES, array.toString()).commit()) {
            throw new IllegalStateException("Managed dictionary entries could not be saved");
        }
    }
}
