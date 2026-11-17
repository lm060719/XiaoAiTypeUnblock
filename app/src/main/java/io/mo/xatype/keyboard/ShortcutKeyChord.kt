package io.mo.xatype.keyboard

import android.view.KeyEvent

/** A complete, momentary key chord. The serialized name also serves as its label. */
data class ShortcutKeyChord(val key: String, val ctrl: Boolean = false, val alt: Boolean = false,
                            val shift: Boolean = false) {
    val label: String get() = listOfNotNull(if (ctrl) "Ctrl" else null, if (alt) "Alt" else null,
        if (shift) "Shift" else null, key).joinToString("+")
    // A marker distinguishes a synthetic action from native letters and popup characters.
    val encoded: String get() = "按键:$label"
    val keyCode: Int get() = keys.getValue(key)
    val metaState: Int get() = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
        (if (alt) KeyEvent.META_ALT_ON else 0) or (if (shift) KeyEvent.META_SHIFT_ON else 0)

    /** Pure event plan, tested without constructing Android's framework KeyEvent in a JVM. */
    data class Stroke(val code: Int, val down: Boolean, val meta: Int)
    fun strokes(): List<Stroke> = buildList {
        val modifiers = listOfNotNull(
            if (ctrl) KeyEvent.KEYCODE_CTRL_LEFT to KeyEvent.META_CTRL_ON else null,
            if (alt) KeyEvent.KEYCODE_ALT_LEFT to KeyEvent.META_ALT_ON else null,
            if (shift) KeyEvent.KEYCODE_SHIFT_LEFT to KeyEvent.META_SHIFT_ON else null)
        var state = 0
        modifiers.forEach { (code, mask) -> state = state or mask; add(Stroke(code, true, state)) }
        add(Stroke(keyCode, true, state))
        add(Stroke(keyCode, false, state))
        modifiers.asReversed().forEach { (code, mask) -> state = state and mask.inv(); add(Stroke(code, false, state)) }
    }

    /** A failed/throwing send must not leave a modifier held in the receiving application. */
    fun dispatch(emit: (Stroke) -> Boolean): Boolean {
        val pressed = linkedSetOf<Int>()
        var state = 0
        try {
            for (stroke in strokes()) {
                if (stroke.down) pressed.add(stroke.code)
                state = stroke.meta
                if (!emit(stroke)) return false
                if (!stroke.down) pressed.remove(stroke.code)
            }
            return true
        } finally {
            pressed.toList().asReversed().forEach { code ->
                val mask = when (code) {
                    KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON
                    KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON
                    KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON
                    else -> 0
                }
                state = state and mask.inv()
                runCatching { emit(Stroke(code, false, state)) }
            }
        }
    }

    companion object {
        val keys: Map<String, Int> = buildMap {
            ('A'..'Z').forEach { put(it.toString(), KeyEvent.KEYCODE_A + (it - 'A')) }
            ('0'..'9').forEach { put(it.toString(), KeyEvent.KEYCODE_0 + (it - '0')) }
            putAll(mapOf("Left" to KeyEvent.KEYCODE_DPAD_LEFT, "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
                "Up" to KeyEvent.KEYCODE_DPAD_UP, "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
                "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB,
                "Enter" to KeyEvent.KEYCODE_ENTER, "Space" to KeyEvent.KEYCODE_SPACE,
                "Backspace" to KeyEvent.KEYCODE_DEL, "Delete" to KeyEvent.KEYCODE_FORWARD_DEL,
                "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
                "PageUp" to KeyEvent.KEYCODE_PAGE_UP, "PageDown" to KeyEvent.KEYCODE_PAGE_DOWN))
            (1..12).forEach { put("F$it", KeyEvent.KEYCODE_F1 + it - 1) }
        }

        /** Strict canonical form keeps old comma-separated mappings unambiguous. */
        fun parse(value: String): ShortcutKeyChord? {
            if (value.length > 40) return null
            if (!value.startsWith("按键:")) return null
            val parts = value.removePrefix("按键:").split('+')
            val key = parts.last()
            if (key !in keys) return null
            val modifiers = parts.dropLast(1)
            if (modifiers.any { it !in setOf("Ctrl", "Alt", "Shift") } || modifiers.distinct().size != modifiers.size) return null
            val chord = ShortcutKeyChord(key, "Ctrl" in modifiers, "Alt" in modifiers, "Shift" in modifiers)
            return chord.takeIf { it.encoded == value }
        }
    }
}
