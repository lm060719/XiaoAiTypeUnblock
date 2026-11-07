package io.mo.xatype.keyboard18

import io.mo.xatype.keyboard.ShuangpinScheme
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 14-key reuses the 18-key decoder with QWERTY letters paired two to a key. */
class Keyboard14EngineTest {
    private fun taps(pinyin: String) = pinyin.map { letter ->
        if (letter == '\'') letter else Keyboard14Keys.code(Keyboard14Keys.groups.first { letter in it }).toChar()
    }.joinToString("")
    private fun engine() = Keyboard18Engine(dictionary)

    @Test fun `layout pairs every letter exactly once in five five four rows`() {
        assertEquals(listOf(5, 5, 4), Keyboard14Keys.rows.map { it.size })
        assertEquals(('a'..'z').toList(), Keyboard14Keys.groups.joinToString("").toList().sorted())
        assertEquals(14, Keyboard14Keys.hints.size)
        // Private codes never collide with the 18-key range.
        Keyboard14Keys.groups.forEach { group ->
            assertNull(Keyboard18Keys.group(Keyboard14Keys.code(group)))
            assertSame(Keyboard14Keys, GroupedKeys.of(Keyboard14Keys.code(group)))
        }
        Keyboard18Keys.groups.forEach { assertSame(Keyboard18Keys, GroupedKeys.of(Keyboard18Keys.code(it))) }
    }

    @Test fun `why is offered for grouped initials`() {
        val engine = engine()
        engine.inputText(taps("wsm"))
        assertEquals("为什么", engine.candidates.first().text)
        assertEquals("为什么", engine.input(' ').commit)
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `common full pinyin words reach the first candidates`() {
        // Pairing makes some readings collide (nihao / buhao share BN UI GH AS OP); the word stays near the top.
        for ((pinyin, word) in listOf("nihao" to "你好", "women" to "我们", "shenme" to "什么", "zhongguo" to "中国")) {
            val engine = engine()
            engine.inputText(taps(pinyin))
            assertTrue("$pinyin: ${engine.candidates.take(3).map { it.text }}", engine.candidates.take(3).any { it.text == word })
        }
    }

    @Test fun `separator splits ambiguous syllables`() {
        val engine = engine()
        engine.inputText(taps("xi'an"))
        assertEquals("西安", engine.candidates.first().text)
        assertEquals("xi an", engine.candidates.first().spelling)
    }

    @Test fun `exact letters from long press constrain the group`() {
        val engine = engine()
        // "ta" typed with an exact t: the TY group must not offer "ya" readings.
        engine.inputText("t" + taps("a"))
        assertTrue(engine.candidates.filter { it.consumed == engine.raw.length }.all { it.spelling.startsWith("t") })
        engine.backspace(); engine.backspace()
        assertTrue(engine.raw.isEmpty())
    }

    @Test fun `learned compositions are stored apart from 18 key taps`() {
        val taps18 = Keyboard18Keys.code("u").toChar().toString()
        val taps14 = Keyboard14Keys.code("ui").toChar().toString()
        assertNotEquals(Keyboard18Engine.compositionKey(null, taps18, Keyboard18Keys),
            Keyboard18Engine.compositionKey(null, taps14, Keyboard14Keys))
        // The 18-key key format is unchanged, so existing learning survives the upgrade.
        assertEquals(Keyboard18Engine.compositionKey(null, taps18), Keyboard18Engine.compositionKey(null, taps18, Keyboard18Keys))
        assertTrue(Keyboard18Engine.compositionKey(null, taps14, Keyboard14Keys).startsWith("K14:QUANPIN:"))
    }

    @Test fun `double pinyin decodes through paired keys`() {
        val scheme = ShuangpinScheme.XIAOHE
        val dictionary = File(asset).inputStream().use { Keyboard18Dictionary.read(it, scheme, Keyboard14Keys) }
        val engine = Keyboard18Engine(dictionary)
        // 小鹤: ni hao -> ni hc
        engine.inputText(taps("nihc"))
        assertTrue(engine.candidates.take(3).any { it.text == "你好" })
    }

    companion object {
        private val asset = listOf("src/main/assets/keyboard18/pinyin.table", "app/src/main/assets/keyboard18/pinyin.table")
            .first { File(it).isFile }
        private val dictionary by lazy { File(asset).inputStream().use { Keyboard18Dictionary.read(it, null, Keyboard14Keys) } }
    }
}
