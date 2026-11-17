package io.mo.xatype.keyboard

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class KeyboardShortcutHostActionsTest {
    private val adapter = KeyboardShortcutHostActions(javaClass.classLoader!!, io.mo.xatype.compat.KeyboardAbi.reference(javaClass.classLoader!!))
    private class Callbacks(val segments: MutableList<String>, val hasCandidates: Boolean = true,
                            val stall: Boolean = false) : ab.b1 {
        val events = mutableListOf<String>()
        private val engine = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(z8.q::class.java)) { _, method, _ ->
            check(method.name == "clear")
            segments.clear(); events.add("clear"); null
        } as z8.q
        override fun u() = engine
        override fun o() = false
        override fun K(text: String) { events.add("commit:$text") }
        override fun y(result: z8.r) {}
        override fun n() = f().isNotEmpty()
        override fun d() = hasCandidates
        override fun f() = segments.firstOrNull().orEmpty()
        override fun M() = 2
        override fun b(index: Int) {
            events.add("select:$index")
            if (!stall) segments.removeAt(0)
        }
        override fun F() { events.add("refresh") }
        override fun J() { events.add("finish") }
    }

    @Test fun `pending segments use the host highlighted candidate until complete`() {
        for (segments in listOf(listOf("wsm", "sm", "m"), listOf("64726", "426", "26"))) {
            val target = Callbacks(segments.toMutableList())
            adapter.finishInput(target)
            assertEquals(listOf("select:2", "select:2", "select:2", "finish"), target.events)
            assertTrue(target.segments.isEmpty())
        }
    }

    @Test fun `missing or stalled candidates preserve remaining text and clear state`() {
        val missing = Callbacks(mutableListOf("raw"), hasCandidates = false)
        adapter.finishInput(missing)
        assertEquals(listOf("commit:raw", "clear", "refresh", "finish"), missing.events)
        val stalled = Callbacks(mutableListOf("raw"), stall = true)
        adapter.finishInput(stalled)
        assertEquals(listOf("select:2", "commit:raw", "clear", "refresh", "finish"), stalled.events)
    }

    @Test fun `existing editor selection is preserved when there is no composition`() {
        val target = Callbacks(mutableListOf())
        adapter.finishInput(target)
        assertEquals(listOf("finish"), target.events)
    }

    @Test fun `cancel discards pending input without selecting or committing even with stalled candidates`() {
        for (candidates in listOf(true, false)) {
            val target = Callbacks(mutableListOf("pending", "suffix"), hasCandidates = candidates, stall = true)
            assertTrue(adapter.hasPending(target))
            adapter.cancelInput(target)
            assertFalse(adapter.hasPending(target))
            assertEquals(listOf("clear", "refresh", "finish"), target.events)
        }
    }
}
