package io.mo.xatype.keyboard

/** Shared release actions for the native nine/26-key (including shape input) and custom 18-key layouts. */
object KeyboardShortcuts {
    /** Internal action of the 18-key delete key: swipe up clears, swipe down restores. Never drawn as a label. */
    const val CLEAR_INPUT = "清空输入"
    /** Native 26-key letters, shared by pinyin, English and the shape (虎码/五笔) keyboard. */
    val letters = mapOf('z' to "全选", 'x' to "剪切", 'c' to "复制", 'v' to "粘贴", 'm' to "段尾")
    val groups18 = mapOf("z" to "段首", "xc" to "复制", "v" to "粘贴", "bn" to "全选", "m" to "段尾")
    val groups9 = mapOf("pqrs" to "全选", "tuv" to "复制", "wxyz" to "粘贴", "ghi" to "剪切", "mno" to "段尾")
    val t9Letters = mapOf('2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz")
    val actions = (letters.values + groups18.values + groups9.values).toSet()

    /** ExtractedText selections are relative to its text, while setSelection is absolute. */
    fun paragraphPosition(text: CharSequence, selection: Int, startOffset: Int, end: Boolean): Int {
        val caret = selection.coerceIn(0, text.length)
        val value = text.toString()
        val relative = if (end) value.indexOf('\n', caret).let { if (it < 0) value.length else it }
            else value.lastIndexOf('\n', caret - 1) + 1
        return startOffset + relative
    }
}
