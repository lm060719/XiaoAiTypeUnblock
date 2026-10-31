package io.mo.xatype.keyboard

/** Keep the engine and visible layout in agreement, including temporary number panels. */
enum class KeyboardShortcutLayout {
    QWERTY, T9, KEYBOARD18;

    companion object {
        fun fromHost(mode: String?, layoutId: String?, keyboard18: Boolean): KeyboardShortcutLayout? {
            if (layoutId == "qwerty" && (mode == "PINYIN" || mode == "ENGLISH")) {
                return if (keyboard18 && mode == "PINYIN") KEYBOARD18 else QWERTY
            }
            return if (!keyboard18 && mode == "T9" && layoutId == "t9") T9 else null
        }
    }
}
