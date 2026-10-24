package io.mo.xatype.keyboard

import io.mo.xatype.compat.KeyboardAbi
import org.junit.Assert.*
import org.junit.Test
import z8.*

class EmojiCandidatesTest {
    private val table = EmojiCandidates.read(checkNotNull(javaClass.getResourceAsStream("/xatype/emoji.tsv")).reader())
    private val bridge = EmojiHostBridge(javaClass.classLoader!!, KeyboardAbi.reference(javaClass.classLoader!!))
    private fun wrapped(native: Native, enabled: () -> Boolean = { true }) =
        bridge.create(native, { table }, enabled) { throw AssertionError(it) } as p

    @Test fun `real table covers aliases and preserves complete scientist sequences`() {
        assertEquals(listOf("🐷", "🐖", "🐽"), table.additions("xiao'zhu", listOf("小猪")).map { it.text })
        assertEquals(listOf("🐷", "🐖", "🐽"), table.additions("zhuzhu", listOf("猪猪")).map { it.text })
        assertEquals(listOf("🧑‍🔬", "👨‍🔬", "👩‍🔬"), table.additions("KE XUE JIA", listOf("科学家")).map { it.text })
        assertEquals(listOf("👩‍🔬"), table.additions("nü'ke'xue'jia", listOf("女科学家")).map { it.text })
        assertEquals(listOf("🐖", "🐽"), table.additions("xiaozhu", listOf("小猪", "🐷️")).map { it.text })
        // Common single characters stay plain; only explicit aliases such as 猪 add emoji.
        assertTrue(table.additions("shi", listOf("是", "时", "事", "十", "使")).isEmpty())
        assertEquals(listOf("🐷", "🐖", "🐽"), table.additions("zhu", listOf("猪")).map { it.text })
        for (raw in listOf("", "xiaozhupeiqi", "小猪pei'qi", "xiaozhu1", "xiao", "123")) {
            assertTrue(raw, table.additions(raw, listOf("小猪", "猪")).isEmpty())
        }
    }

    @Test fun `native first choice stays first and shifted text selection uses original index`() {
        val native = Native()
        val proxy = wrapped(native)
        assertEquals(listOf("小猪", "🐷", "🐖", "🐽", "小朱"), proxy.s().map { it.text })
        assertEquals("小朱", proxy.n(4, "小朱").first)
        assertEquals(1, native.selected)
        assertEquals(0, native.cleared)
        val first = Native()
        val p = wrapped(first)
        p.s()
        assertEquals("小猪", p.b(0))
        assertEquals(0, first.selected)
    }

    @Test fun `selected shuangpin scheme produces selectable emoji without losing a suffix`() {
        assertEquals(listOf("🧑‍🔬", "👨‍🔬", "👩‍🔬"), table.additions("kextjx", listOf("科学家"),
            ShuangpinScheme.XIAOHE).map { it.text })
        val spellings = mapOf(ShuangpinScheme.XIAOHE to "xnvu", ShuangpinScheme.ZIRANMA to "xcvu",
            ShuangpinScheme.WEIRUAN to "xcvu", ShuangpinScheme.SOUGOU to "xcvu",
            ShuangpinScheme.ZHINENG_ABC to "xzau", ShuangpinScheme.PINYIN_JIAJIA to "xkvu",
            ShuangpinScheme.ZIGUANG to "xbuu")
        for ((scheme, raw) in spellings) {
            val native = Native().apply { this.raw = raw }
            val proxy = bridge.create(native, { table }, { true }, { scheme }) { throw AssertionError(it) } as p
            assertEquals(scheme.name, listOf("小猪", "🐷", "🐖", "🐽", "小朱"), proxy.s().map { it.text })
            val selected = proxy.n(1, "🐷")
            assertEquals("🐷", selected.first)
            assertTrue(selected.second)
            assertEquals(1, native.cleared)
            assertEquals(-1, native.selected)
            assertSame(t, proxy.f())
            assertTrue(table.additions(raw + "pq", listOf("小猪"), scheme).isEmpty())
        }
        assertTrue(table.additions("xnvu", listOf("小猪")).isEmpty())
        assertTrue(table.additions("xnvu", listOf("小猪"), ShuangpinScheme.ZIRANMA).isEmpty())
        assertTrue(table.additions("小xnvu", listOf("小猪"), ShuangpinScheme.XIAOHE).isEmpty())
        assertEquals(listOf("🐷", "🐖", "🐽"), table.additions("9688", listOf("小猪"),
            ShuangpinScheme.XIAOHE, nineKey = true).map { it.text })
        assertTrue(table.additions("9688", listOf("小猪"), ShuangpinScheme.XIAOHE).isEmpty())
        assertEquals(listOf("🐷", "🐖", "🐽"), table.additions("9426948", listOf("小猪"), nineKey = true).map { it.text })
        assertTrue(table.additions("9426948", listOf("小猪")).isEmpty())
        val semicolon = EmojiCandidates.read("星星\txing'xing\t⭐".reader())
        assertEquals(listOf("⭐"), semicolon.additions("x;x;", listOf("星星"), ShuangpinScheme.WEIRUAN).map { it.text })
    }

    @Test fun `emoji selection clears composition once and does not select or learn a Chinese word`() {
        val native = Native()
        var enabled = true
        val proxy = wrapped(native) { enabled }
        proxy.s()
        enabled = false // An already displayed candidate keeps its identity until the next refresh.
        val selected = proxy.n(2, "🐖")
        assertEquals("🐖", selected.first)
        assertTrue(selected.second)
        assertEquals(1, native.cleared)
        assertEquals(-1, native.selected)
        assertSame(t, proxy.f())
        assertTrue(proxy.s().isEmpty())
    }

    @Test fun `pagination spans injected head and native tail without skipping or repeating words`() {
        val native = Native()
        val proxy = wrapped(native)
        proxy.s()
        assertEquals(listOf("🐽", "小朱", "候选2", "候选3"), proxy.m(3, 4).map { it.text })
        assertEquals(2, native.pageStart)
        assertEquals(listOf("候选2", "候选3", "候选4"), proxy.m(5, 3).map { it.text })
        assertEquals("候选3", proxy.n(6, "候选3").first)
        assertEquals(3, native.selected)
    }

    @Test fun `edits stale taps disabled and unsupported engines retain native behavior`() {
        val native = Native()
        val proxy = wrapped(native)
        proxy.s()
        native.raw = "xiaozhupeiqi" // A decoder update outside the proxy must not lose the suffix.
        val stale = proxy.n(1, "🐷")
        assertEquals("", stale.first)
        assertFalse(stale.second)
        assertEquals(0, native.cleared)
        assertEquals(listOf("小猪", "小朱"), proxy.s().map { it.text })
        proxy.k()
        assertEquals(native.raw, (proxy.f() as s).raw)
        assertTrue(bridge.supports(native))
        assertFalse(bridge.supports(proxy)) // Do not wrap independent shape/18-key proxies.
        assertSame(native, EmojiHostBridge.unwrap(proxy))
        assertSame(native, EmojiHostBridge.unwrap(native))
        assertEquals(listOf("小猪", "小朱"), wrapped(Native()) { false }.s().map { it.text })
        assertTrue(wrapped(Native(), { true }).let { it.s(); it.m(Int.MAX_VALUE, 2).isEmpty() })
    }

    @Test fun `decoration failure keeps native candidates and disables only enhancement`() {
        val native = Native()
        var failures = 0
        val proxy = bridge.create(native, { table }, { error("Preference unavailable") }, failed = { failures++ }) as p
        repeat(2) { assertEquals(listOf("小猪", "小朱"), proxy.s().map { it.text }) }
        assertEquals(1, failures)
        assertEquals("小朱", proxy.n(1, "小朱").first)
        assertEquals(1, native.selected)
        assertEquals(0, native.cleared)
    }

    @Test fun `prediction defaults preserve first and subsequent native associations`() {
        assertEquals(PredictionMode.KEEP, PredictionMode.resolve(null))
        assertEquals(PredictionMode.KEEP, PredictionMode.resolve("unknown"))
        assertEquals(listOf(PredictionMode.KEEP, PredictionMode.DISABLE), PredictionMode.entries)
        listOf("DISABLE", "DISABLE_NEXT", "DISABLE_CONTINUOUS").forEach {
            assertEquals(PredictionMode.DISABLE, PredictionMode.resolve(it))
        }
        val native = Native().apply { predictAfterSelection = true }
        val proxy = bridge.create(native, { error("Emoji dictionary must not load") }, { false }) { throw AssertionError(it) } as p
        assertEquals("小猪", proxy.n(0, "小猪").first)
        repeat(2) {
            assertTrue(proxy.e())
            assertEquals(listOf("你好", "世界"), proxy.s().map { it.text })
            assertEquals("你好", proxy.b(0))
        }
        assertEquals(0, native.cleared)
    }

    @Test fun `disable clears only predictions across selection and snapshot paths`() {
        val native = Native().apply { predictAfterSelection = true }
        val proxy = bridge.create(native, { error("Emoji dictionary must not load") }, { false },
            prediction = { PredictionMode.DISABLE }) { throw AssertionError(it) } as p
        assertEquals(listOf("小猪", "小朱"), proxy.s().map { it.text })
        assertEquals("小猪", proxy.n(0, "小猪").first)
        assertSame(t, proxy.f())
        assertTrue(proxy.s().isEmpty())
        assertFalse(proxy.e())
        assertEquals(1, native.cleared)
        proxy.d("xiao'zhu")
        assertEquals("小朱", proxy.b(1))
        assertEquals(2, native.cleared)
        // Predictions may also arrive from a native key commit or an external update.
        native.hasPredictions = true
        assertTrue(proxy.m(0, 2).isEmpty())
        assertEquals(3, native.cleared)
        native.hasPredictions = true
        assertSame(t, proxy.f())
        assertEquals(4, native.cleared)
        native.raw = "pei'qi"
        native.hasPredictions = true
        assertEquals("pei'qi", (proxy.f() as s).raw)
        assertEquals(4, native.cleared)
    }

    @Test fun `disable preserves emoji mapping remaining input and space commits`() {
        val native = Native().apply { predictAfterSelection = true }
        val proxy = bridge.create(native, { table }, { true },
            prediction = { PredictionMode.DISABLE }) { throw AssertionError(it) } as p
        assertEquals(listOf("小猪", "🐷", "🐖", "🐽", "小朱"), proxy.s().map { it.text })
        assertEquals("小朱", proxy.n(4, "小朱").first)
        assertTrue(proxy.s().isEmpty())
        assertEquals(1, native.cleared)
        // An invalid selection must not duplicate the native commit or clear.
        assertEquals("", proxy.n(-1, "").first)
        assertFalse(proxy.e())
        assertEquals(1, native.cleared)
        proxy.d("xiaozhu")
        native.suffixAfterSelection = "pei'qi"
        assertEquals("小猪", proxy.b(0))
        assertEquals("pei'qi", (proxy.f() as s).raw)
        assertEquals(1, native.cleared)
        native.suffixAfterSelection = ""
        // A native key result can contain a commit and a stale prediction snapshot.
        native.raw = ""
        native.hasPredictions = true
        val committed = proxy.h(' ')
        assertEquals("你好", committed.commit)
        assertTrue(committed.candidates.isEmpty())
        assertTrue(committed.consumed)
        assertEquals(2, committed.delete)
        assertEquals(listOf("metadata"), committed.errors)
        assertEquals("accepted", committed.acceptedRaw)
        assertTrue(proxy.s().isEmpty())
        assertEquals(2, native.cleared)
    }

    @Test fun `prediction setting failure preserves native commit without retrying selection`() {
        val native = Native().apply { predictAfterSelection = true }
        var failures = 0
        val proxy = bridge.create(native, { table }, { true }, prediction = { error("Unavailable setting") },
            predictionFailed = { failures++ }) { throw AssertionError(it) } as p
        assertEquals("小猪", proxy.n(0, "小猪").first)
        assertEquals(listOf("你好", "世界"), proxy.s().map { it.text })
        assertEquals("你好", proxy.b(0))
        assertEquals(1, failures)
        assertEquals(0, native.cleared)
    }

    /** Records the native index/consumption contract independently of emoji insertion. */
    private class Native : p {
        var raw = "xiao'zhu"
        var selected = -1
        var cleared = 0
        var pageStart = -1
        var predictAfterSelection = false
        var hasPredictions = false
        var suffixAfterSelection = ""
        private val words = listOf("小猪", "小朱") + (2..49).map { "候选$it" }
        private val predictionWords = listOf("你好", "世界")
        private fun currentWords() = if (e()) predictionWords else words
        private fun candidate(text: String) = a(text, null, 1, b.SYSTEM, null)
        private fun snapshot() = r(true, raw, s(), null, 0, emptyList(), raw)
        override fun a() = g.PINYIN
        override fun b(index: Int): String {
            val word = currentWords().getOrNull(index) ?: return ""
            selected = index; raw = suffixAfterSelection; hasPredictions = predictAfterSelection
            return word
        }
        override fun n(index: Int, fallback: String) = b(index).let { nc.f(it, it.isNotEmpty()) }
        override fun s() = if (raw.isEmpty() && !e()) emptyList() else currentWords().take(2).map(::candidate)
        override fun m(start: Int, count: Int): List<a> {
            pageStart = start
            return if (raw.isEmpty() && !e()) emptyList() else currentWords().drop(start).take(count).map(::candidate)
        }
        override fun f(): u = if (raw.isEmpty() && !e()) t else s(0, raw, raw, emptyList())
        override fun clear() { cleared++; raw = ""; hasPredictions = false }
        override fun d(text: String): r { raw = text; hasPredictions = false; return snapshot() }
        override fun k(): r { raw = raw.dropLast(1); return snapshot() }
        override fun i(code: Int) = h(code.toChar())
        override fun h(key: Char): r {
            if (key == ' ' && e()) return r(true, "", s(), b(0), 2, listOf("metadata"), "accepted")
            raw += key
            return snapshot()
        }
        override fun c() = h('\'')
        override fun o(text: String): r { raw += text; return snapshot() }
        override fun g(list: List<Any>) = snapshot()
        override fun r(segment: Int) = snapshot()
        override fun e() = raw.isEmpty() && hasPredictions
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
