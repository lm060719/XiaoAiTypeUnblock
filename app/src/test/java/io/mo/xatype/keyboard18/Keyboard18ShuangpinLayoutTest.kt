package io.mo.xatype.keyboard18

import io.mo.xatype.compat.KeyboardAbi
import ka.c
import ka.e
import ka.f
import ka.g
import org.junit.Assert.*
import org.junit.Test

class Keyboard18ShuangpinLayoutTest {
    private val layouts = Keyboard18HostLayout(javaClass.classLoader!!, KeyboardAbi.reference(javaClass.classLoader!!))
    private fun native(): g {
        fun key(code: Int, kind: f = f.CHAR) = c(code, code.toChar().toString(), 1f, null, 48f, kind,
            null, null, null, null, null, false, null, false, false, null, false, null)
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { letters ->
            e(1f, letters.map { key(it.code) } + if (letters.startsWith("z"))
                listOf(key(-1, f.SHIFT), key(-2, f.BACKSPACE)) else emptyList(), null)
        } + e(1f, listOf(key(32, f.SPACE)), null)
        return g("qwerty", "拼音26键", rows, listOf("PINYIN"), null, 4f, true, 12f, true)
    }

    @Test fun `semicolon is available only on Chinese L popup for schemes that require it`() {
        for (english in listOf(false, true)) for (semicolon in listOf(false, true)) {
            val original = native()
            val rebuilt = layouts.rebuild(original, english, semicolon) as g
            if (english) {
                assertSame(original, rebuilt)
                continue
            }
            val keys = rebuilt.c.flatMap { it.a }
            val l = keys.single { layouts.groupOf(it) == "l" }
            assertEquals(semicolon, ";" in l.i.orEmpty())
            assertTrue(keys.filter { it !== l }.none { ";" in it.i.orEmpty() })
            assertSame(original.c.last(), rebuilt.c.last())
            assertTrue(l.i.orEmpty().contains("l"))
        }
    }
}
