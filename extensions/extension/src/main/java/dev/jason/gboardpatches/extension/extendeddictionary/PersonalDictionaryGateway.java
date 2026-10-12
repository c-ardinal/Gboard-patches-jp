package dev.jason.gboardpatches.extension.extendeddictionary;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Access to Gboard personal dictionary rows; failures surface as runtime exceptions. */
interface PersonalDictionaryGateway {
    boolean contains(String word, String shortcut, String localeTag);

    /** True when row id still holds exactly this entry, including its ja-JP POS tag. */
    boolean exists(long id, String word, String shortcut, String localeTag, String posTag);

    /**
     * Inserts every entry in one transaction and returns their row ids in order. Nothing is
     * written when it throws; cancellation surfaces as a CancellationException.
     */
    long[] insertAll(List<ManagedEntryStore.Entry> entries,
            ExtendedDictionaryImporter.Progress progress, AtomicBoolean cancel);

    /** Deletes the given rows (and their POS rows) in one transaction. */
    void deleteAll(List<ManagedEntryStore.Entry> entries,
            ExtendedDictionaryImporter.Progress progress);

    /**
     * How many entries match no enabled input language by language and region (such rows reach
     * no engine), or -1 when the enabled languages cannot be determined.
     */
    int countWithoutEnabledLanguage(List<ManagedEntryStore.Entry> entries);

    /** The language tag exactly as Gboard stores it in the locale column. */
    String normalizedTag(String localeTag);
}
