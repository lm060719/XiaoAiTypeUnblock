package io.mo.xatype.keyboard18

import java.io.InputStream
import java.util.zip.GZIPInputStream
import io.mo.xatype.keyboard.ShuangpinScheme
import kotlin.math.ln

data class Pinyin18Entry(val text: String, val spelling: String, val weight: Int, val inputSpelling: String = spelling, val literal: Boolean = false) {
    val code = inputSpelling.replace(" ", "")
    val initials = inputSpelling.split(' ').joinToString("") { it.first().toString() }
    val syllableCount = initials.length
    val boundaries = buildSet {
        var position = 0
        inputSpelling.forEach { if (it == ' ') add(position) else position++ }
    }
    val score = ln(weight.toDouble() + 1.0)
}

/** Project once when loading; each tap walks one trie path, never 2^n letter permutations. */
class Keyboard18Dictionary private constructor(val entries: List<Pinyin18Entry>, val scheme: ShuangpinScheme?) {
    fun valid(token: Char) = Keyboard18Keys.valid(token) || (scheme?.usesSemicolon == true && token == ';')
    private class Node {
        // Most trie nodes are leaves or pass-through; allocate collections only when needed.
        var children: HashMap<Char, Node>? = null
        var words: MutableList<Pinyin18Entry>? = null
        var completions: MutableList<Pinyin18Entry>? = null
    }
    private val root = Node()
    private val initialRoot = Node()
    val maxLength = entries.maxOf { it.code.length }
    val maxScore = entries.maxOf { it.score }
    /** Only rows containing Latin letters; a full text set would cost a 100k-entry HashSet. */
    private val literalWeights = HashMap<String, Int>()
    init {
        entries.forEach { entry ->
            if (entry.literal) literalWeights[entry.text] = maxOf(literalWeights[entry.text] ?: 0, entry.weight)
        }
    }
    /** True when [text] is a real English/mixed dictionary row, not a synthesized Latin path. */
    fun isLiteral(text: String): Boolean = text in literalWeights
    /** Highest corpus weight of a literal (English/tech) entry for [text], or 0. */
    fun literalWeight(text: String): Int = literalWeights[text] ?: 0

    init {
        // Input rows are ordered by descending corpus frequency.
        entries.forEach { entry ->
            var node = root
            entry.code.forEach { letter ->
                val children = node.children ?: HashMap<Char, Node>().also { node.children = it }
                node = children.getOrPut(Keyboard18Keys.representative(letter)) { Node() }
                val completions = node.completions ?: ArrayList<Pinyin18Entry>().also { node.completions = it }
                if (completions.size < COMPLETIONS) completions.add(entry)
            }
            (node.words ?: ArrayList<Pinyin18Entry>().also { node.words = it }).add(entry)
            // Index each syllable's first letter separately from the literal full spelling.
            // A grouped tap still has to contain that initial: WE is never an alias for S.
            if (scheme == null && entry.initials != entry.code && !entry.literal) {
                var initialNode = initialRoot
                entry.initials.forEach { letter ->
                    val children = initialNode.children ?: HashMap<Char, Node>().also { initialNode.children = it }
                    initialNode = children.getOrPut(Keyboard18Keys.representative(letter)) { Node() }
                }
                (initialNode.words ?: ArrayList<Pinyin18Entry>().also { initialNode.words = it }).add(entry)
            }
        }
    }

    private fun compatible(entry: Pinyin18Entry, raw: String, start: Int, end: Int, abbreviated: Boolean = false): Boolean {
        val code = if (abbreviated) entry.initials else entry.code
        var letter = 0
        for (position in start until end) {
            val token = raw[position]
            if (token == '\'') {
                if (abbreviated) {
                    if (letter <= 0 || letter >= code.length) return false
                } else if (letter !in entry.boundaries) return false
            } else {
                if (letter >= code.length || !Keyboard18Keys.matches(token, code[letter])) return false
                letter++
            }
        }
        return true
    }

    fun words(raw: String, start: Int) = findWords(root, raw, start, false)
    fun abbreviations(raw: String, start: Int) = findWords(initialRoot, raw, start, true)

    /**
     * Entries spelling all of `raw[start, end)`, without the per-code cap of [words]: the lattice
     * needs only the frequent few, but the candidate list must reach every character of a reading.
     */
    fun wholeWords(raw: String, start: Int) = findWhole(root, raw, start, false)
    fun wholeAbbreviations(raw: String, start: Int) = findWhole(initialRoot, raw, start, true)

    private fun findWhole(rootNode: Node, raw: String, start: Int, abbreviated: Boolean): List<Pinyin18Entry> {
        var node = rootNode
        var count = 0
        for (position in start until raw.length) {
            if (raw[position] == '\'') continue
            if (++count > maxLength) return emptyList()
            node = node.children?.get(Keyboard18Keys.projected(raw[position])) ?: return emptyList()
        }
        if (count == 0) return emptyList()
        val result = ArrayList<Pinyin18Entry>()
        for (entry in node.words.orEmpty()) {
            if (compatible(entry, raw, start, raw.length, abbreviated)) {
                result.add(entry)
                if (result.size == WHOLE_WORDS) break
            }
        }
        return result
    }

    private fun findWords(rootNode: Node, raw: String, start: Int, abbreviated: Boolean): List<Pair<Int, Pinyin18Entry>> {
        val result = ArrayList<Pair<Int, Pinyin18Entry>>()
        var node = rootNode
        var count = 0
        for (position in start until raw.length) {
            val token = raw[position]
            if (token == '\'') continue
            if (++count > maxLength) break
            node = node.children?.get(Keyboard18Keys.projected(token)) ?: break
            var retained = 0
            for (entry in node.words.orEmpty()) {
                if (compatible(entry, raw, start, position + 1, abbreviated)) {
                    result.add(position + 1 to entry)
                    if (++retained == WORDS_PER_CODE) break
                }
            }
        }
        return result
    }

    fun completions(raw: String, start: Int): List<Pinyin18Entry> {
        var node = root
        var count = 0
        for (position in start until raw.length) {
            if (raw[position] == '\'') continue
            count++
            node = node.children?.get(Keyboard18Keys.projected(raw[position])) ?: return emptyList()
        }
        if (count == 0) return emptyList()
        return node.completions.orEmpty().filter { it.code.length > count && it.code.length - count <= 6 &&
            compatible(it, raw, start, raw.length) }.take(COMPLETIONS)
    }

    companion object {
        private const val COMPLETIONS = 24
        private const val WORDS_PER_CODE = 48
        /** yi alone has 300+ characters; this bounds only two-letter abbreviation nodes. */
        private const val WHOLE_WORDS = 600
        fun read(input: InputStream, scheme: ShuangpinScheme? = null): Keyboard18Dictionary {
            val rows = ArrayList<Pinyin18Entry>()
            val encoded = HashMap<String, String?>()
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    // A malformed or supplementary row must not take the whole keyboard down;
                    // skip it and keep the rest of the corpus usable.
                    val parts = line.split('\t')
                    if (parts.size != 3 || parts[0].isEmpty()) return@forEach
                    val weight = parts[2].toIntOrNull() ?: return@forEach
                    val literal = parts[0].any { it in 'a'..'z' || it in 'A'..'Z' }
                    val code = parts[1].replace(" ", "")
                    if (code.isEmpty() || code.length > 48 || !code.all { it in 'a'..'z' }) return@forEach
                    val inputSpelling = when {
                        scheme == null -> parts[1]
                        // Pure English rows are typed letter by letter in every scheme.
                        literal && parts[0].all { it in 'a'..'z' || it in 'A'..'Z' } -> parts[1]
                        // Mixed rows (qq飞车, B站): encode the pinyin syllables, keep Latin pieces.
                        literal -> parts[1].split(' ').joinToString(" ") { syllable ->
                            encoded.getOrPut(syllable) { scheme.encodeSyllable(syllable) } ?: syllable
                        }
                        else -> parts[1].split(' ').map { syllable ->
                            encoded.getOrPut(syllable) { scheme.encodeSyllable(syllable) }
                                ?: return@forEach
                        }.joinToString(" ")
                    }
                    rows.add(Pinyin18Entry(parts[0], parts[1], weight.coerceAtLeast(0), inputSpelling, literal))
                }
            }
            require(rows.isNotEmpty() && rows.size <= 300_000) { "拼音词库为空或过大" }
            return Keyboard18Dictionary(rows.sortedByDescending { it.weight }, scheme)
        }
    }
}
