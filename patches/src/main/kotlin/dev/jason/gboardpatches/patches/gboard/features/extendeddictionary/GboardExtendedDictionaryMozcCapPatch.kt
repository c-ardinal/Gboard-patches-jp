package dev.jason.gboardpatches.patches.gboard.features.extendeddictionary

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.findMutableMethodOrThrow
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

private const val MOZC_SHORTCUTS_UTIL = "Liam;"

internal const val MOZC_SHORTCUT_STOCK_CAP = 200_000
internal const val MOZC_SHORTCUT_PATCHED_CAP = 1_000_000

/** MozcShortcutsUtil: a() logs "Too many records" and b() truncates the import list. */
internal val EXTENDED_DICTIONARY_MOZC_CAP_TARGETS = listOf(
    GboardMethodTarget(
        MOZC_SHORTCUTS_UTIL,
        "a",
        listOf("Ljava/util/List;", "Ljava/lang/String;"),
        "Ljava/lang/String;",
    ),
    GboardMethodTarget(MOZC_SHORTCUTS_UTIL, "b", listOf("Ljava/util/List;"), "V"),
)

internal val gboardExtendedDictionaryMozcCapPatch = bytecodePatch(
    description = "將 Gboard 匯入 Mozc 的個人字典上限由 200000 筆提高為 1000000 筆。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    execute {
        EXTENDED_DICTIONARY_MOZC_CAP_TARGETS.forEach { target ->
            findMutableMethodOrThrow(target).raiseMozcShortcutCap()
        }
    }
}

/**
 * Stock 18.0.3 shape in both methods: `const vN, 200000` immediately followed by
 * `if-le vSize, vN`. Anything else (count, opcode or guard) fails the patch.
 * In iam.b the same register is also the start index of subList(start, size).clear(), so the
 * truncation point moves to the patched cap together with the guard.
 */
internal fun MutableMethod.raiseMozcShortcutCap() {
    val instructions = implementation?.instructions?.toList()
        ?: error("$definingClass->$name has no implementation")
    val indices = instructions.indices.filter { index ->
        (instructions[index] as? NarrowLiteralInstruction)?.narrowLiteral == MOZC_SHORTCUT_STOCK_CAP
    }
    check(indices.size == 1) {
        "Expected exactly one $MOZC_SHORTCUT_STOCK_CAP literal in $definingClass->$name, " +
            "found ${indices.size}"
    }
    val index = indices.single()
    val constant = instructions[index]
    check(constant.opcode == Opcode.CONST) {
        "Unexpected ${constant.opcode} for the Mozc cap in $definingClass->$name"
    }
    val register = (constant as OneRegisterInstruction).registerA
    val guard = instructions.getOrNull(index + 1)
    check(guard?.opcode == Opcode.IF_LE && (guard as TwoRegisterInstruction).registerB == register) {
        "Mozc cap in $definingClass->$name is not followed by its if-le guard"
    }
    replaceInstruction(index, "const v$register, $MOZC_SHORTCUT_PATCHED_CAP")
}
