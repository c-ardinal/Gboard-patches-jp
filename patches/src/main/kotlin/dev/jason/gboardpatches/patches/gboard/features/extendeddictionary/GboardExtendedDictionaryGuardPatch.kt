package dev.jason.gboardpatches.patches.gboard.features.extendeddictionary

import app.morphe.patcher.patch.bytecodePatch
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.findMutableMethodOrThrow
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

private const val DATABASE = "Lqcj;"
private const val ENTRY = "Lqca;"
private const val LANGUAGE_TAG = "Lrph;"

internal val EXTENDED_DICTIONARY_RUNTIME_TARGETS = listOf(
    GboardMethodTarget(DATABASE, "<init>", listOf("Landroid/content/Context;"), "V"),
    GboardMethodTarget(DATABASE, "a", listOf(ENTRY), "J"),
    GboardMethodTarget(DATABASE, "c", emptyList(), "Lqce;"),
    GboardMethodTarget(DATABASE, "e", listOf("J"), "V"),
    GboardMethodTarget(
        ENTRY,
        "<init>",
        listOf("J", "Ljava/lang/String;", "Ljava/lang/String;", LANGUAGE_TAG, "Ljava/lang/String;"),
        "V",
    ),
    GboardMethodTarget(LANGUAGE_TAG, "g", listOf("Ljava/lang/String;"), LANGUAGE_TAG),
)

internal val gboardExtendedDictionaryGuardPatch = bytecodePatch(
    description = "確認 Extended Dictionary runtime 依賴的 Gboard 個人字典 API 存在。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    execute {
        EXTENDED_DICTIONARY_RUNTIME_TARGETS.forEach { target -> findMutableMethodOrThrow(target) }
    }
}
