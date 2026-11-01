package io.mo.xatype.keyboard18

import io.mo.xatype.compat.KeyboardAbi
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class Keyboard18HostBridgeTest {
    private val loader = javaClass.classLoader!!
    private val bridge = Keyboard18HostBridge(loader, KeyboardAbi.reference(loader))
    private fun grouped(vararg groups: String) =
        groups.joinToString("") { Keyboard18Keys.code(it).toChar().toString() }
    private fun engine(): Keyboard18Engine {
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).bufferedWriter().use {
                it.write("而\ter\t1000\n你\tni\t1000\n好\thao\t1000\n你好\tni hao\t10000\n")
            }
        }.toByteArray()
        return Keyboard18Engine(bytes.inputStream().use { Keyboard18Dictionary.read(it) })
    }

    private fun editorText(engine: Keyboard18Engine): String {
        val state = bridge.state(engine) as z8.s
        // Mirrors the host's search-editor path: Composing.rawInput, minus separators.
        assertEquals(engine.display, state.display)
        assertEquals(state.display, state.raw)
        assertFalse(state.raw.any { it in '\uE000'..'\uF8FF' })
        return state.raw.replace("'", "")
    }

    @Test fun `search preedit displays pinyin while decoder retains ambiguous taps`() {
        val engine = engine()
        val taps = grouped("we", "rt", "y")
        val input = engine.inputText(taps)
        assertEquals("ery", editorText(engine))
        val result = bridge.result(input) as z8.r
        assertEquals("ery", result.code)
        assertEquals(taps, result.acceptedRaw)
        assertEquals(taps, engine.raw)
        assertEquals("而", result.candidates.first().text)

        engine.backspace()
        assertEquals("er", editorText(engine))
        engine.clear()
        assertSame(z8.t, bridge.state(engine))
        engine.restore(result.acceptedRaw!!)
        assertEquals("ery", editorText(engine))
        assertEquals(taps, engine.raw)
    }

    @Test fun `partial candidate selection updates preedit and final commit clears it`() {
        val engine = engine()
        val taps = grouped("bn", "io", "h", "a", "io")
        engine.inputText(taps)
        assertEquals("ni hao", editorText(engine))
        assertEquals("你", engine.select(engine.candidates.indexOfFirst { it.text == "你" }))
        assertEquals(taps.drop(2), engine.raw)
        assertEquals("hao", editorText(engine))
        assertEquals("好", engine.input(' ').commit)
        assertSame(z8.t, bridge.state(engine))
    }

    @Test fun `unmatched groups and exact letters have readable fallback preedit`() {
        val engine = engine()
        val taps = grouped("xc", "q")
        engine.inputText(taps)
        assertTrue(engine.candidates.isEmpty())
        assertEquals("[xc]q", editorText(engine))
        assertEquals("[xc]q", engine.commitAll())
        assertSame(z8.t, bridge.state(engine))
        engine.inputText("xq")
        assertEquals("xq", editorText(engine))
        engine.backspace()
        assertEquals("x", editorText(engine))
        engine.backspace()
        assertSame(z8.t, bridge.state(engine))
    }
}
