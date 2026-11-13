package io.mo.xatype.dict

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.FilterInputStream
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
        val collector = Collector(maxWords, onProgress, isCancelled)
        readArchive(zip, entryNames, collector)
        return collector.result()
    }

    /** Local tables share one collector so duplicates and the cap apply across all selected files. */
    class LocalFile(val name: String, val open: () -> InputStream)

    fun extractLocal(
        files: List<LocalFile>, source: OnlineDictSource, maxWords: Int,
        onProgress: (Int) -> Unit = {}, isCancelled: () -> Boolean = { false }
    ): List<Entry> {
        require(files.isNotEmpty()) { "请选择词库文件" }
        val collector = Collector(maxWords, onProgress, isCancelled)
        files.forEach { file ->
            check(!isCancelled()) { "已取消" }
            val name = file.name.lowercase()
            require(name.endsWith(".zip") || name.endsWith(".dict.yaml")) {
                "请选择 ZIP 压缩包或 UTF-8 .dict.yaml 词库文件"
            }
            file.open().use { input ->
                if (name.endsWith(".zip")) readArchive(input, source.dictFiles, collector)
                else collector.readTable(input)
            }
        }
        return collector.result().also {
            check(it.isNotEmpty()) { "没有可导入的词条，请选择包含实际词条的 UTF-8 词库文件，不能只选择引用其他词表的配置文件" }
        }
    }

    private fun readArchive(zip: InputStream, entryNames: Collection<String>, collector: Collector) {
        var seenEntries = 0
        ZipInputStream(zip.buffered()).use { archive ->
            while (true) {
                collector.checkCancelled()
                val item = archive.nextEntry ?: break
                if (!item.isDirectory && item.name.substringAfterLast('/') in entryNames) {
                    seenEntries++
                    collector.readTable(archive)
                } else {
                    // Drain through the same budget; skipped ZIP members can also be enormous.
                    collector.drain(archive)
                }
            }
        }
        check(seenEntries > 0) { "压缩包中没有找到所需词库文件" }
    }

    private class Collector(
        val maxWords: Int, val onProgress: (Int) -> Unit, val isCancelled: () -> Boolean
    ) {
        init { require(maxWords > 0 && maxWords <= 1_000_000) }
        val best = HashMap<String, Int>(maxWords)
        var floor = 0
        var bytes = 0L
        var lines = 0L

        fun checkCancelled() = check(!isCancelled()) { "已取消" }

        private fun bounded(input: InputStream) = object : FilterInputStream(input) {
            private fun count(n: Int): Int {
                checkCancelled()
                if (n > 0) bytes += n
                check(bytes <= 512L * 1024 * 1024) { "词库内容过大，请解压后仅选择需要的词库文件" }
                return n
            }
            override fun read(): Int = super.read().also { count(if (it < 0) 0 else 1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
            override fun close() = Unit // The archive/file owner closes the underlying stream.
        }

        fun drain(input: InputStream) {
            val stream = bounded(input)
            val buffer = ByteArray(1 shl 16)
            while (stream.read(buffer) >= 0) { /* Count skipped entries too. */ }
        }

        fun readTable(input: InputStream) {
            val reader = BufferedReader(InputStreamReader(bounded(input), Charsets.UTF_8), 1 shl 16)
            val line = StringBuilder()
            val chunk = CharArray(1 shl 14)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                for (index in 0 until count) {
                    val char = chunk[index]
                    if (char == '\n') {
                        accept(line.toString().removePrefix("\uFEFF").trimEnd('\r'))
                        line.setLength(0)
                    } else {
                        check(line.length < 16_384) { "词库单行过长，请检查文件格式" }
                        line.append(char)
                    }
                }
            }
            if (line.isNotEmpty()) accept(line.toString().removePrefix("\uFEFF").trimEnd('\r'))
        }

        private fun accept(line: String) {
            if (++lines % 262_144 == 0L) { checkCancelled(); onProgress(best.size.coerceAtMost(maxWords)) }
            val entry = parseLine(line) ?: return
            if (entry.weight < floor) return
            val old = best[entry.word]
            if (old == null || old < entry.weight) best[entry.word] = entry.weight
            if (best.size > maxWords * 2) floor = prune(best, maxWords)
        }

        fun result(): List<Entry> {
            checkCancelled()
            return best.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(maxWords).map { Entry(it.key, it.value) }
        }
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
