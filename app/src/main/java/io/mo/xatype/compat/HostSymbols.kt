package io.mo.xatype.compat

/** Members of the input method (com.xiaomi.type), located by [HostFingerprints]. */
object HostSymbols : SymbolTable(4, HostFingerprints::resolve) {
    const val AI_SAFETY_PARSERS = "aiSafety.parsers"
    const val VOICE_MODERATION = "voice.moderation"
    const val ASR_ERROR_MAPPER = "asr.errorMapper"
    const val ASR_ERROR_CALLBACK = "asr.errorCallback"
    const val OS_VERSION_GATE = "os.versionGate"
    const val HEIGHT_RECT = "height.rect"
    const val HEIGHT_CLAMP = "height.clamp"
    const val HEIGHT_RECT_GETTER = "height.rectGetter"
    const val HEIGHT_LAYOUT = "height.layout"
    const val HEIGHT_DRAG = "height.drag"
    const val HEIGHT_DRAG_KIND = "height.dragKind"
    const val MODERN_KEYBOARD = "keyboard.modern"
    const val BOTTOM_SPACING = "keyboard.bottomSpacing"
    const val BOTTOM_SPACING_CONSUME = "keyboard.bottomSpacingConsume"
    const val BOTTOM_SPACING_CALLERS = "keyboard.bottomSpacingCallers"

    override val keys = listOf(
        AI_SAFETY_PARSERS, VOICE_MODERATION, ASR_ERROR_MAPPER, ASR_ERROR_CALLBACK,
        OS_VERSION_GATE, HEIGHT_RECT, HEIGHT_CLAMP, HEIGHT_RECT_GETTER, HEIGHT_LAYOUT,
        HEIGHT_DRAG, HEIGHT_DRAG_KIND, MODERN_KEYBOARD,
        BOTTOM_SPACING, BOTTOM_SPACING_CONSUME, BOTTOM_SPACING_CALLERS
    )

    fun modernKeyboard(): ModernKeyboardProfile? = strings(MODERN_KEYBOARD)?.let {
        runCatching { ModernKeyboardProfile.fromProperties(it) }.getOrNull()
    }
}
