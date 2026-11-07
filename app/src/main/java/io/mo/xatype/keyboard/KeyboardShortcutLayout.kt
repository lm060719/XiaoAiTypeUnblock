package io.mo.xatype.keyboard

/** Keep the engine and visible layout in agreement, including temporary number panels. */
enum class KeyboardShortcutLayout {
    QWERTY, T9, KEYBOARD18, KEYBOARD14;

    companion object {
        fun fromHost(mode: String?, layoutId: String?, keyboard18: Boolean, keyboard14: Boolean = false): KeyboardShortcutLayout? {
            if (layoutId == "qwerty" && (mode == "PINYIN" || mode == "ENGLISH")) {
                return when {
                    mode != "PINYIN" -> QWERTY
                    keyboard18 -> KEYBOARD18
                    keyboard14 -> KEYBOARD14
                    else -> QWERTY
                }
            }
            return if (!keyboard18 && !keyboard14 && mode == "T9" && layoutId == "t9") T9 else null
        }
    }
}
