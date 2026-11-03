package io.mo.xatype.keyboard18

import io.mo.xatype.keyboard.ShuangpinScheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class Keyboard18ShuangpinTest {
    private fun dictionary(scheme: ShuangpinScheme?) = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).bufferedWriter().use { it.write(
            "你好\tni hao\t100000\n为什么\twei shen me\t200000\n中国\tzhong guo\t100000\n" +
                "中\tzhong\t10000\n国\tguo\t10000\n应\tying\t10000\n爱\tai\t10000\n嗯\ten\t10000\n") }
    }.toByteArray().inputStream().use { Keyboard18Dictionary.read(it, scheme) }
    private fun grouped(code: String) = code.map { letter ->
        Keyboard18Keys.code(if (letter == ';') "l" else Keyboard18Keys.groups.first { letter in it }).toChar()
    }.joinToString("")

    @Test fun `encoder matches independently generated upstream vectors for every corpus syllable`() {
        val source = checkNotNull(javaClass.getResourceAsStream("/shuangpin-reference.tsv"))
        val differences = mutableListOf<String>()
        source.bufferedReader().useLines { lines -> lines.forEach { line ->
            val (id, syllable, expected) = line.split('\t')
            val actual = ShuangpinScheme.valueOf(id).encodeSyllable(syllable)
            if ((expected == "-" && actual != null) || (expected != "-" && actual !in expected.split(',')))
                differences.add("$id / $syllable -> $actual, expected $expected")
        } }
        assertTrue(differences.joinToString("\n"), differences.isEmpty())
    }

    @Test fun `seven schemes decode why with exact codes and ambiguous 18-key taps`() {
        val codes = mapOf(ShuangpinScheme.XIAOHE to "wwufme", ShuangpinScheme.ZIRANMA to "wzufme",
            ShuangpinScheme.WEIRUAN to "wzufme", ShuangpinScheme.SOUGOU to "wzufme",
            ShuangpinScheme.ZHINENG_ABC to "wqvfme", ShuangpinScheme.PINYIN_JIAJIA to "wwirme",
            ShuangpinScheme.ZIGUANG to "wkiwme")
        for ((scheme, code) in codes) for (input in listOf(code, grouped(code))) {
            val engine = Keyboard18Engine(dictionary(scheme))
            engine.inputText(input)
            assertEquals("$scheme", "为什么", engine.candidates.first().text)
            assertEquals("wei shen me", engine.candidates.first().spelling)
            assertEquals("为什么", engine.input(' ').commit)
            assertTrue(engine.raw.isEmpty())
        }
    }

    @Test fun `zero initials follow scheme conventions and exact letters constrain grouped alternatives`() {
        for (scheme in ShuangpinScheme.entries) {
            val engine = Keyboard18Engine(dictionary(scheme))
            val code = if (scheme in setOf(ShuangpinScheme.XIAOHE, ShuangpinScheme.ZIRANMA)) "ai" else when (scheme) {
                ShuangpinScheme.PINYIN_JIAJIA -> "os"
                ShuangpinScheme.ZIGUANG -> "op"
                else -> "ol"
            }
            engine.inputText(code)
            assertTrue("$scheme / $code", engine.candidates.any { it.text == "爱" })
        }
        val engine = Keyboard18Engine(dictionary(ShuangpinScheme.XIAOHE))
        engine.inputText("wsm")
        assertFalse(engine.candidates.any { it.text == "为什么" })
    }

    @Test fun `two syllable shuangpin prefers the complete word over a learned prefix`() {
        val engine = Keyboard18Engine(dictionary(ShuangpinScheme.XIAOHE))
        repeat(3) {
            engine.inputText("ni")
            engine.select(engine.candidates.indexOfFirst { it.text == "你" })
            engine.clear()
        }
        engine.inputText("nihc")
        assertEquals("你好", engine.candidates.first().text)
    }

    @Test fun `partial selection keeps the suffix and supports deletion restore and delimiters`() {
        val engine = Keyboard18Engine(dictionary(ShuangpinScheme.XIAOHE))
        engine.inputText("vsgo")
        val prefix = engine.candidates.indexOfFirst { it.text == "中" && it.consumed == 2 }
        assertTrue(prefix >= 0)
        assertEquals("中", engine.select(prefix))
        assertEquals("go", engine.raw)
        assertEquals("国", engine.commitAll())
        engine.restore(grouped("nihc"))
        engine.backspace()
        assertEquals(3, engine.raw.length)
        engine.input(grouped("c").single())
        assertEquals("你好", engine.commitAll())
        engine.inputText("ni'hc")
        assertEquals("你好", engine.commitAll())
        engine.inputText("n'ihc")
        assertFalse(engine.candidates.any { it.text == "你好" })
    }

    @Test fun `semicolon finals can be tapped on L or selected exactly without changing full pinyin punctuation`() {
        for (scheme in listOf(ShuangpinScheme.WEIRUAN, ShuangpinScheme.SOUGOU, ShuangpinScheme.ZIGUANG)) {
            val engine = Keyboard18Engine(dictionary(scheme))
            engine.inputText(grouped("y;"))
            assertTrue(engine.candidates.any { it.text == "应" })
            engine.clear()
            assertTrue(engine.restore("y;").consumed)
            assertEquals("应", engine.commitAll())
            engine.inputText("yl")
            assertFalse(engine.candidates.any { it.text == "应" })
        }
        val full = Keyboard18Engine(dictionary(null))
        assertFalse(full.restore("y;").consumed)
        full.inputText("nihao")
        assertEquals("你好;", full.input(';').commit)
    }

    @Test fun `all seven modes decode common phrases using the shipped dictionary`() {
        val asset = listOf(File("src/main/assets/keyboard18/pinyin.table"),
            File("app/src/main/assets/keyboard18/pinyin.table")).first { it.isFile }
        for (scheme in ShuangpinScheme.entries) {
            val corpus = asset.inputStream().use { Keyboard18Dictionary.read(it, scheme) }
            assertTrue(corpus.entries.size > 64_000)
            val engine = Keyboard18Engine(corpus)
            for ((text, pinyin) in listOf("你好" to "ni hao", "为什么" to "wei shen me", "中国" to "zhong guo")) {
                val code = pinyin.split(' ').joinToString("") { checkNotNull(scheme.encodeSyllable(it)) }
                engine.inputText(grouped(code))
                assertTrue("$scheme / $text", engine.candidates.any { it.text == text })
                assertEquals(text, engine.select(engine.candidates.indexOfFirst { it.text == text }))
                assertTrue(engine.raw.isEmpty())
            }
        }
    }
}
