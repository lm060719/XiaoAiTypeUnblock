package io.mo.xatype.keyboard

import io.mo.xatype.keyboard18.Keyboard14Keys
import io.mo.xatype.keyboard18.Keyboard18Keys

/**
 * Downward-swipe actions per keyboard. Each keyboard maps its letter keys (26-key letters,
 * 18/14-key groups, nine-key letter groups) to an action; users may reassign any key.
 */
enum class ShortcutKeyboard(
    val title: String,
    /** Preference holding the user's mapping; absent means [defaults]. */
    val prefKey: String,
    /** Assignable keys in display order, as lowercase letters or letter groups. */
    val keys: List<String>,
    val defaults: Map<String, String>,
    /** Keyboard rows of [keys], for the settings editor. */
    val rows: List<List<String>>,
) {
    NINE("九键", "pref_shortcut_map_t9",
        listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"),
        mapOf("pqrs" to "全选", "tuv" to "复制", "wxyz" to "粘贴", "ghi" to "剪切", "mno" to "段尾"),
        listOf(listOf("abc", "def"), listOf("ghi", "jkl", "mno"), listOf("pqrs", "tuv", "wxyz"))),
    QWERTY("26 键", "pref_shortcut_map_26",
        ('a'..'z').map(Char::toString),
        mapOf("z" to "全选", "x" to "剪切", "c" to "复制", "v" to "粘贴", "m" to "段尾"),
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { row -> row.map(Char::toString) }),
    KEYBOARD18("18 键", "pref_shortcut_map_18",
        Keyboard18Keys.groups,
        mapOf("z" to "段首", "xc" to "复制", "v" to "粘贴", "bn" to "全选", "m" to "段尾"),
        Keyboard18Keys.rows),
    /** 14-key bottom row: ZX · CV · BN · M, following the 26-key Z 全选 / C 复制 / V 粘贴 / M 段尾. */
    KEYBOARD14("14 键", "pref_shortcut_map_14",
        Keyboard14Keys.groups,
        mapOf("zx" to "全选", "cv" to "复制", "bn" to "粘贴", "m" to "段尾"),
        Keyboard14Keys.rows);

    /** Label of [key] on this keyboard: nine-key shows its digit before the letters. */
    fun keyLabel(key: String): String = if (this == NINE)
        "${KeyboardShortcuts.t9Letters.entries.first { it.value == key }.key} ${key.uppercase()}" else key.uppercase()

    /** Decode a stored mapping; null (never edited) yields [defaults]. Unknown keys and actions are dropped. */
    fun decode(stored: String?): Map<String, String> {
        if (stored == null) return defaults
        return stored.split(',').mapNotNull { entry ->
            val (key, action) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            (key to action).takeIf { key in keys && action in KeyboardShortcuts.actions }
        }.toMap()
    }

    fun encode(mapping: Map<String, String>): String = keys.mapNotNull { key ->
        mapping[key]?.takeIf { it in KeyboardShortcuts.actions }?.let { "$key=$it" }
    }.joinToString(",")
}

/** Shared release actions for the native nine/26-key (including shape input) and custom 18/14-key layouts. */
object KeyboardShortcuts {
    /** Every action, in the order offered by the settings editor. */
    val actionOrder = listOf("全选", "复制", "剪切", "粘贴", "段首", "段尾")
    val actions = actionOrder.toSet()
    val t9Letters = mapOf('2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz")

    /** Default mappings, kept for callers that need the built-in layout. */
    val letters get() = ShortcutKeyboard.QWERTY.defaults.mapKeys { it.key.single() }
    val groups18 get() = ShortcutKeyboard.KEYBOARD18.defaults
    val groups14 get() = ShortcutKeyboard.KEYBOARD14.defaults
    val groups9 get() = ShortcutKeyboard.NINE.defaults

    /** ExtractedText selections are relative to its text, while setSelection is absolute. */
    fun paragraphPosition(text: CharSequence, selection: Int, startOffset: Int, end: Boolean): Int {
        val caret = selection.coerceIn(0, text.length)
        val value = text.toString()
        val relative = if (end) value.indexOf('\n', caret).let { if (it < 0) value.length else it }
            else value.lastIndexOf('\n', caret - 1) + 1
        return startOffset + relative
    }
}
