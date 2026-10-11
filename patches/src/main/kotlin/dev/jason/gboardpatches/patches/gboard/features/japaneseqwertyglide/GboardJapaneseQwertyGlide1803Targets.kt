package dev.jason.gboardpatches.patches.gboard.features.japaneseqwertyglide

import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget

internal object GboardJapaneseQwertyGlide1803Targets {
    private const val METADATA = "Lcom/google/android/libraries/inputmethod/metadata"
    private const val GESTURE_HANDLER =
        "Lcom/google/android/apps/inputmethod/libs/latin5/handler/LatinGestureMotionEventHandler;"

    const val HANDLER_INFO_ARRAY = "[$METADATA/KeyboardViewDef\$MotionEventHandlerInfo;"
    const val VIEW_DEF_HANDLER_INFOS_FIELD = "$METADATA/KeyboardViewDef;->i:$HANDLER_INFO_ARRAY"
    const val MANAGER_HANDLER_INFOS_FIELD = "Lozj;->h:$HANDLER_INFO_ARRAY"
    const val HANDLER_CONTEXT_MANAGER_FIELD = "Lozi;->b:Lozj;"
    const val MANAGER_DELEGATE_FIELD = "Lozj;->b:Loxv;"
    const val MANAGER_SEND = "Loxv;->M(Lnur;)V"
    const val KEYBOARD = "Lcom/google/android/libraries/inputmethod/keyboard/impl/Keyboard;"
    const val DELIGHT5_FIELD =
        "$GESTURE_HANDLER->m:Lcom/google/android/apps/inputmethod/libs/delight5/Delight5Facilitator;"
    const val FIND_VIEW_BY_ID = "Landroid/view/View;->findViewById(I)Landroid/view/View;"

    val managerInit = GboardMethodTarget(
        classType = "Lozj;",
        name = "<init>",
        parameterTypes = listOf(
            "Landroid/content/Context;",
            "Loxv;",
            "$METADATA/KeyboardDef;",
            "$METADATA/KeyboardViewDef;",
            "Loxu;",
        ),
        returnType = "V",
    )
    val gestureReady = GboardMethodTarget(
        classType = GESTURE_HANDLER,
        name = "r",
        parameterTypes = emptyList(),
        returnType = "Z",
    )
    val gestureInputArea = GboardMethodTarget(
        classType = GESTURE_HANDLER,
        name = "c",
        parameterTypes = listOf("Landroid/view/View;"),
        returnType = "Landroid/view/ViewGroup;",
    )
    val handlerContextSend = GboardMethodTarget(
        classType = "Lozi;",
        name = "n",
        parameterTypes = listOf("Lnur;"),
        returnType = "V",
    )
    val keyboardActivate = GboardMethodTarget(
        classType = KEYBOARD,
        name = "e",
        parameterTypes = listOf("Landroid/view/inputmethod/EditorInfo;", "Ljava/lang/Object;"),
        returnType = "V",
    )
    val japaneseImeEvent = GboardMethodTarget(
        classType = "Lcom/google/android/apps/inputmethod/libs/mozc/ime/SimpleJapaneseIme;",
        name = "q",
        parameterTypes = listOf("Lnur;"),
        returnType = "Z",
    )
    val mozcCommit = GboardMethodTarget(
        classType = "Lhzu;",
        name = "b",
        parameterTypes = listOf("Llxn;", "Z", "Landroid/view/inputmethod/EditorInfo;"),
        returnType = "Ljava/lang/String;",
    )
    val keyboardDeactivate = GboardMethodTarget(
        classType = KEYBOARD,
        name = "h",
        parameterTypes = emptyList(),
        returnType = "V",
    )
}
