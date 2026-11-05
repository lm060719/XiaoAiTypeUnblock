package io.mo.xatype.keyboard18

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import io.mo.xatype.keyboard.ShuangpinScheme
import org.junit.Test

/** Regression cases use the shipped corpus and user input, rather than mirroring trie logic. */
class Keyboard18EngineTest {
    private fun grouped(vararg groups: String) = groups.joinToString("") { Keyboard18Keys.code(it).toChar().toString() }
    private fun engine() = Keyboard18Engine(dictionary)

    @Test fun `why is offered for the correct grouped initials and can be committed`() {
        val engine = engine()
        engine.inputText(grouped("we", "sd", "m"))
        assertEquals("为什么", engine.candidates.first().text)
        assertEquals("为什么", engine.input(' ').commit)
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `full pinyin still offers why before abbreviated character combinations`() {
        val engine = engine()
        engine.inputText(grouped("we", "we", "io", "sd", "h", "we", "bn", "m", "we"))
        assertEquals("为什么", engine.candidates.first().text)
        assertEquals("为什么", engine.select(0))
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `wrong second group does not become the s initial and rare repeated words are suppressed`() {
        val engine = engine()
        engine.inputText(grouped("we", "we", "m"))
        assertFalse(engine.candidates.any { it.text == "为什么" })
        assertFalse(engine.candidates.take(5).any { it.text in setOf("哦哦呒", "额额呒", "哦哦无", "哦哦嘸") })
    }

    @Test fun `exact initials constrain ambiguity and deleting a grouped tap restores candidates`() {
        val engine = engine()
        engine.inputText("wsm")
        assertEquals("为什么", engine.candidates.first().text)
        engine.clear()
        val input = grouped("we", "sd", "m")
        engine.inputText(input)
        assertEquals(input.dropLast(1), engine.backspace().raw)
        assertEquals("为什么", engine.input(input.last()).candidates.first().text)
        assertNull(engine.restore(input).commit)
    }

    @Test fun `common single syllable initial outranks the rare standalone m entry`() {
        val engine = engine()
        engine.inputText(grouped("m"))
        assertNotEquals("呒", engine.candidates.first().text)
        assertTrue(engine.candidates.any { it.text == "么" })
    }

    @Test fun `pinyin spellings from the english table are not candidates`() {
        val engine = Keyboard18Engine(mergedDictionary)
        engine.inputText("ai")
        assertEquals("爱", engine.candidates.first().text)
        // ai is also a real English/tech word; keep it as a candidate after the Chinese word.
        assertTrue(engine.candidates.any { it.text == "ai" })
        // Double-letter key abbreviations are pinyin fragments, not candidates.
        for (typed in listOf("sd", "rt", "fg", "io")) {
            engine.clear()
            engine.inputText(typed)
            assertTrue(engine.candidates.none { it.text == typed })
        }
    }

    @Test fun `typed sequence leads alternative decodings from the same keys`() {
        val engine = Keyboard18Engine(mergedDictionary())
        engine.inputText(grouped("u", "u", "rt", "u"))
        // No Latin echo or synthesized mixed phrase may lead; empty is fine for nonsense input.
        assertTrue(engine.candidates.none { candidate ->
            candidate.text.any { it in 'a'..'z' || it in 'A'..'Z' }
        })
    }

    @Test fun `learning a prefix does not displace a complete word`() {
        for (corpus in listOf(dictionary, testDictionary("你\tni\t1000\n你好\tni hao\t100000\n好\thao\t1000\n"))) {
            val counts = mutableMapOf<String, Int>()
            val engine = Keyboard18Engine(corpus, { counts[it] ?: 0 }, { counts[it] = (counts[it] ?: 0) + 1 })
            repeat(2) {
                engine.inputText("ni")
                assertEquals("你", engine.select(engine.candidates.indexOfFirst { it.text == "你" }))
                engine.clear()
            }
            engine.inputText("nihao")
            assertEquals("你好", engine.candidates.first().text)
            engine.clear()
            engine.inputText("ni")
            engine.select(engine.candidates.indexOfFirst { it.text == "你" })
            engine.clear()
            engine.inputText("nihao")
            // A full-length word still leads the solidified one-character prefix.
            assertEquals("你好", engine.candidates.first().text)
            assertEquals("你好", engine.input(' ').commit)
            assertTrue(engine.raw.isEmpty())
        }
    }

    @Test fun `three selections promote a lower frequency complete homophone`() {
        val counts = mutableMapOf<String, Int>()
        val engine = Keyboard18Engine(testDictionary("你\tni\t100000\n妮\tni\t100\n"),
            { counts[it] ?: 0 }, { counts[it] = (counts[it] ?: 0) + 1 })
        engine.inputText("ni")
        assertEquals("你", engine.candidates.first().text)
        repeat(3) {
            engine.select(engine.candidates.indexOfFirst { it.text == "妮" })
            engine.inputText("ni")
        }
        // Every pick adds weight, so even the third read must keep the picked homophone on top.
        assertEquals("妮", engine.candidates.first().text)
        assertEquals(listOf("ni"), engine.pinyinVariants())
    }

    @Test fun `english word is offered in chinese mode`() {
        val engine = Keyboard18Engine(testDictionary("ok\tok\t5000\n"))
        engine.inputText(grouped("io", "jk"))
        assertTrue(engine.candidates.any { it.text == "ok" })
        assertEquals("ok", engine.select(engine.candidates.indexOfFirst { it.text == "ok" }))
    }

    @Test fun `mixed chinese english term is offered`() {
        val engine = Keyboard18Engine(testDictionary("qq飞车\tqq fei che\t8000\n"))
        engine.inputText(grouped("q", "q", "fg", "we", "io", "xc", "h", "we"))
        assertEquals("qq飞车", engine.candidates.first().text)
        assertEquals("qq飞车", engine.select(0))
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `complete sentence outranks a higher scoring prefix word`() {
        val engine = Keyboard18Engine(testDictionary("你\tni\t1000000\n你哈\tni ha\t100\n"))
        engine.inputText(grouped("bn", "io", "h", "a"))
        assertEquals("你哈", engine.candidates.first().text)
        assertEquals("你哈", engine.select(0))
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `shipped corpus offers complete coverage for a real word`() {
        val engine = Keyboard18Engine(mergedDictionary)
        engine.inputText("nihao")
        assertEquals(5, engine.candidates.first().consumed)
        assertEquals("你好", engine.candidates.first().text)
    }

    @Test fun `pinyin key-letter echoes never enter candidates`() {
        val engine = Keyboard18Engine(mergedDictionary)
        for (groups in listOf(
            arrayOf("q", "y"), arrayOf("q", "we"), arrayOf("we", "q"),
            arrayOf("we", "sd"), arrayOf("rt", "io"), arrayOf("a", "m"),
            arrayOf("u", "io"), arrayOf("sd", "m"), arrayOf("m", "sd")
        )) {
            engine.clear()
            engine.inputText(grouped(*groups))
            val echo = groups.joinToString("") { group -> Keyboard18Keys.group(Keyboard18Keys.code(group))!! }
            val bad = engine.candidates.filter { candidate ->
                candidate.text.all { it in 'a'..'z' || it in 'A'..'Z' } &&
                    candidate.text.lowercase() !in setOf("ai", "ok", "qq", "app", "api", "wx", "vx")
            }
            assertTrue("$echo bad=" + bad.joinToString { "${it.text}/${it.spelling}" }, bad.isEmpty())
        }
        engine.clear()
        engine.inputText("ai")
        assertTrue(engine.candidates.any { it.text == "ai" })
    }

    @Test fun `expanded corpus covers modern practical terms`() {
        val engine = Keyboard18Engine(mergedDictionary)
        val terms = mapOf(
            "大模型" to "damoxing", "提示词" to "tishici", "知识图谱" to "zhishitupu",
            "微服务" to "weifuwu", "直播带货" to "zhibodaihuo", "跨境电商" to "kuajingdianshang",
            "核酸检测" to "hesuanjiance", "扫地机器人" to "saodijiqiren", "螺蛳粉" to "luosifen"
        )
        for ((word, code) in terms) {
            engine.clear()
            engine.inputText(code)
            assertTrue("$word / $code", engine.candidates.any { it.text == word })
        }
    }

    @Test fun `shipped corpus contains the english and mixed entries`() {
        assertTrue(mergedDictionary.entries.any { it.text == "ok" && it.literal })
        assertTrue(mergedDictionary.entries.any { it.text == "qq飞车" })
    }

    @Test fun `prefix completion does not outrank the exact word`() {
        val engine = Keyboard18Engine(testDictionary("你好\tni hao\t100000\n你好吗\tni hao ma\t100000\n"))
        engine.inputText("nihao")
        assertEquals("你好", engine.candidates.first().text)
    }

    @Test fun `literal english entries survive double pinyin encoding`() {
        val rows = "ok\tok\t5000\nfei\tfei\t1000\n"
        val bytes = ByteArrayOutputStream().apply {
            java.util.zip.GZIPOutputStream(this).bufferedWriter().use { it.write(rows) }
        }.toByteArray()
        val encoded = Keyboard18Dictionary.read(bytes.inputStream(), ShuangpinScheme.XIAOHE)
        assertTrue(encoded.entries.any { it.text == "ok" && it.inputSpelling == "ok" })
    }

    @Test fun `merged corpus loads under double pinyin with english kept`() {
        val merged = mergedDictionary(ShuangpinScheme.XIAOHE)
        assertTrue(merged.entries.any { it.text == "ok" && it.inputSpelling == "ok" })
        assertTrue(merged.entries.any { it.text == "qq飞车" })
    }

    @Test fun `three repeated word by word compositions become one candidate`() {
        val learned = HashMap<String, List<Keyboard18CompositionStore.Learned>>()
        var recorded = 0
        val engine = Keyboard18Engine(testDictionary("你\tni\t1000000\n好\thao\t1000000\n"),
            compositionCandidates = { learned[it].orEmpty() },
            recordComposition = { key, parts, spellings ->
                recorded++
                learned[key] = listOf(Keyboard18CompositionStore.Learned(parts.joinToString(""), spellings.joinToString(" ")))
            })
        val input = grouped("bn", "io", "h", "a", "io")
        repeat(3) {
            engine.inputText(input)
            engine.select(engine.candidates.indexOfFirst { it.text == "你" })
            engine.select(engine.candidates.indexOfFirst { it.text == "好" })
        }
        assertEquals(3, recorded)
        assertEquals(1, learned.size)
        engine.inputText(input)
        assertEquals("你好", engine.candidates.first().text)
        assertEquals("你好", engine.select(0))
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `single candidate selection is not recorded as a composition`() {
        var recorded = 0
        val engine = Keyboard18Engine(testDictionary("你好\tni hao\t1000000\n"),
            recordComposition = { _, _, _ -> recorded++ })
        engine.inputText(grouped("bn", "io", "h", "a", "io"))
        engine.select(engine.candidates.indexOfFirst { it.text == "你好" })
        assertEquals(0, recorded)
    }

    @Test fun `learned composition appears for the same grouped taps`() {
        val engine = Keyboard18Engine(mergedDictionary,
            compositionCandidates = { key ->
                if (key == Keyboard18Engine.compositionKey(null, grouped("u", "io")))
                    listOf(Keyboard18CompositionStore.Learned("精实", "jing shi")) else emptyList()
            })
        engine.inputText(grouped("u", "io"))
        assertEquals("精实", engine.candidates.first().text)
        assertEquals("精实", engine.select(0))
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `single grouped syllables keep the most frequent reading first`() {
        val engine = Keyboard18Engine(mergedDictionary)
        // The first letter of a double key must not outrank the more common second letter.
        engine.inputText(grouped("bn", "io"))
        assertEquals("你", engine.candidates.first().text)
        engine.clear()
        engine.inputText(grouped("sd", "a"))
        assertTrue(engine.candidates.first().text in setOf("大", "打"))
        engine.clear()
        engine.inputText(grouped("bn", "a"))
        assertTrue(engine.candidates.take(5).any { it.text in setOf("那", "拿", "哪") })
    }

    @Test fun `composition key covers only the taps actually picked after a backspace`() {
        val keys = ArrayList<String>()
        val engine = Keyboard18Engine(testDictionary("你\tni\t1000000\n好\thao\t1000000\n哈\tha\t1000000\n"),
            recordComposition = { key, parts, _ -> keys += key + "=" + parts.joinToString("") })
        engine.inputText("nihao")
        engine.select(engine.candidates.indexOfFirst { it.text == "你" })
        engine.backspace()
        engine.select(engine.candidates.indexOfFirst { it.text == "哈" })
        assertEquals(listOf(Keyboard18Engine.compositionKey(null, "niha") + "=你哈"), keys)
    }

    @Test fun `composition keys differ between full pinyin and shuangpin`() {
        val taps = grouped("bn", "io", "h", "xc")
        assertNotEquals(Keyboard18Engine.compositionKey(null, taps),
            Keyboard18Engine.compositionKey(ShuangpinScheme.XIAOHE, taps))
    }

    @Test fun `mixed entries encode their pinyin syllables under shuangpin`() {
        val rows = "qq飞车\tqq fei che\t8000\nok\tok\t5000\n"
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).bufferedWriter().use { it.write(rows) }
        }.toByteArray()
        val scheme = ShuangpinScheme.XIAOHE
        val encoded = Keyboard18Dictionary.read(bytes.inputStream(), scheme)
        val expected = "qq " + scheme.encodeSyllable("fei") + " " + scheme.encodeSyllable("che")
        assertEquals(expected, encoded.entries.single { it.text == "qq飞车" }.inputSpelling)
        assertEquals("ok", encoded.entries.single { it.text == "ok" }.inputSpelling)
    }

    @Test fun `plain lowercase english rows are not shipped because they never surface`() {
        assertFalse(mergedDictionary.entries.any { it.literal && it.text.all { c -> c in 'a'..'z' } &&
            it.text !in setOf("ai", "ok", "qq", "app", "api", "wx", "vx") })
        assertTrue(mergedDictionary.entries.any { it.text == "iPhone" })
    }

    private fun testDictionary(rows: String) = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).bufferedWriter().use { it.write(rows) }
    }.toByteArray().inputStream().use { Keyboard18Dictionary.read(it) }

    companion object {
    private fun mergedDictionary(scheme: ShuangpinScheme? = null): Keyboard18Dictionary {
        val weights = LinkedHashMap<String, Int>()
        listOf("pinyin.table", "extra.table", "english.table").forEach { asset ->
            val file = listOf(File("src/main/assets/keyboard18/$asset"),
                File("app/src/main/assets/keyboard18/$asset")).first { it.isFile }
            file.inputStream().use { java.util.zip.GZIPInputStream(it).bufferedReader().forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size == 3) {
                    val key = parts[0] + "\t" + parts[1]
                    weights[key] = maxOf(weights[key] ?: -1, parts[2].toIntOrNull() ?: 0)
                }
            } }
        }
        val gzip = ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).bufferedWriter().use { writer ->
            weights.forEach { (key, weight) -> writer.write(key + "\t" + weight + "\n") }
        } }.toByteArray()
        return Keyboard18Dictionary.read(gzip.inputStream(), scheme)
    }
        private val mergedDictionary by lazy { mergedDictionary() }
        private val dictionary by lazy {
            val asset = listOf(File("src/main/assets/keyboard18/pinyin.table"),
                File("app/src/main/assets/keyboard18/pinyin.table")).first { it.isFile }
            asset.inputStream().use(Keyboard18Dictionary::read)
        }
    }
}
