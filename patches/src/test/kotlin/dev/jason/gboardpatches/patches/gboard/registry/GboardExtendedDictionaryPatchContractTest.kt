package dev.jason.gboardpatches.patches.gboard.registry

import com.google.gson.JsonParser
import dev.jason.gboardpatches.patches.gboard.features.extendeddictionary.EXTENDED_DICTIONARY_FEATURE_MARKER
import dev.jason.gboardpatches.patches.gboard.features.extendeddictionary.EXTENDED_DICTIONARY_RUNTIME_FIELDS
import dev.jason.gboardpatches.patches.gboard.features.extendeddictionary.EXTENDED_DICTIONARY_RUNTIME_TARGETS
import dev.jason.gboardpatches.patches.gboard.features.extendeddictionary.gboardExtendedDictionaryFeatureMarkerPatch
import dev.jason.gboardpatches.patches.gboard.features.extendeddictionary.gboardExtendedDictionaryGuardPatch
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesSettingsPatch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardExtendedDictionaryPatchContractTest {
    private val repositoryRoot = repositoryRoot()

    @Test
    fun `public patch is version sensitive guarded and enabled by default`() {
        val patch = gboardExtendedDictionaryPatch

        assertEquals("Extended Dictionary", patch.name)
        assertTrue(patch.default)
        assertEquals(
            setOf(
                gboardPatchesSettingsPatch,
                gboardExtendedDictionaryFeatureMarkerPatch,
                gboardExtendedDictionaryGuardPatch,
            ),
            patch.dependencies,
        )

        val catalog = JsonParser.parseString(
            Files.readString(repositoryRoot.resolve(CATALOG_PATH), StandardCharsets.UTF_8),
        ).asJsonObject
        val feature = catalog.getAsJsonArray("features").map { it.asJsonObject }.single {
            it["feature_id"].asString == "extended_dictionary"
        }
        assertEquals("version-sensitive", feature["migration_scope"].asString)
        assertEquals(EXTENDED_DICTIONARY_FEATURE_MARKER, feature["feature_marker"].asString)
        val contribution = feature.getAsJsonArray("contributions").single().asJsonObject
        assertEquals("dedicated_bytecode", contribution["anchor_family_id"].asString)
        assertTrue(contribution.getAsJsonArray("required_bindings").isEmpty)
        assertTrue(contribution.getAsJsonArray("runtime_calls").isEmpty)
    }

    @Test
    fun `guard resolves exactly the personal dictionary api used by the runtime gateway`() {
        assertEquals(
            listOf(
                "Lqcj;-><init>(Landroid/content/Context;)V",
                "Lqcg;->f()V",
                "Lrph;->g(Ljava/lang/String;)Lrph;",
            ),
            EXTENDED_DICTIONARY_RUNTIME_TARGETS.map { target -> target.reference },
        )
        assertEquals(
            listOf("Lqcg;->a:Ljava/lang/String;", "Lqcg;->b:Lqcd;"),
            EXTENDED_DICTIONARY_RUNTIME_FIELDS.map { target -> target.reference },
        )
        val gateway = Files.readString(repositoryRoot.resolve(GATEWAY_PATH), StandardCharsets.UTF_8)
        listOf("\"qcj\"", "\"qcg\"", "\"rph\"").forEach { name ->
            assertTrue("Gateway must resolve $name", gateway.contains(name))
        }
    }

    @Test
    fun `settings row is registered before backup and has both locales`() {
        val registry = Files.readString(
            repositoryRoot.resolve(SETTINGS_REGISTRY_PATH),
            StandardCharsets.UTF_8,
        )
        val homepage = registry.indexOf("new GboardSettingsHomepageSettingsFeature()")
        val dictionary = registry.indexOf("new GboardExtendedDictionarySettingsFeature(context)")
        val backup = registry.indexOf("new GboardBackupRestoreSettingsFeature(context)")
        assertTrue(homepage >= 0 && homepage < dictionary && dictionary < backup)

        val text = Files.readString(
            repositoryRoot.resolve(SETTINGS_TEXT_PATH),
            StandardCharsets.UTF_8,
        )
        assertTrue(text.contains("<translation locale=\"en\">Extended Dictionary</translation>"))
        assertTrue(text.contains("<translation locale=\"zh-Hant\">擴充字典</translation>"))
    }

    private fun repositoryRoot(): Path {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        return generateSequence(workingDirectory) { it.parent }
            .firstOrNull { candidate ->
                Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
            } ?: error("Could not locate repository root from $workingDirectory")
    }

    private companion object {
        const val CATALOG_PATH =
            "patches/src/main/resources/gboard/gboard-port-product-catalog.json"
        const val GATEWAY_PATH =
            "extensions/extension/src/main/java/dev/jason/gboardpatches/extension/" +
                "extendeddictionary/GboardPersonalDictionaryReflectionGateway.java"
        const val SETTINGS_REGISTRY_PATH =
            "extensions/extension/src/main/java/dev/jason/gboardpatches/extension/settings/" +
                "GboardPatchesSettingsFeatureRegistry.java"
        const val SETTINGS_TEXT_PATH =
            "extensions/extension/src/main/settings-text/gboard_settings_text.xml"
    }
}
