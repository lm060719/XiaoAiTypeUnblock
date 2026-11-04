package io.mo.xatype.dict

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Reduces Rime `.dict.yaml` tables to plain Chinese words with a weight. Pinyin, tone marks and
 * auxiliary codes are dropped: the host engine derives its own readings from the word.
 */
object RimeWordExtractor {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 7

    class Entry(val word: String, val weight: Int)

    /** Only BMP Han characters: the host parser skips surrogate pairs, and Latin or digits are not words. */
    fun isImportable(word: String): Boolean {
        if (word.length !in MIN_LENGTH..MAX_LENGTH) return false
        return word.all { it in '一'..'鿿' || it in '㐀'..'䶿' || it == '〇' }
    }

    /** `词<TAB>拼音<TAB>权重`, `词<TAB>权重` and `词<TAB>拼音` are all accepted; header and comments are not. */
    fun parseLine(line: String): Entry? {
        if (line.isEmpty() || line[0] == '#') return null
        val tab = line.indexOf('\t')
        if (tab <= 0) return null
        val word = line.substring(0, tab).trim()
        if (!isImportable(word)) return null
        val last = line.substring(line.lastIndexOf('\t') + 1).trim()
        val weight = if (last.isNotEmpty() && last.all { it.isDigit() || it == '.' }) {
            last.toDoubleOrNull()?.coerceIn(0.0, Int.MAX_VALUE.toDouble())?.toInt() ?: 0
        } else 0
        return Entry(word, weight)
    }

    /**
     * Streams the named entries of a zip archive and keeps the [maxWords] heaviest distinct words.
     * Memory stays near 2 × [maxWords] even for multi-million-line tables.
     */
    fun extract(
        zip: InputStream,
        entryNames: Collection<String>,
        maxWords: Int,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): List<Entry> {
        require(maxWords > 0)
        val best = HashMap<String, Int>(maxWords.coerceAtMost(1 shl 20))
        var floor = 0 // Words lighter than this were already pruned and cannot make the cut again.
        var seenEntries = 0
        var lines = 0L
        ZipInputStream(zip).use { archive ->
            while (true) {
                val item = archive.nextEntry ?: break
                if (item.isDirectory || item.name.substringAfterLast('/') !in entryNames) continue
                seenEntries++
                val reader = BufferedReader(InputStreamReader(archive, Charsets.UTF_8), 1 shl 16)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (++lines and 0x3FFFF == 0L) {
                        check(!isCancelled()) { "已取消" }
                        onProgress(best.size)
                    }
                    val entry = parseLine(line) ?: continue
                    if (entry.weight < floor) continue
                    val old = best[entry.word]
                    if (old == null || old < entry.weight) best[entry.word] = entry.weight
                    if (best.size > maxWords * 2) floor = prune(best, maxWords)
                }
            }
        }
        check(seenEntries > 0) { "压缩包中没有找到所需词库文件" }
        return best.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(maxWords).map { Entry(it.key, it.value) }
    }

    /** Drops everything below the [keep]-th heaviest weight and returns that weight. */
    private fun prune(best: MutableMap<String, Int>, keep: Int): Int {
        val weights = best.values.toIntArray().also { it.sort() }
        val threshold = weights[weights.size - keep]
        best.values.removeAll { it < threshold }
        // Many words may share the cut weight; trim those ties so the map really shrinks.
        var excess = best.size - keep
        if (excess > 0) {
            val iterator = best.entries.iterator()
            while (excess > 0 && iterator.hasNext()) {
                if (iterator.next().value == threshold) { iterator.remove(); excess-- }
            }
        }
        return threshold
    }
}
