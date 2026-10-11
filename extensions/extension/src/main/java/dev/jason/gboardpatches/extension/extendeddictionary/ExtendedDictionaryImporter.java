package dev.jason.gboardpatches.extension.extendeddictionary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds rows without adopting existing ones, and removes only rows that still match. */
final class ExtendedDictionaryImporter {
    static final String JAPANESE_LOCALE_TAG = "ja-JP";
    /** Gboard POS label for a noun; Mozc ignores ja-JP rows without a POS label. */
    static final String JAPANESE_DEFAULT_POS_TAG = new String(new int[] {0x540D, 0x8A5E}, 0, 2);

    static final class ImportResult {
        final int inserted;
        final int skippedExisting;
        final int failed;
        final boolean rolledBack;
        final int rollbackLeftovers;
        final boolean cancelled;

        ImportResult(int inserted, int skippedExisting, int failed, boolean rolledBack,
                int rollbackLeftovers) {
            this(inserted, skippedExisting, failed, rolledBack, rollbackLeftovers, false);
        }

        ImportResult(int inserted, int skippedExisting, int failed, boolean rolledBack,
                int rollbackLeftovers, boolean cancelled) {
            this.inserted = inserted;
            this.skippedExisting = skippedExisting;
            this.failed = failed;
            this.rolledBack = rolledBack;
            this.rollbackLeftovers = rollbackLeftovers;
            this.cancelled = cancelled;
        }
    }

    static final class RemoveResult {
        final int deleted;
        final int forgotten;
        final int failed;

        RemoveResult(int deleted, int forgotten, int failed) {
            this.deleted = deleted;
            this.forgotten = forgotten;
            this.failed = failed;
        }
    }

    interface Progress {
        void onProgress(int done, int total);
    }

    /** Passes on every PROGRESS_ROW_STEP rows, after PROGRESS_INTERVAL_NANOS, or at the end. */
    static final class ProgressThrottle implements Progress {
        static final int PROGRESS_ROW_STEP = 250;
        static final long PROGRESS_INTERVAL_NANOS = 500_000_000L;

        private final Progress progress;
        private int lastDone;
        private long lastNanos = System.nanoTime();

        ProgressThrottle(Progress progress) {
            this.progress = progress;
        }

        @Override
        public void onProgress(int done, int total) {
            long now = System.nanoTime();
            if (done == total || done - lastDone >= PROGRESS_ROW_STEP
                    || (done > lastDone && now - lastNanos >= PROGRESS_INTERVAL_NANOS)) {
                lastDone = done;
                lastNanos = now;
                progress.onProgress(done, total);
            }
        }
    }

    private ExtendedDictionaryImporter() {
    }

    static String posTagFor(WordListParser.Row row) {
        if (!JAPANESE_LOCALE_TAG.equalsIgnoreCase(row.localeTag.replace('_', '-'))) {
            return "";
        }
        return row.posTag.isEmpty() ? JAPANESE_DEFAULT_POS_TAG : row.posTag;
    }

    static ImportResult importRows(PersonalDictionaryGateway gateway, ManagedEntryStore store,
            List<WordListParser.Row> rows) {
        return importRows(gateway, store, rows, (done, total) -> {
        }, new AtomicBoolean());
    }

    static ImportResult importRows(PersonalDictionaryGateway gateway, ManagedEntryStore store,
            List<WordListParser.Row> rows, Progress progress, AtomicBoolean cancel) {
        List<ManagedEntryStore.Entry> existing = store.load();
        List<ManagedEntryStore.Entry> pending = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int skipped = 0;
        for (WordListParser.Row row : rows) {
            String key = row.word + '\u0000' + row.shortcut + '\u0000'
                    + gateway.normalizedTag(row.localeTag);
            if (!seen.add(key) || gateway.contains(row.word, row.shortcut, row.localeTag)) {
                skipped++;
                continue;
            }
            pending.add(new ManagedEntryStore.Entry(
                    -1L, row.word, row.shortcut, row.localeTag, posTagFor(row)));
        }
        progress.onProgress(0, pending.size());
        if (pending.isEmpty()) {
            return new ImportResult(0, skipped, 0, false, 0);
        }
        long[] ids;
        try {
            ids = gateway.insertAll(pending, new ProgressThrottle(progress), cancel);
        } catch (CancellationException cancelled) {
            return new ImportResult(0, skipped, 0, false, 0, true);
        } catch (RuntimeException failure) {
            return new ImportResult(0, skipped, 0, true, 0);
        }
        List<ManagedEntryStore.Entry> inserted = new ArrayList<>(pending.size());
        for (int index = 0; index < pending.size(); index++) {
            ManagedEntryStore.Entry entry = pending.get(index);
            inserted.add(new ManagedEntryStore.Entry(ids[index], entry.word, entry.shortcut,
                    entry.localeTag, entry.posTag));
        }
        try {
            int failed = 0;
            List<ManagedEntryStore.Entry> verified = new ArrayList<>(existing);
            for (ManagedEntryStore.Entry entry : inserted) {
                if (matches(gateway, entry)) {
                    verified.add(entry);
                } else {
                    failed++;
                }
            }
            store.save(verified);
            return new ImportResult(verified.size() - existing.size(), skipped, failed,
                    false, 0);
        } catch (RuntimeException failure) {
            return new ImportResult(0, skipped, 0, true, rollBack(gateway, inserted));
        }
    }

    static RemoveResult removeAll(PersonalDictionaryGateway gateway, ManagedEntryStore store) {
        return removeAll(gateway, store, (done, total) -> {
        });
    }

    static RemoveResult removeAll(PersonalDictionaryGateway gateway, ManagedEntryStore store,
            Progress progress) {
        List<ManagedEntryStore.Entry> remaining = new ArrayList<>();
        List<ManagedEntryStore.Entry> matching = new ArrayList<>();
        int forgotten = 0;
        for (ManagedEntryStore.Entry entry : store.load()) {
            try {
                if (matches(gateway, entry)) {
                    matching.add(entry);
                } else {
                    forgotten++;
                }
            } catch (RuntimeException failure) {
                remaining.add(entry);
            }
        }
        int deleted = 0;
        progress.onProgress(0, matching.size());
        try {
            if (!matching.isEmpty()) {
                gateway.deleteAll(matching, new ProgressThrottle(progress));
            }
            deleted = matching.size();
        } catch (RuntimeException failure) {
            remaining.addAll(matching);
        }
        store.save(remaining);
        return new RemoveResult(deleted, forgotten, remaining.size());
    }

    private static int rollBack(PersonalDictionaryGateway gateway,
            List<ManagedEntryStore.Entry> inserted) {
        List<ManagedEntryStore.Entry> matching = new ArrayList<>();
        try {
            for (ManagedEntryStore.Entry entry : inserted) {
                if (matches(gateway, entry)) {
                    matching.add(entry);
                }
            }
            gateway.deleteAll(matching, (done, total) -> {
            });
            return 0;
        } catch (RuntimeException failure) {
            return inserted.size();
        }
    }

    private static boolean matches(PersonalDictionaryGateway gateway,
            ManagedEntryStore.Entry entry) {
        return gateway.exists(entry.id, entry.word, entry.shortcut, entry.localeTag);
    }
}
