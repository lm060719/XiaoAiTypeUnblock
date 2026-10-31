package io.mo.xatype.keyboard18

/** Private-use codes distinguish ambiguous taps from exact letters chosen in a popup. */
object Keyboard18Keys {
    val rows = listOf(listOf("q", "we", "rt", "y", "u", "io", "p"),
        listOf("a", "sd", "fg", "h", "jk", "l"), listOf("z", "xc", "v", "bn", "m"))
    val groups = rows.flatten()
    private const val BASE = 0xE100
    private val representatives = ('a'..'z').associateWith { letter -> groups.first { letter in it }.first() }
    val hints = listOf(":", "1", "2", "3", "/", "-", "=", "!", "4", "5", "6", "()", "?", "@", "7", "8", "9", "0")
    fun code(group: String) = BASE + groups.indexOf(group).also { require(it >= 0) }
    fun group(code: Int): String? = groups.getOrNull(code - BASE)
    fun representative(letter: Char): Char = if (letter == ';') 'l' else representatives.getValue(letter)
    fun valid(token: Char) = token in 'a'..'z' || group(token.code) != null || token == '\''
    fun matches(token: Char, letter: Char) = group(token.code)?.let {
        letter in it || (it == "l" && letter == ';')
    } ?: (token == letter)
    fun projected(token: Char): Char = group(token.code)?.first() ?: representative(token)
    fun readable(raw: String) = buildString {
        raw.forEach { append(group(it.code)?.let { letters -> if (letters.length > 1) "[$letters]" else letters } ?: it.toString()) }
    }
}
