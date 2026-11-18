package io.mo.xatype.dict

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class OnlineDictApplierTest {
    @get:Rule val temp = TemporaryFolder()

    /**
     * Mimics the host: a bounded store that evicts the oldest words when full, optional words that
     * refuse deletion, and import time that grows with the batch size.
     */
    private class FakeEngine(
        var ready: Boolean = true,
        val store: LinkedHashSet<String> = linkedSetOf(),
        val capacity: Int = Int.MAX_VALUE,
        val undeletable: MutableSet<String> = mutableSetOf()
    ) : UserWordEngine {
        val batchSizes = ArrayList<Int>()
        var now = 0L
        override fun words() = if (ready) store.toList() else null
        override fun importFile(path: String): Int {
            val lines = File(path).readLines()
            batchSizes += lines.size
            now += lines.size / 2L // 0.5 ms per word
            lines.forEach {
                store.add(it.substringBefore(' '))
                while (store.size > capacity) store.remove(store.first())
            }
            return 0
        }
        override fun delete(words: List<String>) { words.filter { it !in undeletable }.forEach(store::remove) }
        override fun save() {}
    }

    private fun words(vararg w: String) = ByteArrayInputStream(w.joinToString("") { "$it 0\n" }.toByteArray())
    private fun many(n: Int, prefix: String = "词") = (0 until n).map { "$prefix$it" }.toTypedArray()
    private fun applier(engine: FakeEngine, dir: File = temp.newFolder(), limit: Int = 100_000) =
        OnlineDictApplier(engine, dir, pause = {}, clock = { engine.now }, totalLimit = limit)

    @Test fun `import adds words in batches sized to the target time`() {
        val engine = FakeEngine(store = linkedSetOf("我的词"))
        val result = applier(engine).apply(OnlineDictStore.ACTION_IMPORT, words(*many(8_000), "我的词"))
        assertTrue(result.message, result.ok)
        assertEquals(8_000, result.imported)
        assertEquals(8_001, engine.store.size)
        assertEquals(OnlineDictApplier.FIRST_CHUNK, engine.batchSizes.first())
        // 80 ms at 0.5 ms per word settles at 160 words per batch.
        assertTrue(engine.batchSizes.drop(1).dropLast(1).all { it == 160 })
        assertTrue(result.message, "1 个已存在" in result.message)
    }

    @Test fun `import stops at the total limit so user words are never evicted`() {
        val engine = FakeEngine(store = linkedSetOf("自造词甲", "自造词乙", "自造词丙"))
        val result = applier(engine, limit = 10).apply(OnlineDictStore.ACTION_IMPORT, words(*many(20)))
        assertTrue(result.ok)
        assertEquals(7, result.imported)
        assertEquals(10, engine.store.size)
        assertTrue(engine.store.containsAll(listOf("自造词甲", "自造词乙", "自造词丙")))
        assertTrue(result.message, "容量限制少导入 13 个" in result.message)
    }

    @Test fun `full dictionary refuses to import`() {
        val engine = FakeEngine(store = linkedSetOf(*many(10, "旧")))
        val result = applier(engine, limit = 10).apply(OnlineDictStore.ACTION_IMPORT, words("甲乙"))
        assertFalse(result.ok)
        assertEquals(0, result.imported)
        assertEquals(10, engine.store.size)
    }

    @Test fun `eviction of an existing word stops the import and restores it`() {
        // The device holds fewer words than the limit assumes.
        val engine = FakeEngine(store = linkedSetOf(*many(300, "旧")), capacity = 400)
        val result = applier(engine, limit = 5_000).apply(OnlineDictStore.ACTION_IMPORT, words(*many(2_000)))
        assertFalse(result.ok)
        assertTrue(result.message, "已停止导入" in result.message)
        assertTrue(engine.store.containsAll(many(300, "旧").toList()))
        assertTrue(engine.store.size <= 400)
        assertTrue(engine.batchSizes.sum() < 2_000)
    }

    @Test fun `switching source removes only what the previous import added`() {
        val engine = FakeEngine(store = linkedSetOf("自造词", "共有词"))
        val dir = temp.newFolder()
        applier(engine, dir).apply(OnlineDictStore.ACTION_IMPORT, words("雾凇词", "共有词"))
        assertEquals(setOf("自造词", "共有词", "雾凇词"), engine.store)
        val result = applier(engine, dir).apply(OnlineDictStore.ACTION_IMPORT, words("万象词"))
        assertTrue(result.ok)
        assertEquals(setOf("自造词", "共有词", "万象词"), engine.store)
    }

    @Test fun `clear reports what was really removed and keeps the rest for a retry`() {
        val engine = FakeEngine(store = linkedSetOf("自造词"), undeletable = mutableSetOf("顽固词"))
        val dir = temp.newFolder()
        applier(engine, dir).apply(OnlineDictStore.ACTION_IMPORT, words("甲乙", "丙丁", "顽固词"))

        val first = applier(engine, dir).apply(OnlineDictStore.ACTION_CLEAR, null)
        assertFalse(first.ok)
        assertEquals("已清除 2 个导入词，1 个未能删除，下次清除时重试", first.message)
        assertEquals(setOf("自造词", "顽固词"), engine.store)

        engine.undeletable.clear()
        val second = applier(engine, dir).apply(OnlineDictStore.ACTION_CLEAR, null)
        assertTrue(second.ok)
        assertEquals("已清除 1 个导入词", second.message)
        assertEquals(setOf("自造词"), engine.store)
    }

    @Test fun `a new source imports while undeletable old words stay recorded for clearing`() {
        val engine = FakeEngine(undeletable = mutableSetOf("顽固词"))
        val dir = temp.newFolder()
        applier(engine, dir).apply(OnlineDictStore.ACTION_IMPORT, words("顽固词"))
        val result = applier(engine, dir).apply(OnlineDictStore.ACTION_IMPORT, words("万象词"))
        assertTrue(result.message, result.ok)
        assertEquals(1, result.imported)
        assertTrue("万象词" in engine.store)
        assertEquals(listOf("顽固词", "万象词"), File(dir, "imported-words.txt").readLines())
        engine.undeletable.clear()
        val cleared = applier(engine, dir).apply(OnlineDictStore.ACTION_CLEAR, null)
        assertTrue(cleared.message, cleared.ok)
        assertTrue(engine.store.isEmpty())
    }

    @Test fun `engine not ready asks for a retry and changes nothing`() {
        val engine = FakeEngine(ready = false)
        val result = applier(engine).apply(OnlineDictStore.ACTION_IMPORT, words("甲乙"))
        assertTrue(result.retry)
        assertTrue(engine.batchSizes.isEmpty())
    }

    @Test fun `progress follows actual batches and excludes existing and over limit words`() {
        val engine = FakeEngine(store = linkedSetOf("已有词"))
        val progress = mutableListOf<Pair<Int, Int>>()
        val phases = mutableListOf<String>()
        val result = applier(engine, limit = 502).apply(
            OnlineDictStore.ACTION_IMPORT, words(*many(800), "已有词")
        ) { message, done, total ->
            phases += message
            if (message == "正在导入词条…") {
                assertEquals(done + 1, engine.store.size)
                progress += done to total
            }
        }
        assertTrue(result.ok)
        assertEquals(0 to 501, progress.first())
        assertEquals(501 to 501, progress.last())
        assertEquals(engine.batchSizes.runningFold(0, Int::plus), progress.map { it.first })
        assertTrue(progress.all { it.second == 501 })
        assertEquals("正在保存词库…", phases.last())
    }

    @Test fun `clear progress counts each processed batch without claiming deletion success`() {
        val engine = FakeEngine(undeletable = mutableSetOf("词0"))
        val dir = temp.newFolder()
        applier(engine, dir).apply(OnlineDictStore.ACTION_IMPORT, words(*many(450)))
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = applier(engine, dir).apply(OnlineDictStore.ACTION_CLEAR, null) { _, done, total ->
            if (total > 0) progress += done to total
        }
        assertEquals(listOf(0, 200, 400, 450), progress.map { it.first })
        assertTrue(progress.all { it.second == 450 })
        assertFalse(result.ok)
        assertEquals(setOf("词0"), engine.store)
    }

    @Test fun `eviction switches to recovery without reporting full import progress`() {
        val engine = FakeEngine(store = linkedSetOf(*many(300, "旧")), capacity = 400)
        val progress = mutableListOf<Pair<Int, Int>>()
        val phases = mutableListOf<String>()
        val result = applier(engine, limit = 5_000).apply(
            OnlineDictStore.ACTION_IMPORT, words(*many(2_000))
        ) { message, done, total ->
            phases += message
            if (message == "正在导入词条…") progress += done to total
        }
        assertFalse(result.ok)
        assertTrue(progress.none { it.first == it.second })
        assertEquals("正在恢复原有词条…", phases.last())
    }
}
