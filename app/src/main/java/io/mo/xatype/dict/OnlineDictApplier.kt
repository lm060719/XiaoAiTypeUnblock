package io.mo.xatype.dict

import java.io.File
import java.io.InputStream

/** The few host user-word operations needed; the real implementation is reflective, tests use a fake. */
interface UserWordEngine {
    /** Every user word, or null while the engine is not ready. */
    fun words(): List<String>?

    /** Additive import of a `词 数字` text file; 0 on success. Never removes words by itself. */
    fun importFile(path: String): Int

    /** Removes words previously added by [importFile]; words that are not present are ignored. */
    fun delete(words: List<String>)
    fun save()
}

/**
 * Runs inside the input method process. Imports only ever add words (host mode 1); the words this
 * class added are remembered in [dir] so a source switch or "clear" removes exactly those, never
 * words the user typed or that existed before.
 *
 * The host user dictionary is small (about 16 000 words on 0.2.1246) and silently evicts the oldest
 * words when it is full, which would throw away the user's own words. Imports therefore stay below
 * [totalLimit] and stop at once if any word that existed before disappears.
 */
class OnlineDictApplier(
    private val engine: UserWordEngine,
    private val dir: File,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val totalLimit: Int = TOTAL_LIMIT
) {
    class Result(
        val ok: Boolean,
        val message: String,
        val imported: Int = 0,
        /** True when the engine was not ready and the same request should be retried later. */
        val retry: Boolean = false,
        /** For the log: user words before and after, and the longest single engine import call. */
        val before: Int = -1,
        val after: Int = -1,
        val slowestMs: Long = 0
    )

    private val record = File(dir, "imported-words.txt")

    fun apply(action: String, words: InputStream?): Result {
        if (engine.words() == null) return Result(false, "输入法词库尚未就绪，下次启动输入法时重试", retry = true)
        dir.mkdirs()
        val initial = engine.words().orEmpty().size
        val removal = removeRecorded()
        if (action == OnlineDictStore.ACTION_CLEAR) {
            val note = if (removal.failed > 0) "，${removal.failed} 个未能删除，下次清除时重试" else ""
            return Result(removal.failed == 0, "已清除 ${removal.removed} 个导入词$note",
                before = initial, after = engine.words().orEmpty().size)
        }
        if (removal.failed > 0) {
            return Result(false, "上一来源有 ${removal.failed} 个词未能删除，已暂停导入，请先清除后重试")
        }
        if (words == null) return Result(false, "没有可导入的词库文件")

        val fileWords = LinkedHashSet<String>()
        words.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val word = line.substringBefore(' ')
                if (word.isNotEmpty()) fileWords.add(word)
            }
        }
        if (fileWords.isEmpty()) return Result(false, "词库文件为空")

        val existingList = engine.words().orEmpty()
        val existing = existingList.toHashSet()
        val candidates = fileWords.filter { it !in existing }
        val alreadyThere = fileWords.size - candidates.size
        val room = (totalLimit - existing.size).coerceAtLeast(0)
        val planned = candidates.take(room)
        val overLimit = candidates.size - planned.size
        if (planned.isEmpty()) {
            return Result(room > 0, if (room == 0) "用户词库已有 ${existing.size} 个词，接近容量上限，未导入以免挤掉你自己的词"
                else "词库中的词都已存在，无需导入")
        }

        // Recorded before importing, so an interrupted run can still be undone later.
        writeRecord(planned)
        val chunk = File(dir, "chunk.txt")
        var index = 0
        var size = FIRST_CHUNK
        var failed = 0
        var slowest = 0L
        var lost = emptyList<String>()
        try {
            while (index < planned.size) {
                val part = planned.subList(index, minOf(planned.size, index + size))
                chunk.bufferedWriter(Charsets.UTF_8).use { out ->
                    part.forEach { out.write(it); out.write(" ${OnlineDictStore.WORD_TYPE}\n") }
                }
                val start = clock()
                if (engine.importFile(chunk.path) != 0) failed++
                val spent = (clock() - start).coerceAtLeast(1)
                slowest = maxOf(slowest, spent)
                index += part.size
                // Keep each blocking call near TARGET_MS, whatever the device and dictionary size.
                size = (size * TARGET_MS / spent).toInt().coerceIn(MIN_CHUNK, MAX_CHUNK)

                val now = engine.words().orEmpty().toHashSet()
                lost = existingList.filter { it !in now }
                if (lost.isNotEmpty()) break
                pause(PAUSE_MS) // Let keyboard input run between batches.
            }
        } finally {
            chunk.delete()
        }

        var restored = 0
        if (lost.isNotEmpty()) restored = restore(lost, planned.subList(0, index), chunk)
        engine.save()
        val after = engine.words().orEmpty().toHashSet()
        val added = planned.filter { it in after }
        writeRecord(added)

        val notes = ArrayList<String>()
        if (alreadyThere > 0) notes += "$alreadyThere 个已存在"
        if (overLimit > 0) notes += "因用户词库容量限制少导入 $overLimit 个"
        if (planned.size - added.size > 0 && lost.isEmpty()) notes += "${planned.size - added.size} 个被输入法拒绝"
        if (failed > 0) notes += "$failed 批返回异常"
        if (lost.isNotEmpty()) {
            val missing = lost.size - restored
            notes += "检测到用户词库已满，已停止导入" + if (missing > 0) "，$missing 个原有词未能恢复" else "并恢复了被挤掉的词"
        }
        val suffix = if (notes.isEmpty()) "" else "（${notes.joinToString("，")}）"
        return Result(lost.isEmpty(), "已导入 ${added.size} 个词$suffix", added.size,
            before = initial, after = after.size, slowestMs = slowest)
    }

    /**
     * Puts back words the host evicted; the host only re-adds them through the same import path.
     * The newest imported words are dropped first, otherwise re-adding would evict more old words.
     */
    private fun restore(lost: List<String>, imported: List<String>, chunk: File): Int {
        val present = engine.words().orEmpty().toHashSet()
        imported.asReversed().filter { it in present }.take(lost.size + MIN_CHUNK)
            .chunked(DELETE_CHUNK).forEach(engine::delete)
        lost.chunked(MAX_CHUNK).forEach { part ->
            chunk.bufferedWriter(Charsets.UTF_8).use { out ->
                part.forEach { out.write(it); out.write(" ${OnlineDictStore.WORD_TYPE}\n") }
            }
            engine.importFile(chunk.path)
            chunk.delete()
        }
        val now = engine.words().orEmpty().toHashSet()
        return lost.count { it in now }
    }

    private class Removal(val removed: Int, val failed: Int)

    private fun removeRecorded(): Removal {
        if (!record.isFile) return Removal(0, 0)
        val recorded = record.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        val before = engine.words().orEmpty().toHashSet()
        val present = recorded.filter { it in before }
        present.chunked(DELETE_CHUNK).forEach { part ->
            engine.delete(part)
            pause(PAUSE_MS)
        }
        engine.save()
        val after = engine.words().orEmpty().toHashSet()
        val remaining = present.filter { it in after }
        // Words that could not be deleted stay recorded, so a later clear tries them again.
        if (remaining.isEmpty()) record.delete() else writeRecord(remaining)
        return Removal(present.size - remaining.size, remaining.size)
    }

    private fun writeRecord(words: List<String>) {
        val temp = File(dir, "imported-words.tmp")
        temp.bufferedWriter(Charsets.UTF_8).use { out -> words.forEach { out.write(it); out.write("\n") } }
        check(temp.renameTo(record) || (record.delete() && temp.renameTo(record))) { "无法保存导入记录" }
    }

    companion object {
        /** Total user words allowed after an import; the host holds about 16 000 and evicts beyond that. */
        const val TOTAL_LIMIT = 12_000
        const val FIRST_CHUNK = 200
        const val MIN_CHUNK = 50
        const val MAX_CHUNK = 1000
        const val TARGET_MS = 80L
        const val DELETE_CHUNK = 200
        const val PAUSE_MS = 120L
    }
}
