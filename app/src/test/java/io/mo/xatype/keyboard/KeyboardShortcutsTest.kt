package io.mo.xatype.keyboard

import io.mo.xatype.keyboard18.Keyboard18HostLayout
import io.mo.xatype.keyboard.KeyboardShortcutLayout.*
import org.junit.Assert.*
import org.junit.Test

class KeyboardShortcutsTest {
    private val loader = javaClass.classLoader!!
    private val abi = io.mo.xatype.compat.KeyboardAbi.reference(loader)
    private val adapter = KeyboardShortcutHostLayout(loader, abi)
    private fun key(code: Int, label: String, popup: List<String>? = null) = ka.c(code,
        label, 1f, 36f, 48f, ka.f.CHAR, "-", "-", popup, 1, 5, false, true, false, false, 7, false, 9)
    private fun layout(english: Boolean, shift: Boolean, split: Boolean): ka.g {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").mapIndexed { index, letters ->
            val keys = letters.map { letter -> key((if (shift) letter.uppercaseChar() else letter).code,
                (if (!english || shift) letter.uppercaseChar() else letter).toString(),
                listOf(letter.uppercaseChar().toString(), "-", letter.toString())) }.toMutableList()
            if (index == 2) { keys.add(0, ka.c(-1, ka.f.SHIFT)); keys.add(ka.c(-2, ka.f.BACKSPACE)) }
            if (split) ka.e(1f, keys.take(keys.size / 2), keys.drop(keys.size / 2)) else ka.e(1f, keys, null)
        } + ka.e(1f, listOf(ka.c(32, ka.f.SPACE)), null)
        return ka.g("qwerty", "拼音26键", rows, listOf("PINYIN", "ENGLISH"), 8f, 4f, true, 12f, true)
    }
    private fun keys(layout: ka.g) = layout.c.flatMap { it.a + it.c.orEmpty() }

    @Test fun `native Chinese English shift and split keys all retain independent display casing`() {
        for (english in listOf(false, true)) for (shift in listOf(false, true)) for (split in listOf(false, true)) {
            val original = layout(english, shift, split)
            assertEquals(5, keys(original).mapNotNull { adapter.action(it, QWERTY) }.size)
            val copy = keys(original).single { it.a == (if (shift) 'C' else 'c').code }
            assertEquals("复制", adapter.action(copy, QWERTY))
            assertEquals(if (!english || shift) "C" else "c", copy.b)
            assertEquals(listOf("C", "-", "c"), copy.i)
        }
    }

    @Test fun `release action copies native properties without changing the visible key or menu`() {
        val original = key('c'.code, "C", listOf("C", "-", "c"))
        val action = adapter.actionKey(original, "复制") as ka.c
        assertEquals('c'.code, original.a)
        assertEquals("C", original.b)
        assertEquals(listOf("C", "-", "c"), original.i)
        assertEquals('复'.code, action.a)
        assertEquals("复制", action.b)
        assertEquals(listOf("复制"), action.i)
        for (name in 'a'..'r') if (name !in listOf('a', 'b', 'i')) {
            val field = ka.c::class.java.getDeclaredField(name.toString())
            assertEquals("field $name", field.get(original), field.get(action))
        }
    }

    @Test fun `18 key Chinese shortcuts dispatch while popups keep letters and hints only`() {
        val builder = Keyboard18HostLayout(loader, abi)
        for (shift in listOf(false, true)) {
            val keyboard = builder.rebuild(layout(false, shift, false), english = false) as ka.g
            assertEquals(5, keys(keyboard).mapNotNull { adapter.action(it, KEYBOARD18) }.size)
            assertFalse(keys(keyboard).any { it.i.orEmpty().any(KeyboardShortcuts.actions::contains) })
            val copy = keys(keyboard).single { adapter.action(it, KEYBOARD18) == "复制" }
            assertTrue(copy.i.orEmpty().containsAll(if (shift) listOf("X", "C", "7") else listOf("x", "c", "7")))
            assertNull(adapter.action(copy, QWERTY))
            keys(keyboard).forEach { source ->
                adapter.action(source, KEYBOARD18)?.let { action ->
                    assertEquals(action, (adapter.actionKey(source, action) as ka.c).b)
                }
            }
        }
    }

    @Test fun `18 key switches to native English layout and all five shortcuts survive shift and split`() {
        val builder = Keyboard18HostLayout(loader, abi)
        val expected = mapOf('z' to "全选", 'c' to "复制", 'v' to "粘贴", 'b' to "剪切", 'm' to "段尾")
        for (shift in listOf(false, true)) for (split in listOf(false, true)) {
            val original = layout(true, shift, split)
            assertSame(original, builder.rebuild(original, english = true))
            val mode = KeyboardShortcutLayout.fromHost("ENGLISH", "qwerty", keyboard18 = true)!!
            val actual = keys(original).mapNotNull { source ->
                adapter.action(source, mode)?.let { action ->
                    assertEquals(action, (adapter.actionKey(source, action) as ka.c).b)
                    source.a.toChar().lowercaseChar() to action
                }
            }.toMap()
            assertEquals(expected, actual)
        }
    }

    @Test fun `every supported action including select all and paragraph start can be dispatched`() {
        assertEquals(setOf("全选", "复制", "粘贴", "剪切", "段首", "段尾"), KeyboardShortcuts.actions)
        for (action in KeyboardShortcuts.actions) {
            assertEquals(action, (adapter.actionKey(key('z'.code, "Z"), action) as ka.c).b)
        }
    }

    @Test fun `unrelated labels symbols controls and non shortcut letters are not gestures`() {
        assertNull(adapter.action(key('a'.code, "A"), QWERTY))
        assertNull(adapter.action(key('c'.code, "XC"), QWERTY))
        assertNull(adapter.action(key('c'.code, "复制"), QWERTY))
        assertNull(adapter.action(key('1'.code, "1"), QWERTY))
        assertNull(adapter.action(ka.c(-2, ka.f.BACKSPACE), QWERTY))
    }

    @Test fun `T9 maps five physical letter groups without binding adjacent number keys`() {
        val expected = mapOf('4' to "剪切", '6' to "段尾", '7' to "全选", '8' to "复制", '9' to "粘贴")
        for (split in listOf(false, true)) for (multiline in listOf(false, true)) {
            val letters = listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
                .mapIndexed { index, group ->
                    key('2'.code + index, if (multiline) group.lowercase().toList().joinToString("\n") else group,
                        group.map(Char::toString) + ('2'.code + index).toChar().toString() + group.lowercase().map(Char::toString))
                }
            val rows = letters.chunked(3).map { row ->
                if (split) ka.e(1f, row.take(1), row.drop(1)) else ka.e(1f, row, null)
            } + ka.e(1f, ('0'..'9').map { key(it.code, it.toString()) }, null)
            val keyboard = ka.g("t9", "九键", rows, listOf("T9"), 8f, 4f, true, 12f, true)
            val shortcuts = keys(keyboard).mapNotNull { source ->
                adapter.action(source, T9)?.let { source.a.toChar() to it }
            }
            assertEquals(5, shortcuts.size)
            assertEquals(expected, shortcuts.toMap())
            keys(keyboard).forEach { source -> adapter.action(source, T9)?.let { action ->
                assertEquals(action, (adapter.actionKey(source, action) as ka.c).b)
            } }
            assertTrue(keys(keyboard).none { adapter.action(it, QWERTY) != null || adapter.action(it, KEYBOARD18) != null })
        }
    }

    @Test fun `T9 requires matching digit code and complete letter group`() {
        for ((code, label) in listOf('8' to "8", '7' to "TUV", '8' to "TV", '8' to "TUV8",
                'v' to "TUV", '2' to "ABC", '3' to "DEF", '5' to "JKL", '0' to "WXYZ")) {
            assertNull("$code / $label", adapter.action(key(code.code, label), T9))
        }
        assertNull(adapter.action(ka.c('8'.code, ka.f.SWITCH), T9))
        assertNull(adapter.action(key('c'.code, "C"), T9))
    }

    @Test fun `T9 release preserves the original digit hints popup menu and key geometry`() {
        val original = ka.c('8'.code, "TUV", 1f, 81.45f, 50f, ka.f.CHAR, "8", null,
            listOf("T", "U", "V", "8", "t", "u", "v"), 3, null, false, null, false, false, null, false, null)
        val action = adapter.actionKey(original, adapter.action(original, T9)!!) as ka.c
        assertEquals('8'.code, original.a)
        assertEquals("TUV", original.b)
        assertEquals(listOf("T", "U", "V", "8", "t", "u", "v"), original.i)
        assertEquals("复制", action.b)
        assertEquals(listOf("复制"), action.i)
        for (name in 'a'..'r') if (name !in listOf('a', 'b', 'i')) {
            val field = ka.c::class.java.getDeclaredField(name.toString())
            assertEquals("field $name", field.get(original), field.get(action))
        }
    }

    @Test fun `host layout gating accepts T9 and rejects number panels and unrelated engines`() {
        assertEquals(T9, KeyboardShortcutLayout.fromHost("T9", "t9", false))
        assertEquals(QWERTY, KeyboardShortcutLayout.fromHost("PINYIN", "qwerty", false))
        assertEquals(QWERTY, KeyboardShortcutLayout.fromHost("ENGLISH", "qwerty", false))
        assertEquals(KEYBOARD18, KeyboardShortcutLayout.fromHost("PINYIN", "qwerty", true))
        assertEquals(QWERTY, KeyboardShortcutLayout.fromHost("ENGLISH", "qwerty", true))
        for (mode in listOf("T9", "PINYIN", "ENGLISH", "SHUANGPIN", "STROKE", "HANDWRITING", "VOICE", null)) {
            for (layoutId in listOf("number", "stroke", "handwriting", "", null)) {
                assertNull(KeyboardShortcutLayout.fromHost(mode, layoutId, false))
                assertNull(KeyboardShortcutLayout.fromHost(mode, layoutId, true))
            }
        }
        assertNull(KeyboardShortcutLayout.fromHost("T9", "qwerty", false))
        assertNull(KeyboardShortcutLayout.fromHost("PINYIN", "t9", false))
        assertNull(KeyboardShortcutLayout.fromHost("T9", "t9", true))
        assertNull(KeyboardShortcutLayout.fromHost("SHUANGPIN", "qwerty", false))
    }

    @Test(expected = IllegalArgumentException::class) fun `release cannot synthesize an arbitrary action`() {
        adapter.actionKey(key('c'.code, "C"), "任意文字")
    }

    @Test fun `paragraph navigation handles boundaries offsets and empty text`() {
        val text = "第一段\n第二段\n"
        assertEquals(0, KeyboardShortcuts.paragraphPosition(text, 0, 0, false))
        assertEquals(0, KeyboardShortcuts.paragraphPosition(text, 3, 0, false))
        assertEquals(4, KeyboardShortcuts.paragraphPosition(text, 4, 0, false))
        assertEquals(7, KeyboardShortcuts.paragraphPosition(text, 4, 0, true))
        assertEquals(8, KeyboardShortcuts.paragraphPosition(text, text.length, 0, true))
        assertEquals(108, KeyboardShortcuts.paragraphPosition(text, 99, 100, false))
        assertEquals(12, KeyboardShortcuts.paragraphPosition("", 0, 12, true))
        assertEquals(0, KeyboardShortcuts.paragraphPosition(text, -2, 0, false))
    }
}
