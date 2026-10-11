package dev.jason.gboardpatches.extension.extendeddictionary;

/** Access to Gboard personal dictionary rows; failures surface as runtime exceptions. */
interface PersonalDictionaryGateway {
    boolean contains(String word, String shortcut, String localeTag);

    long insert(String word, String shortcut, String localeTag, String posTag);

    void delete(long id);

    boolean exists(long id, String word, String shortcut, String localeTag);
}
