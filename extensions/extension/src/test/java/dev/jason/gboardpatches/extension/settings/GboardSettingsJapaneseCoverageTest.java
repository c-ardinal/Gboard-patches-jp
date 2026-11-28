package dev.jason.gboardpatches.extension.settings;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gboard-patches-jp: every English settings entry must also have a Japanese translation. */
public final class GboardSettingsJapaneseCoverageTest {
    private static final String JAPANESE = "values-ja";
    private static final Pattern ENTRY_NAME =
            Pattern.compile("<(?:string|plurals)\\s+name=\"([^\"]+)\"");

    @Test
    public void everyEnglishEntryHasJapaneseTranslation() throws Exception {
        Set<String> japanese = names(GboardSettingsTextFiles.read(JAPANESE));
        List<String> missing = new ArrayList<String>();
        for (String name : names(GboardSettingsTextFiles.english())) {
            if (!japanese.contains(name)) {
                missing.add(name);
            }
        }
        Assert.assertTrue("Missing Japanese settings text: " + missing, missing.isEmpty());
    }

    private static Set<String> names(String xml) {
        Set<String> names = new LinkedHashSet<String>();
        Matcher matcher = ENTRY_NAME.matcher(xml);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
