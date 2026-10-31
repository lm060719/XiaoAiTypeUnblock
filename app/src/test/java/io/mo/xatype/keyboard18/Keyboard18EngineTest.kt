package io.mo.xatype.keyboard18

import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
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

    @Test fun `learning a prefix three times does not displace a complete word`() {
        for (corpus in listOf(dictionary, testDictionary("你\tni\t1000\n你好\tni hao\t100000\n好\thao\t1000\n"))) {
            val counts = mutableMapOf<String, Int>()
            val engine = Keyboard18Engine(corpus, { counts[it] ?: 0 }, { counts[it] = (counts[it] ?: 0) + 1 })
            repeat(3) {
                engine.inputText("ni")
                assertEquals("你", engine.select(engine.candidates.indexOfFirst { it.text == "你" }))
                engine.clear()
            }
            engine.inputText("nihao")
            assertEquals("你好", engine.candidates.first().text)
            assertEquals("ni hao", engine.display)
            assertEquals("你好", engine.input(' ').commit)
            assertTrue(engine.raw.isEmpty())
        }
    }

    @Test fun `three selections promote a lower frequency complete homophone`() {
        val counts = mutableMapOf<String, Int>()
        val engine = Keyboard18Engine(testDictionary("你\tni\t100000\n妮\tni\t100\n"),
            { counts[it] ?: 0 }, { counts[it] = (counts[it] ?: 0) + 1 })
        repeat(3) {
            engine.inputText("ni")
            assertEquals("你", engine.candidates.first().text)
            assertEquals("妮", engine.select(engine.candidates.indexOfFirst { it.text == "妮" }))
        }
        engine.inputText("ni")
        assertEquals("妮", engine.candidates.first().text)
        assertEquals(listOf("ni"), engine.pinyinVariants())
    }

    private fun testDictionary(rows: String) = ByteArrayOutputStream().apply {
        GZIPOutputStream(this).bufferedWriter().use { it.write(rows) }
    }.toByteArray().inputStream().use { Keyboard18Dictionary.read(it) }

    companion object {
        private val dictionary by lazy {
            val asset = listOf(File("src/main/assets/keyboard18/pinyin.table"),
                File("app/src/main/assets/keyboard18/pinyin.table")).first { it.isFile }
            asset.inputStream().use(Keyboard18Dictionary::read)
        }
    }
}
