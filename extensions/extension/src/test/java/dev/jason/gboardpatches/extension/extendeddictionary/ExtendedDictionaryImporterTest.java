package dev.jason.gboardpatches.extension.extendeddictionary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public final class ExtendedDictionaryImporterTest {
    private static final String NOUN = ExtendedDictionaryImporter.JAPANESE_DEFAULT_POS_TAG;

    private ManagedEntryStore store;
    private FakeGateway gateway;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ManagedEntryStore.PREF_FILE, Context.MODE_PRIVATE)
                .edit().clear().commit();
        store = ManagedEntryStore.open(context);
        gateway = new FakeGateway();
    }

    @Test
    public void skipsExistingUserRowsAndTracksOnlyNewRows() {
        long userId = gateway.addUserRow("Alpha", "a", "en-US", "");

        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, Arrays.asList(row("a", "Alpha", "en-US", ""),
                        row("b", "Beta", "en-US", ""), row("b", "Beta", "en-US", "")));

        assertFalse(result.rolledBack);
        assertEquals(1, result.inserted);
        assertEquals(2, result.skippedExisting);
        List<ManagedEntryStore.Entry> managed = store.load();
        assertEquals(1, managed.size());
        assertEquals("Beta", managed.get(0).word);
        assertFalse(managed.stream().anyMatch(entry -> entry.id == userId));
        assertTrue(gateway.rows.containsKey(userId));
    }

    @Test
    public void japaneseRowsDefaultToNounAndOtherLocalesNeverPassPosTags() {
        ExtendedDictionaryImporter.importRows(gateway, store, Arrays.asList(
                row("こう", "構築", "ja-JP", ""),
                row("いぬ", "犬", "ja-JP", "固有名詞"),
                row("omw", "On my way!", "en-US", "名詞"),
                row("", "Hallo", "de", "noun")));

        assertEquals(Arrays.asList(NOUN, "固有名詞", "", ""),
                gateway.insertedPosTags);
    }

    @Test
    public void japaneseDefaultPosToleratesTagCaseAndUnderscore() {
        assertEquals(NOUN, ExtendedDictionaryImporter.posTagFor(row("a", "A", "ja-jp", "")));
        assertEquals(NOUN, ExtendedDictionaryImporter.posTagFor(row("a", "A", "ja_JP", "")));
        assertEquals("", ExtendedDictionaryImporter.posTagFor(row("a", "A", "ja", "")));
    }

    @Test
    public void failureMidImportRollsBackThisBatchAndKeepsStoreUnchanged() {
        ManagedEntryStore.Entry previous =
                new ManagedEntryStore.Entry(gateway.addUserRow("Old", "o", "en-US", ""),
                        "Old", "o", "en-US", "");
        store.save(Collections.singletonList(previous));
        long userId = gateway.addUserRow("Mine", "m", "en-US", "");
        gateway.failOnInsertNumber = 3;

        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, Arrays.asList(row("a", "A", "en-US", ""),
                        row("b", "B", "en-US", ""), row("c", "C", "en-US", "")));

        assertTrue(result.rolledBack);
        assertEquals(0, result.inserted);
        assertEquals(0, result.rollbackLeftovers);
        assertEquals(2, gateway.deletedIds.size());
        assertEquals(Collections.singletonList(previous.id),
                store.load().stream().map(entry -> entry.id).toList());
        assertTrue(gateway.rows.containsKey(userId));
        assertTrue(gateway.rows.containsKey(previous.id));
        assertEquals(2, gateway.rows.size());
    }

    @Test
    public void unverifiedOrFailedInsertsAreNeitherTrackedNorDeleted() {
        long userId = gateway.addUserRow("Other", "x", "en-US", "");
        gateway.forcedInsertResults.add(-1L);
        gateway.forcedInsertResults.add(userId);

        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, Arrays.asList(row("a", "A", "en-US", ""),
                        row("b", "B", "en-US", "")));

        assertEquals(0, result.inserted);
        assertEquals(2, result.failed);
        assertTrue(store.load().isEmpty());
        assertTrue(gateway.deletedIds.isEmpty());
        assertTrue(gateway.rows.containsKey(userId));
    }

    @Test
    public void removalDeletesOnlyMatchingRowsAndForgetsChangedOnes() {
        ExtendedDictionaryImporter.importRows(gateway, store, Arrays.asList(
                row("a", "A", "en-US", ""), row("b", "B", "en-US", "")));
        List<ManagedEntryStore.Entry> managed = store.load();
        long editedId = managed.get(1).id;
        gateway.rows.put(editedId, new String[] {"B edited", "b", "en-US", ""});
        long userId = gateway.addUserRow("Mine", "m", "en-US", "");

        ExtendedDictionaryImporter.RemoveResult result =
                ExtendedDictionaryImporter.removeAll(gateway, store);

        assertEquals(1, result.deleted);
        assertEquals(1, result.forgotten);
        assertEquals(0, result.failed);
        assertEquals(Collections.singletonList(managed.get(0).id), gateway.deletedIds);
        assertTrue(gateway.rows.containsKey(editedId));
        assertTrue(gateway.rows.containsKey(userId));
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void removalKeepsEntriesWhoseDeleteFailedForALaterRetry() {
        ExtendedDictionaryImporter.importRows(gateway, store,
                Collections.singletonList(row("a", "A", "en-US", "")));
        gateway.failDeletes = true;

        ExtendedDictionaryImporter.RemoveResult result =
                ExtendedDictionaryImporter.removeAll(gateway, store);

        assertEquals(0, result.deleted);
        assertEquals(1, result.failed);
        assertEquals(1, store.load().size());
    }

    private static WordListParser.Row row(String shortcut, String word, String localeTag,
            String posTag) {
        return new WordListParser.Row(shortcut, word, localeTag, posTag);
    }

    static final class FakeGateway implements PersonalDictionaryGateway {
        final Map<Long, String[]> rows = new LinkedHashMap<>();
        final List<String> insertedPosTags = new ArrayList<>();
        final List<Long> deletedIds = new ArrayList<>();
        final List<Long> forcedInsertResults = new ArrayList<>();
        int failOnInsertNumber = -1;
        boolean failDeletes;
        private long nextId = 100L;
        private int insertCount;

        long addUserRow(String word, String shortcut, String localeTag, String posTag) {
            long id = nextId++;
            rows.put(id, new String[] {word, shortcut, localeTag, posTag});
            return id;
        }

        @Override
        public boolean contains(String word, String shortcut, String localeTag) {
            return rows.values().stream().anyMatch(row -> row[0].equals(word)
                    && row[1].equals(shortcut) && row[2].equals(localeTag));
        }

        @Override
        public long insert(String word, String shortcut, String localeTag, String posTag) {
            insertCount++;
            if (insertCount == failOnInsertNumber) {
                throw new IllegalStateException("simulated database failure");
            }
            insertedPosTags.add(posTag);
            if (!forcedInsertResults.isEmpty()) {
                return forcedInsertResults.remove(0);
            }
            return addUserRow(word, shortcut, localeTag, posTag);
        }

        @Override
        public void delete(long id) {
            if (failDeletes) {
                throw new IllegalStateException("simulated delete failure");
            }
            deletedIds.add(id);
            rows.remove(id);
        }

        @Override
        public boolean exists(long id, String word, String shortcut, String localeTag) {
            String[] row = rows.get(id);
            return row != null && row[0].equals(word) && row[1].equals(shortcut)
                    && row[2].equals(localeTag);
        }
    }
}
