package io.mo.xatype.keyboard18

/**
 * One grouped-letter layout. Private-use codes distinguish ambiguous taps from exact letters
 * chosen in a popup; each layout owns its own code range, so a code identifies its layout.
 */
open class GroupedKeys(
    /** Short name shown in status messages and the picker, e.g. "18键". */
    val title: String,
    val rows: List<List<String>>,
    val hints: List<String>,
    private val base: Int,
    /** Prefix of learned-composition keys; empty keeps the original 18-key storage. */
    val compositionPrefix: String,
    /** The left key of the last letter row: a shift key, or a syllable separator. */
    val separatorKey: Boolean,
    val controlWeight: Float,
    /** Long-press of L also offers ';' for double-pinyin schemes using the semicolon. */
    val semicolonGroup: String = "l",
) {
    val groups = rows.flatten()
    private val representatives = ('a'..'z').associateWith { letter -> groups.first { letter in it }.first() }
    init {
        require(hints.size == groups.size && groups.joinToString("").toSortedSet() == ('a'..'z').toSortedSet())
    }
    fun code(group: String) = base + groups.indexOf(group).also { require(it >= 0) }
    fun group(code: Int): String? = if (code in base until base + groups.size) groups[code - base] else null
    fun hint(group: String) = hints[groups.indexOf(group)]
    fun representative(letter: Char): Char = if (letter == ';') semicolonGroup.first() else representatives.getValue(letter)
    fun valid(token: Char) = token in 'a'..'z' || group(token.code) != null || token == '\''
    fun matches(token: Char, letter: Char) = group(token.code)?.let {
        letter in it || (it == semicolonGroup && letter == ';')
    } ?: (token == letter)
    fun projected(token: Char): Char = group(token.code)?.first() ?: representative(token)
    /** Preedit letter of one tap: a group shows its first letter, an exact letter or separator itself. */
    fun shown(token: Char): Char = group(token.code)?.first() ?: token
    fun readable(raw: String) = buildString {
        raw.forEach { append(group(it.code)?.let { letters -> if (letters.length > 1) "[$letters]" else letters } ?: it.toString()) }
    }
    /** Group typed by [letter], also when the host reports the representative letter instead of the private code. */
    fun groupOf(code: Int): String? = group(code) ?: groups.firstOrNull { it.first() == code.toChar().lowercaseChar() }

    companion object {
        val all: List<GroupedKeys> get() = listOf(Keyboard18Keys, Keyboard14Keys)
        /** The layout owning a private-use group [code], or null for ordinary characters. */
        fun of(code: Int): GroupedKeys? = all.firstOrNull { it.group(code) != null }
    }
}

/** 7 · 6 · 5: single and paired letters, shift at the left of the last row. */
object Keyboard18Keys : GroupedKeys("18键",
    listOf(listOf("q", "we", "rt", "y", "u", "io", "p"),
        listOf("a", "sd", "fg", "h", "jk", "l"), listOf("z", "xc", "v", "bn", "m")),
    listOf(":", "1", "2", "3", "/", "-", "=", "!", "4", "5", "6", "()", "?", "@", "7", "8", "9", "0"),
    base = 0xE100, compositionPrefix = "", separatorKey = false, controlWeight = 1f)

/** 5 · 5 · 4: QWERTY letters paired, a syllable separator at the left of the last row. */
object Keyboard14Keys : GroupedKeys("14键",
    listOf(listOf("qw", "er", "ty", "ui", "op"),
        listOf("as", "df", "gh", "jk", "l"), listOf("zx", "cv", "bn", "m")),
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "~", "!", "@", "?"),
    base = 0xE140, compositionPrefix = "K14:", separatorKey = true, controlWeight = 0.77f)
