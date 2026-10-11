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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public void skipsExistingUserRowsAndDuplicatesAndTracksOnlyNewRows() {
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
        assertEquals(1, gateway.insertBatches);
    }

    @Test
    public void inFileDuplicatesCollapseOnTheNormalizedLanguageTag() {
        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, Arrays.asList(row("a", "A", "ja-jp", ""),
                        row("a", "A", "ja-JP", "")));

        assertEquals(1, result.inserted);
        assertEquals(1, result.skippedExisting);
        assertEquals(1, gateway.rows.size());
    }

    @Test
    public void removalReportsProgressUpToTheMatchingCount() {
        ExtendedDictionaryImporter.importRows(gateway, store, Arrays.asList(
                row("a", "A", "en-US", ""), row("b", "B", "en-US", "")));
        List<int[]> calls = new ArrayList<>();

        ExtendedDictionaryImporter.removeAll(gateway, store,
                (done, total) -> calls.add(new int[] {done, total}));

        assertEquals(0, calls.get(0)[0]);
        assertEquals(2, calls.get(calls.size() - 1)[0]);
        assertTrue(calls.stream().allMatch(call -> call[1] == 2));
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
    public void progressIsThrottledMonotonicAndEndsAtTotal() {
        gateway.addUserRow("word7", "s", "en-US", "");
        List<WordListParser.Row> rows = new ArrayList<>();
        for (int index = 0; index < 1000; index++) {
            rows.add(row("s", "word" + index, "en-US", ""));
        }
        List<int[]> calls = new ArrayList<>();

        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, rows, (done, total) -> calls.add(new int[] {done, total}),
                new AtomicBoolean());

        assertEquals(999, result.inserted);
        assertEquals(1, result.skippedExisting);
        assertEquals(999, store.load().size());
        assertTrue(calls.size() >= 2);
        assertTrue(calls.size() <= 2 + 1000 / ExtendedDictionaryImporter.ProgressThrottle
                .PROGRESS_ROW_STEP + 2);
        int previous = -1;
        for (int[] call : calls) {
            assertEquals(999, call[1]);
            assertTrue(call[0] >= previous);
            previous = call[0];
        }
        assertEquals(0, calls.get(0)[0]);
        assertEquals(999, calls.get(calls.size() - 1)[0]);
    }

    @Test
    public void batchFailureLeavesDictionaryAndStoreUnchanged() {
        ManagedEntryStore.Entry previous =
                new ManagedEntryStore.Entry(gateway.addUserRow("Old", "o", "en-US", ""),
                        "Old", "o", "en-US", "");
        store.save(Collections.singletonList(previous));
        long userId = gateway.addUserRow("Mine", "m", "en-US", "");
        gateway.failure = new IllegalStateException("simulated database failure");

        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, Arrays.asList(row("a", "A", "en-US", ""),
                        row("b", "B", "en-US", "")));

        assertTrue(result.rolledBack);
        assertFalse(result.cancelled);
        assertEquals(0, result.inserted);
        assertEquals(0, result.rollbackLeftovers);
        assertTrue(gateway.deletedIds.isEmpty());
        assertEquals(Collections.singletonList(previous.id),
                store.load().stream().map(entry -> entry.id).toList());
        assertTrue(gateway.rows.containsKey(userId));
        assertEquals(2, gateway.rows.size());
    }

    @Test
    public void cancellationLeavesDictionaryAndStoreUnchanged() {
        gateway.failure = new CancellationException();

        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, Collections.singletonList(row("a", "A", "en-US", "")));

        assertTrue(result.cancelled);
        assertFalse(result.rolledBack);
        assertEquals(0, result.inserted);
        assertTrue(store.load().isEmpty());
        assertTrue(gateway.rows.isEmpty());
    }

    @Test
    public void unverifiedInsertsAreNeitherTrackedNorDeleted() {
        long userId = gateway.addUserRow("Other", "x", "en-US", "");
        gateway.forcedIds = new long[] {userId};

        ExtendedDictionaryImporter.ImportResult result = ExtendedDictionaryImporter.importRows(
                gateway, store, Collections.singletonList(row("a", "A", "en-US", "")));

        assertEquals(0, result.inserted);
        assertEquals(1, result.failed);
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

    /** Transactional fake: a failing batch writes nothing. */
    static final class FakeGateway implements PersonalDictionaryGateway {
        final Map<Long, String[]> rows = new LinkedHashMap<>();
        final List<String> insertedPosTags = new ArrayList<>();
        final List<Long> deletedIds = new ArrayList<>();
        RuntimeException failure;
        long[] forcedIds;
        boolean failDeletes;
        int insertBatches;
        private long nextId = 100L;

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
        public boolean exists(long id, String word, String shortcut, String localeTag) {
            String[] row = rows.get(id);
            return row != null && row[0].equals(word) && row[1].equals(shortcut)
                    && row[2].equals(localeTag);
        }

        @Override
        public long[] insertAll(List<ManagedEntryStore.Entry> entries,
                ExtendedDictionaryImporter.Progress progress, AtomicBoolean cancel) {
            insertBatches++;
            if (failure != null) {
                throw failure;
            }
            if (forcedIds != null) {
                return forcedIds;
            }
            long[] ids = new long[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                ManagedEntryStore.Entry entry = entries.get(index);
                insertedPosTags.add(entry.posTag);
                ids[index] = addUserRow(entry.word, entry.shortcut, entry.localeTag,
                        entry.posTag);
                progress.onProgress(index + 1, entries.size());
            }
            return ids;
        }

        @Override
        public void deleteAll(List<ManagedEntryStore.Entry> entries,
                ExtendedDictionaryImporter.Progress progress) {
            if (failDeletes) {
                throw new IllegalStateException("simulated delete failure");
            }
            for (int index = 0; index < entries.size(); index++) {
                deletedIds.add(entries.get(index).id);
                rows.remove(entries.get(index).id);
                progress.onProgress(index + 1, entries.size());
            }
        }

        @Override
        public String normalizedTag(String localeTag) {
            return "ja-jp".equalsIgnoreCase(localeTag) ? "ja-JP" : localeTag;
        }
    }
}
