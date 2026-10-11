package dev.jason.gboardpatches.extension.extendeddictionary;

import java.util.ArrayList;
import java.util.List;

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

        ImportResult(int inserted, int skippedExisting, int failed, boolean rolledBack,
                int rollbackLeftovers) {
            this.inserted = inserted;
            this.skippedExisting = skippedExisting;
            this.failed = failed;
            this.rolledBack = rolledBack;
            this.rollbackLeftovers = rollbackLeftovers;
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
        List<ManagedEntryStore.Entry> existing = store.load();
        List<ManagedEntryStore.Entry> inserted = new ArrayList<>();
        int skipped = 0;
        int failed = 0;
        try {
            for (WordListParser.Row row : rows) {
                if (gateway.contains(row.word, row.shortcut, row.localeTag)) {
                    skipped++;
                    continue;
                }
                String posTag = posTagFor(row);
                long id = gateway.insert(row.word, row.shortcut, row.localeTag, posTag);
                if (id > 0L) {
                    inserted.add(new ManagedEntryStore.Entry(
                            id, row.word, row.shortcut, row.localeTag, posTag));
                } else {
                    failed++;
                }
            }
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
            return new ImportResult(0, skipped, failed, true, rollBack(gateway, inserted));
        }
    }

    static RemoveResult removeAll(PersonalDictionaryGateway gateway, ManagedEntryStore store) {
        List<ManagedEntryStore.Entry> remaining = new ArrayList<>();
        int deleted = 0;
        int forgotten = 0;
        for (ManagedEntryStore.Entry entry : store.load()) {
            try {
                if (matches(gateway, entry)) {
                    gateway.delete(entry.id);
                    deleted++;
                } else {
                    forgotten++;
                }
            } catch (RuntimeException failure) {
                remaining.add(entry);
            }
        }
        store.save(remaining);
        return new RemoveResult(deleted, forgotten, remaining.size());
    }

    private static int rollBack(PersonalDictionaryGateway gateway,
            List<ManagedEntryStore.Entry> inserted) {
        int leftovers = 0;
        for (ManagedEntryStore.Entry entry : inserted) {
            try {
                if (matches(gateway, entry)) {
                    gateway.delete(entry.id);
                }
            } catch (RuntimeException failure) {
                leftovers++;
            }
        }
        return leftovers;
    }

    private static boolean matches(PersonalDictionaryGateway gateway,
            ManagedEntryStore.Entry entry) {
        return gateway.exists(entry.id, entry.word, entry.shortcut, entry.localeTag);
    }
}
