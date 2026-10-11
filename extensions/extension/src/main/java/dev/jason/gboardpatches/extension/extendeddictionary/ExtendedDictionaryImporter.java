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
        /** ja-JP rows whose reading Mozc would never ingest (see isMozcReading). */
        final int skippedReading;
        /** Stored entries dropped because the user deleted or edited them in Gboard. */
        final int forgotten;

        ImportResult(int inserted, int skippedExisting, int failed, boolean rolledBack,
                int rollbackLeftovers) {
            this(inserted, skippedExisting, failed, rolledBack, rollbackLeftovers, false);
        }

        ImportResult(int inserted, int skippedExisting, int failed, boolean rolledBack,
                int rollbackLeftovers, boolean cancelled) {
            this(inserted, skippedExisting, failed, rolledBack, rollbackLeftovers, cancelled,
                    0, 0);
        }

        private ImportResult(int inserted, int skippedExisting, int failed, boolean rolledBack,
                int rollbackLeftovers, boolean cancelled, int skippedReading, int forgotten) {
            this.inserted = inserted;
            this.skippedExisting = skippedExisting;
            this.failed = failed;
            this.rolledBack = rolledBack;
            this.rollbackLeftovers = rollbackLeftovers;
            this.cancelled = cancelled;
            this.skippedReading = skippedReading;
            this.forgotten = forgotten;
        }

        ImportResult withSkippedReading(int count) {
            return new ImportResult(inserted, skippedExisting, failed, rolledBack,
                    rollbackLeftovers, cancelled, count, forgotten);
        }

        ImportResult withForgotten(int count) {
            return new ImportResult(inserted, skippedExisting, failed, rolledBack,
                    rollbackLeftovers, cancelled, skippedReading, count);
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
        if (!isJapanese(row)) {
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
        List<WordListParser.Row> accepted = new ArrayList<>(rows.size());
        for (WordListParser.Row row : rows) {
            if (!isJapanese(row) || isMozcReading(row.shortcut)) {
                accepted.add(row);
            }
        }
        return importAccepted(gateway, store, accepted, progress, cancel)
                .withSkippedReading(rows.size() - accepted.size());
    }

    private static boolean isJapanese(WordListParser.Row row) {
        return JAPANESE_LOCALE_TAG.equalsIgnoreCase(row.localeTag.replace('_', '-'));
    }

    /**
     * Mirrors Gboard 18.0.3 iam.c(), the filter its Mozc shortcuts handler (iak.d) applies to
     * each reading: non-empty, at most 256 chars, and only these code points.
     */
    static boolean isMozcReading(String reading) {
        if (reading == null || reading.isEmpty() || reading.length() > 256) {
            return false;
        }
        for (int index = 0; index < reading.length(); ) {
            int c = reading.codePointAt(index);
            boolean allowed = (c >= 0x21 && c <= 0x7E) // printable ASCII
                    || (c >= 0x3041 && c <= 0x3096) // hiragana
                    || (c >= 0x309B && c <= 0x309C) // (han)dakuten
                    || (c >= 0x30A1 && c <= 0x30F6) // katakana
                    || (c >= 0x30FB && c <= 0x30FC) // middle dot, prolonged sound mark
                    || (c >= 0x3001 && c <= 0x3002) // ideographic comma and full stop
                    || (c >= 0x300C && c <= 0x300F) // corner brackets
                    || c == 0x301C // wave dash
                    || (c >= 0xFF01 && c <= 0xFF5E) // full-width ASCII
                    || (c >= 0xFF61 && c <= 0xFF9F); // half-width katakana and punctuation
            if (!allowed) {
                return false;
            }
            index += Character.charCount(c);
        }
        return true;
    }

    private static ImportResult importAccepted(PersonalDictionaryGateway gateway,
            ManagedEntryStore store, List<WordListParser.Row> rows, Progress progress,
            AtomicBoolean cancel) {
        // Rows the user deleted or edited in Gboard are no longer ours; their rowids may be
        // reused by this very import, so they must leave the store first.
        List<ManagedEntryStore.Entry> stored = store.load();
        List<ManagedEntryStore.Entry> existing = new ArrayList<>(stored.size());
        for (ManagedEntryStore.Entry entry : stored) {
            if (matches(gateway, entry)) {
                existing.add(entry);
            }
        }
        int forgotten = stored.size() - existing.size();
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
            if (forgotten > 0) {
                store.save(existing);
            }
            return new ImportResult(0, skipped, 0, false, 0).withForgotten(forgotten);
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
                    false, 0).withForgotten(forgotten);
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
        return gateway.exists(
                entry.id, entry.word, entry.shortcut, entry.localeTag, entry.posTag);
    }
}
