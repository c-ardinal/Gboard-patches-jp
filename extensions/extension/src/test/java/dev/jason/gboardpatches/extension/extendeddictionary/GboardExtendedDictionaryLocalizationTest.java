package dev.jason.gboardpatches.extension.extendeddictionary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;

@RunWith(RobolectricTestRunner.class)
public final class GboardExtendedDictionaryLocalizationTest {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");

    @Test
    public void unsupportedGboardBuildShowsEnglishSummaryAndDisablesActions() {
        Context english = localized(Locale.ENGLISH);
        clearStore(english);
        GboardExtendedDictionarySettingsFeature feature =
                new GboardExtendedDictionarySettingsFeature(english,
                        new ClassLoader(getClass().getClassLoader()) {
                        });

        GboardPatchesSettingsContract.Screen screen = feature.buildScreen(new TestHost(english));

        assertEquals("Extended Dictionary", feature.getEntryTitle());
        assertEquals(GboardSettingsText.get(english,
                R.string.gboard_patches_extended_dictionary_unsupported_summary),
                screen.getHeaderSummary());
        List<GboardPatchesSettingsContract.Row> rows = screen.getSections().get(0).getItems();
        assertEquals(3, rows.size());
        assertEquals("Import word list", rows.get(0).getTitle().toString());
        assertFalse(rows.get(0).isEnabled());
        assertTrue(rows.get(1) instanceof GboardPatchesSettingsContract.DangerRow);
        assertFalse(rows.get(1).isEnabled());
        assertEquals("0 words managed by this feature", rows.get(2).getSummary());
        assertEnglish(screen);
    }

    @Test
    public void actionsReportFailuresAsMessagesInsteadOfThrowing() {
        Context english = localized(Locale.ENGLISH);
        GboardExtendedDictionarySettingsFeature feature =
                new GboardExtendedDictionarySettingsFeature(english,
                        new ClassLoader(getClass().getClassLoader()) {
                        });

        String unsupported = GboardSettingsText.get(english,
                R.string.gboard_patches_extended_dictionary_unsupported_summary);
        assertEquals(unsupported, feature.importDocument(english,
                "a\tA\ten-US\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(unsupported, feature.removeAll(english));
        assertEquals("Line 1 has the wrong number of columns.", feature.importDocument(english,
                "a\tA\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("The file contains no words.",
                feature.importDocument(english, new byte[0]));
    }

    @Test
    public void workerTasksTurnEveryThrowableIntoTheFailureMessage() {
        assertEquals("done", GboardExtendedDictionarySettingsFeature.runSafely(
                () -> "done", "failed"));
        assertEquals("failed", GboardExtendedDictionarySettingsFeature.runSafely(() -> {
            throw new IllegalArgumentException("malformed input");
        }, "failed"));
        assertEquals("failed", GboardExtendedDictionarySettingsFeature.runSafely(() -> {
            throw new OutOfMemoryError("simulated");
        }, "failed"));
    }

    @Test
    public void everyParseErrorHasEnglishAndTraditionalChineseText() {
        Context english = localized(Locale.ENGLISH);
        Context chinese = localized(Locale.forLanguageTag("zh-Hant"));
        for (WordListParser.Error error : WordListParser.Error.values()) {
            WordListParser.ParseException failure = new WordListParser.ParseException(error, 7);
            String englishText =
                    GboardExtendedDictionarySettingsFeature.parseErrorMessage(english, failure);
            assertFalse(englishText.isEmpty());
            assertNoHan(englishText);
            assertTrue(HAN.matcher(GboardExtendedDictionarySettingsFeature.parseErrorMessage(
                    chinese, failure)).find());
        }
        assertEquals("擴充字典",
                new GboardExtendedDictionarySettingsFeature(chinese).getEntryTitle());
    }

    @Test
    public void everyExtendedDictionaryResourceHasBothRequiredTranslations() throws Exception {
        Path sourceRoot = Path.of(
                "src/main/java/dev/jason/gboardpatches/extension/extendeddictionary");
        Pattern referencePattern = Pattern.compile("R\\.string\\.([A-Za-z0-9_]+)");
        Set<String> resourceNames = new LinkedHashSet<>();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertFalse("Hard-coded Chinese text in " + path, HAN.matcher(source).find());
                Matcher references = referencePattern.matcher(source);
                while (references.find()) {
                    resourceNames.add(references.group(1));
                }
            }
        }
        String authority = new String(Files.readAllBytes(
                Path.of("src/main/settings-text/gboard_settings_text.xml")),
                StandardCharsets.UTF_8);
        for (String resourceName : resourceNames) {
            Matcher block = Pattern.compile("<string name=\"" + Pattern.quote(resourceName)
                    + "\">(.*?)</string>", Pattern.DOTALL).matcher(authority);
            assertTrue("Missing settings-text entry: " + resourceName, block.find());
            Matcher englishText = Pattern.compile(
                    "<translation locale=\"en\">(.*?)</translation>", Pattern.DOTALL)
                    .matcher(block.group(1));
            assertTrue("Missing English translation: " + resourceName, englishText.find());
            assertFalse("Chinese text in English translation: " + resourceName,
                    HAN.matcher(englishText.group(1)).find());
            assertTrue("Missing Traditional Chinese translation: " + resourceName,
                    block.group(1).contains("<translation locale=\"zh-Hant\">"));
        }
        assertTrue("No extended dictionary resources were audited", resourceNames.size() > 20);
    }

    private static Context localized(Locale locale) {
        Application application = RuntimeEnvironment.getApplication();
        Configuration configuration =
                new Configuration(application.getResources().getConfiguration());
        configuration.setLocale(locale);
        return application.createConfigurationContext(configuration);
    }

    private static void clearStore(Context context) {
        context.getSharedPreferences(ManagedEntryStore.PREF_FILE, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private static void assertEnglish(GboardPatchesSettingsContract.Screen screen) {
        assertNoHan(screen.getToolbarTitle());
        assertNoHan(screen.getHeaderTitle());
        assertNoHan(screen.getHeaderSummary());
        for (GboardPatchesSettingsContract.Section section : screen.getSections()) {
            assertNoHan(section.getTitle());
            for (GboardPatchesSettingsContract.Row row : section.getItems()) {
                assertNoHan(row.getTitle());
                assertNoHan(row.getSummary());
            }
        }
    }

    private static void assertNoHan(CharSequence value) {
        if (value != null) {
            assertFalse("Unexpected Chinese UI text: " + value, HAN.matcher(value).find());
        }
    }

    private static final class TestHost implements GboardPatchesSettingsContract.FeatureHost {
        private final Context context;

        TestHost(Context context) {
            this.context = context;
        }

        @Override
        public Context getContext() {
            return context;
        }

        @Override
        public void submit(GboardPatchesSettingsContract.Intent intent) {
        }
    }
}
