package io.mo.xatype.keyboard

import io.mo.xatype.compat.KeyboardAbi
import org.junit.Assert.*
import org.junit.Test
import z8.*

class PersonalDictionaryTest {
    private val bridge = EmojiHostBridge(javaClass.classLoader!!, KeyboardAbi.reference(javaClass.classLoader!!))
    private val dictionary = PersonalDictionary.of(listOf(
        PersonalDictionary.entry("xm", "小米17"), PersonalDictionary.entry("xm", "小米17 Pro"),
        PersonalDictionary.entry("addr", "第一行\n第二行，😀")))
    private fun proxy(native: Native, personal: () -> PersonalDictionary = { dictionary }) =
        bridge.create(native, { null }, { false }, personal = personal) { throw AssertionError(it) } as p

    @Test fun `ordered dictionary round trips mixed phrases and matches only complete raw letters`() {
        val restored = PersonalDictionary.decode(dictionary.encode())
        assertEquals(dictionary.entries, restored.entries)
        assertEquals(listOf("小米17", "小米17 Pro"), restored.lookup("XM"))
        assertEquals(listOf("小米17", "小米17 Pro"), restored.lookup("x'm"))
        assertEquals(listOf("小米17", "小米17 Pro"), restored.lookup("X M"))
        assertEquals(listOf("第一行\n第二行，😀"), restored.lookup("addr"))
        for (raw in listOf("", "x", "xmx", "小米xm", "小米x'm", "x'm'x", "96", "xm1", "x'm1", "x\nm")) {
            assertTrue(raw, restored.lookup(raw).isEmpty())
        }
        assertEquals(PersonalDictionary.Entry("xm", "小米17"), PersonalDictionary.entry(" XM ", " 小米17 "))
    }

    @Test fun `invalid persisted data fails closed and duplicate rows are rejected`() {
        for (stored in listOf("broken", "{}", "[[1,2]]", "[[\"x1\",\"坏词条\"]]", "[[]]")) {
            assertTrue(stored, PersonalDictionary.decode(stored).entries.isEmpty())
        }
        for ((code, phrase) in listOf("" to "短语", "x'm" to "短语", "x1" to "短语",
            "x" to " ", "x" to "bad\u0000text", "x".repeat(33) to "短语", "x" to "文".repeat(201))) {
            assertThrows(IllegalArgumentException::class.java) { PersonalDictionary.entry(code, phrase) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalDictionary.of(listOf(dictionary.entries[0], dictionary.entries[0]))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalDictionary.of((0..PersonalDictionary.MAX_ENTRIES).map {
                PersonalDictionary.entry("xm", "短语$it")
            })
        }
    }

    @Test fun `key snapshots show shortcuts first and both selection paths clear composition once`() {
        val native = Native()
        val p = proxy(native)
        assertEquals(listOf("小米", "小明"), p.i('x'.code).candidates.map { it.text })
        assertEquals(listOf("小米17", "小米17 Pro", "小米", "小明"), p.h('m').candidates.map { it.text })
        assertEquals(listOf("小米17", "小米17 Pro", "小米", "小明"), p.s().map { it.text })
        val chosen = p.n(1, "小米17 Pro")
        assertEquals("小米17 Pro", chosen.first)
        assertTrue(chosen.second)
        assertSame(t, p.f())
        assertEquals(1, native.cleared)
        assertEquals(0, native.selections)
        p.d("xm")
        assertEquals("小米17", p.b(0))
        assertEquals(2, native.cleared)
        assertTrue(p.s().isEmpty())
    }

    @Test fun `host cursor read after displaying candidates preserves tap positions`() {
        val loader = javaClass.classLoader!!
        val abi = KeyboardAbi.reference(loader, mapOf(
            "c:z8.p" to "Lz8/EngineWithRawCursor;",
            "m:z8.p#rawCursor()I" to "Lz8/EngineWithRawCursor;->r()I"
        ))
        val bridge = EmojiHostBridge(loader, abi)
        val personal = PersonalDictionary.of(listOf(PersonalDictionary.entry("xm", "小米15ultra")))
        for (index in listOf(0, 2)) {
            val native = Native().apply { words = listOf("小米", "小明", "小猫") }
            val p = bridge.create(native, { null }, { false }, personal = { personal }) { throw AssertionError(it) }
                as EngineWithRawCursor
            p.i('x'.code)
            val shown = p.i('m'.code).candidates.map { it.text }
            assertEquals(listOf("小米15ultra", "小米", "小明"), shown)
            assertEquals(2, p.r())
            val picked = p.n(index, shown[index])
            assertEquals(shown[index], picked.first)
            assertTrue(picked.second)
            assertEquals(if (index == 0) -1 else 1, native.selected)
        }
    }

    @Test fun `space commits shortcut through character code text and flush without native learning`() {
        for (path in 0..3) {
            val native = Native()
            val p = proxy(native)
            p.o("xm")
            val result = when (path) {
                0 -> p.h(' ')
                1 -> p.i(32)
                2 -> p.o(" ")
                else -> p.d(HostEngine.FLUSH)
            }
            assertEquals("小米17", result.commit)
            assertTrue(result.consumed)
            assertEquals("", result.code)
            assertEquals("", result.acceptedRaw)
            assertEquals(0, result.delete)
            assertTrue(result.candidates.isEmpty())
            assertEquals(1, native.cleared)
            assertEquals(0, native.selections)
            assertEquals(0, native.spaces)
            assertSame(t, p.f())
        }
        // A restored composition can be committed even before the UI asks for candidates.
        val native = Native().apply { raw = "xm" }
        assertEquals("小米17", proxy(native).h(' ').commit)
    }

    @Test fun `native syllable boundaries match shortcuts across input selection and space commits`() {
        val native = Native().apply { splitRaw = true }
        val p = proxy(native)
        p.h('x')
        assertEquals("小米17", p.h('m').candidates.first().text)
        assertEquals("x'm", (p.f() as s).raw)
        assertEquals("小米17 Pro", p.n(1, "小米17 Pro").first)
        assertSame(t, p.f())
        p.o("xm")
        assertEquals("小米17", p.i(32).commit)
        p.d("xm")
        assertEquals("小米", p.h('x').candidates.first().text)
        assertEquals("小米17", p.k().candidates.first().text)
        assertEquals("小米17", p.s().first().text)
        assertEquals(2, native.cleared)
        assertEquals(0, native.selections)
    }

    @Test fun `remaining input backspace stale taps and disabled dictionary preserve native behavior`() {
        val native = Native()
        var enabled = true
        val p = proxy(native) { if (enabled) dictionary else PersonalDictionary.EMPTY }
        p.d("xm")
        assertEquals(listOf("小米", "小明"), p.h('x').candidates.map { it.text })
        assertEquals("小米17", p.k().candidates.first().text)
        assertEquals(listOf("小米", "小明"), p.k().candidates.map { it.text })
        p.d("xm")
        native.raw = "xmx"
        assertFalse(p.n(0, "小米17").second)
        assertFalse(p.n(2, "小米").second)
        assertEquals("xmx", native.raw)
        assertEquals(0, native.cleared)
        assertEquals(0, native.selections)
        enabled = false
        assertEquals(listOf("小米", "小明"), p.d("xm").candidates.map { it.text })
        assertEquals("小米", p.h(' ').commit)
        assertEquals(1, native.selections)
        assertEquals(1, native.spaces)
    }

    @Test fun `pagination and shifted native selection work with duplicate native phrases removed`() {
        val native = Native().apply { words = listOf("小米17", "小米") + (2..29).map { "候选$it" } }
        val p = proxy(native)
        p.d("xm")
        assertEquals(listOf("小米17", "小米17 Pro", "小米"), p.s().map { it.text })
        assertEquals(listOf("小米", "候选2", "候选3", "候选4"), p.m(2, 4).map { it.text })
        assertEquals(listOf("候选4", "候选5"), p.m(5, 2).map { it.text })
        assertEquals("候选5", p.n(6, "候选5").first)
        assertEquals(5, native.selected)
        assertEquals(0, native.cleared)
        assertEquals(1, native.selections)
    }

    @Test fun `page first and empty native candidates still expose selectable shortcuts`() {
        val native = Native().apply { raw = "xm"; words = emptyList() }
        val p = proxy(native)
        assertEquals(listOf("小米17", "小米17 Pro"), p.m(0, 5).map { it.text })
        assertTrue(p.m(2, 5).isEmpty())
        assertEquals("小米17 Pro", p.n(1, "小米17 Pro").first)
        assertEquals(1, native.cleared)
        assertEquals(0, native.selections)
        native.raw = "xm"
        assertEquals("小米17", p.m(0, 1).first().text)
        native.raw = "xmx"
        assertTrue(p.m(0, 5).isEmpty())
    }

    @Test fun `personal shortcuts and emoji share selection and pagination mapping`() {
        val native = Native().apply { words = listOf("猪", "朱") + (2..29).map { "候选$it" } }
        val table = EmojiCandidates.read("猪\tzhu\t🐷".reader())
        val personal = PersonalDictionary.of(listOf(PersonalDictionary.entry("zhu", "小猪17")))
        val p = bridge.create(native, { table }, { true }, personal = { personal }) { throw AssertionError(it) } as p
        assertEquals(listOf("小猪17", "猪", "🐷", "朱"), p.d("zhu").candidates.map { it.text })
        assertEquals(listOf("🐷", "朱", "候选2"), p.m(2, 3).map { it.text })
        assertEquals("朱", p.n(3, "朱").first)
        p.d("zhu")
        assertEquals("🐷", p.b(2))
        p.d("zhu")
        assertEquals("小猪17", p.i(32).commit)
        assertEquals(2, native.cleared)
        assertEquals(1, native.selections)
    }

    @Test fun `double pinyin nine key English and partially selected prefixes cannot trigger shortcuts`() {
        for (mode in listOf(g.SHUANGPIN, g.T9, g.ENGLISH)) {
            val native = Native().apply { this.mode = mode }
            assertEquals(listOf("小米", "小明"), proxy(native).d("xm").candidates.map { it.text })
        }
        val native = Native()
        val p = bridge.create(native, { null }, { false }, scheme = { ShuangpinScheme.XIAOHE },
            personal = { dictionary }) { throw AssertionError(it) } as p
        assertEquals(listOf("小米", "小明"), p.d("xm").candidates.map { it.text })
        for (raw in listOf("小米xm", "小米x'm", "x'm'x", "xm1")) {
            assertEquals(listOf("小米", "小明"), proxy(Native()).d(raw).candidates.map { it.text })
        }
    }

    @Test fun `a personal dictionary failure never retries native input or disables emoji`() {
        val native = Native().apply { words = listOf("猪", "朱") }
        var failures = 0
        val table = EmojiCandidates.read("猪\tzhu\t🐷".reader())
        val p = bridge.create(native, { table }, { true }, personal = { error("Unavailable") },
            personalFailed = { failures++ }) { throw AssertionError(it) } as p
        assertEquals(listOf("猪", "🐷", "朱"), p.d("zhu").candidates.map { it.text })
        assertEquals(listOf("猪", "🐷", "朱"), p.s().map { it.text })
        assertEquals(1, native.restores)
        assertEquals(1, failures)
        assertEquals("朱", p.n(2, "朱").first)
        assertEquals(1, native.selections)
    }

    private class Native : EngineWithRawCursor {
        var raw = ""
        var mode = g.PINYIN
        var words = listOf("小米", "小明") + (2..29).map { "候选$it" }
        var cleared = 0
        var selections = 0
        var selected = -1
        var spaces = 0
        var restores = 0
        var splitRaw = false
        private fun candidate(word: String) = a(word, null, 1, b.SYSTEM, word)
        private fun result(commit: String? = null) = r(true, raw, s(), commit, 0, emptyList(), raw)
        override fun a() = mode
        override fun b(index: Int): String {
            val word = words.getOrNull(index) ?: return ""
            selections++; selected = index; raw = ""
            return word
        }
        override fun n(index: Int, fallback: String) = b(index).let { nc.f(it, it.isNotEmpty()) }
        override fun clear() { cleared++; raw = "" }
        override fun s() = if (raw.isEmpty()) emptyList() else words.take(2).map(::candidate)
        override fun m(start: Int, count: Int) = if (raw.isEmpty()) emptyList() else words.drop(start).take(count).map(::candidate)
        override fun f(): u = if (raw.isEmpty()) t else {
            val composing = if (splitRaw) raw.toList().joinToString("'") else raw
            s(0, composing, composing, emptyList())
        }
        override fun e() = false
        override fun d(text: String): r { restores++; raw = text; return result() }
        override fun h(key: Char): r {
            if (key == ' ' && raw.isNotEmpty()) { spaces++; return result(b(0)) }
            raw += key; return result()
        }
        override fun i(code: Int) = h(code.toChar())
        override fun o(text: String): r {
            if (text == " ") return h(' ')
            raw += text; return result()
        }
        override fun k(): r { raw = raw.dropLast(1); return result() }
        override fun c() = h('\'')
        override fun g(list: List<Any>) = result()
        override fun r() = raw.length
        override fun r(segment: Int) = result()
        override fun j() = ""
        override fun v() = emptyList<Any>()
        override fun destroy() = clear()
        override fun t() = clear()
        override fun l(rules: Int) {}
        override fun p(case: Int) {}
        override fun q(service: com.mi.ime.MiInputMethodService, callbacks: e) {}
        override fun u(dictionary: y8.b) {}
    }
}
