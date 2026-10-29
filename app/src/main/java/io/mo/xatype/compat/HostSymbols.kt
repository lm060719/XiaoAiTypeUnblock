package io.mo.xatype.compat

/** Members of the input method (com.xiaomi.type), located by [HostFingerprints]. */
object HostSymbols : SymbolTable(8, HostFingerprints::resolve) {
    const val T9_SHUANGPIN_INIT = "keyboard.t9ShuangpinInit"
    const val T9_SHUANGPIN_BOOLEAN = "keyboard.t9ShuangpinBoolean"
    const val T9_SHUANGPIN_STRING = "keyboard.t9ShuangpinString"
    const val KEYBOARD_ENGINE = "keyboard.engine"
    /** Status only: emoji candidates bind through [KEYBOARD_ENGINE] and add no scan group. */
    const val KEYBOARD_EMOJI = "keyboard.emoji"
    /** Status only: post-commit prediction uses the same native engine ABI. */
    const val KEYBOARD_PREDICTION = "keyboard.prediction"
    const val KEYBOARD_LAYOUT = "keyboard.layout"
    const val KEYBOARD_ACTIONS = "keyboard.actions"
    const val KEYBOARD_PICKER = "keyboard.picker"
    const val KEYBOARD_SETTINGS = "keyboard.settings"
    const val KEYBOARD_SWIPE = "keyboard.swipe"
    const val KEYBOARD_LETTER_APPEARANCE = "keyboard.letterAppearance"
    const val KEYBOARD_SURFACE_TEXT = "keyboard.surfaceText"
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
        BOTTOM_SPACING, BOTTOM_SPACING_CONSUME, BOTTOM_SPACING_CALLERS,
        KEYBOARD_ENGINE, KEYBOARD_LAYOUT, KEYBOARD_ACTIONS, KEYBOARD_PICKER,
        KEYBOARD_SETTINGS, KEYBOARD_SWIPE, KEYBOARD_LETTER_APPEARANCE, KEYBOARD_SURFACE_TEXT,
        T9_SHUANGPIN_INIT, T9_SHUANGPIN_BOOLEAN, T9_SHUANGPIN_STRING
    )

    fun modernKeyboard(): ModernKeyboardProfile? = strings(MODERN_KEYBOARD)?.let {
        runCatching { ModernKeyboardProfile.fromProperties(it) }.getOrNull()
    }

    override fun validateValues(key: String, values: List<String>): Boolean = when {
        key in KeyboardAbi.groups -> KeyboardAbi.validate(key, values, checkNotNull(currentLoader()))
        key == MODERN_KEYBOARD -> runCatching {
            ModernKeyboardProfile.fromProperties(values).validate(checkNotNull(currentLoader()))
        }.getOrDefault(false)
        key == AI_SAFETY_PARSERS -> values.size >= 2 && super.validateValues(key, values)
        key == BOTTOM_SPACING_CALLERS -> super.validateValues(key, values)
        else -> values.size == 1 && super.validateValues(key, values)
    }
}
