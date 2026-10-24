package io.mo.xatype.keyboard

/** Shared release actions for the native nine/26-key and custom 18-key layouts. */
object KeyboardShortcuts {
    val letters = mapOf('z' to "段首", 'c' to "复制", 'v' to "粘贴", 'b' to "剪切", 'm' to "段尾")
    val groups18 = mapOf("z" to "段首", "xc" to "复制", "v" to "粘贴", "bn" to "全选", "m" to "段尾")
    val groups9 = mapOf("pqrs" to "段首", "tuv" to "复制", "wxyz" to "粘贴", "ghi" to "剪切", "mno" to "段尾")
    val t9Letters = mapOf('2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz")
    val actions = letters.values.toSet()

    /** ExtractedText selections are relative to its text, while setSelection is absolute. */
    fun paragraphPosition(text: CharSequence, selection: Int, startOffset: Int, end: Boolean): Int {
        val caret = selection.coerceIn(0, text.length)
        val value = text.toString()
        val relative = if (end) value.indexOf('\n', caret).let { if (it < 0) value.length else it }
            else value.lastIndexOf('\n', caret - 1) + 1
        return startOffset + relative
    }
}
