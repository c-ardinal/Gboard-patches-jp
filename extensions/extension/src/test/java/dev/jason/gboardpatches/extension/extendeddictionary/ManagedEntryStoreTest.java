package dev.jason.gboardpatches.extension.extendeddictionary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.jason.gboardpatches.extension.settings.GboardPatchesSettings;

@RunWith(RobolectricTestRunner.class)
public final class ManagedEntryStoreTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ManagedEntryStore.PREF_FILE, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void startsEmptyAndRoundTripsEntries() {
        ManagedEntryStore store = ManagedEntryStore.open(context);
        assertTrue(store.load().isEmpty());

        store.save(Arrays.asList(
                new ManagedEntryStore.Entry(7L, "On my way!", "omw", "en-US", ""),
                new ManagedEntryStore.Entry(9L, "構築", "こう", "ja-JP",
                        "名詞")));

        List<ManagedEntryStore.Entry> loaded = ManagedEntryStore.open(context).load();
        assertEquals(2, loaded.size());
        assertEquals(7L, loaded.get(0).id);
        assertEquals("On my way!", loaded.get(0).word);
        assertEquals("omw", loaded.get(0).shortcut);
        assertEquals("en-US", loaded.get(0).localeTag);
        assertEquals("", loaded.get(0).posTag);
        assertEquals("名詞", loaded.get(1).posTag);

        store.save(Collections.emptyList());
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void corruptStateFailsInsteadOfBeingSilentlyReplaced() {
        context.getSharedPreferences(ManagedEntryStore.PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(ManagedEntryStore.PREF_KEY_ENTRIES, "{not json").commit();

        assertThrows(IllegalStateException.class,
                () -> ManagedEntryStore.open(context).load());
    }

    @Test
    public void usesItsOwnPreferenceFileOutsideTheBackedUpSettings() {
        assertNotEquals(GboardPatchesSettings.PREF_FILE, ManagedEntryStore.PREF_FILE);
    }
}
