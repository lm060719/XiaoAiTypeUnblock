package io.mo.xatype.keyboard

import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.keyboard18.Keyboard14Keys
import io.mo.xatype.keyboard18.Keyboard18HostLayout
import io.mo.xatype.keyboard18.Keyboard18Keys
import io.mo.xatype.keyboard18.Keyboard18Style
import ka.c
import ka.e
import ka.f
import ka.g
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeyboardPositionsTest {
    private val loader = javaClass.classLoader!!
    private val abi = KeyboardAbi.reference(loader)
    private val layouts = KeyboardPositionsHostLayout(loader, abi)

    @Test fun `only complete permutations are accepted by each profile`() {
        for (profile in KeyboardPositions.entries) {
            val swapped = profile.defaultOrder.reversed()
            assertEquals(swapped, profile.decode(swapped.joinToString(",")))
            assertEquals(profile.defaultOrder, profile.decode(swapped.dropLast(1).joinToString(",")))
            assertEquals(profile.defaultOrder, profile.decode(swapped.joinToString(",") + ",invalid"))
            assertEquals(profile.defaultOrder, profile.decode((swapped.dropLast(1) + swapped.first()).joinToString(",")))
        }
    }

    @Test fun `a cross row swap moves the key code long press and action while preserving slot dimensions`() {
        val q = key('Q'.code, "Q", hint = "1", popup = listOf("Q", "1"), actualHint = "１")
        val enter = key(PositionKeyCode.ENTER, "发送", f.ENTER, width = 66f, height = 52f, action = 4)
        val other = e(1f, listOf(key('A'.code, "A")), null)
        val original = layout("qwerty", listOf(e(0.8f, listOf(q), null), other, e(1f, listOf(enter), null)))
        val updated = swap(original, KeyboardPositions.QWERTY, 'q'.code, PositionKeyCode.ENTER)

        val movedEnter = updated.c[0].a.single()
        val movedLetter = updated.c[2].a.single()
        assertEquals(listOf(enter.a, enter.b, enter.f, enter.r), listOf(movedEnter.a, movedEnter.b, movedEnter.f, movedEnter.r))
        assertEquals(listOf(q.a, q.b, q.i), listOf(movedLetter.a, movedLetter.b, movedLetter.i))
        assertEquals(listOf(q.g, q.h), listOf(movedEnter.g, movedEnter.h))
        assertEquals(listOf(enter.g, enter.h), listOf(movedLetter.g, movedLetter.h))
        assertEquals(listOf(q.c, q.d, q.e), listOf(movedEnter.c, movedEnter.d, movedEnter.e))
        assertEquals(listOf(enter.c, enter.d, enter.e), listOf(movedLetter.c, movedLetter.d, movedLetter.e))
        assertEquals(original.c[0].b, updated.c[0].b)
        assertEquals(original.f, updated.f)
        assertSame(other, updated.c[1])
        assertSame(q, original.c[0].a.single())
    }

    @Test fun `downward shortcuts resolve the original slot after swapping a letter with backspace`() {
        val z = key('z'.code, "Z", hint = "(")
        val delete = key(PositionKeyCode.DELETE, "⌫", f.BACKSPACE, repeatable = true)
        val original = layout("qwerty", listOf(e(1f, listOf(z, delete), null)))
        val movedLayout = swap(original, KeyboardPositions.QWERTY, 'z'.code, PositionKeyCode.DELETE)
        val (movedDelete, movedZ) = movedLayout.c.single().a
        val shortcuts = KeyboardShortcutHostLayout(loader, abi)
        val deleteSlot = layouts.originalSlot(movedDelete)
        val letterSlot = layouts.originalSlot(movedZ)
        assertSame(z, deleteSlot)
        assertSame(delete, letterSlot)
        assertEquals("全选", shortcuts.action(deleteSlot, KeyboardShortcutLayout.QWERTY))
        assertEquals(null, shortcuts.action(letterSlot, KeyboardShortcutLayout.QWERTY))
        assertEquals(f.CHAR, (shortcuts.actionKey(deleteSlot, "全选") as c).f)
        assertEquals(f.BACKSPACE, movedDelete.f)
        assertEquals("(", movedDelete.g)
        assertEquals(false, movedDelete.l)
        assertSame(z, layouts.originalSlot(z))
    }

    @Test fun `T9 moves full letter groups and leaves same-code number strip keys in place`() {
        val two = key('2'.code, "ABC", hint = "2")
        val nine = key('9'.code, "WXYZ", hint = "9")
        val numeric = key('2'.code, "2")
        val original = layout("t9", listOf(e(1f, listOf(numeric, two), null), e(1f, listOf(nine), null)))
        val updated = swap(original, KeyboardPositions.NINE, '2'.code, '9'.code)
        assertSame(numeric, updated.c[0].a.first())
        assertEquals("WXYZ", updated.c[0].a.last().b)
        assertEquals("ABC", updated.c[1].a.single().b)
        assertEquals("2", updated.c[0].a.last().g)
        assertSame(nine, layouts.originalSlot(updated.c[1].a.single()))
    }

    @Test fun `stroke layout moves primary input and resource labels while keeping digit hints in place`() {
        val profile = KeyboardPositions.STROKE
        val nativeCodes = ('1'..'6').map(Char::code) + listOf(-8, '：'.code, '；'.code)
        assertEquals(nativeCodes, profile.defaultOrder)
        assertEquals(('1'..'9').map(Char::toString), nativeCodes.map { profile.keys.getValue(it).hint })
        val mainKeys = nativeCodes.mapIndexed { index, code ->
            val kind = if (code == PositionKeyCode.SEPARATOR) f.FUNCTION else f.CHAR
            key(code, if (index >= 7) code.toChar().toString() else "", kind,
                width = 40f + index, hint = (index + 1).toString(), labelRes = 100 + index)
        }
        val numeric = key('1'.code, "1")
        val rows = mainKeys.chunked(3).mapIndexed { index, keys ->
            e(1f, if (index == 0) listOf(numeric) + keys else keys, null)
        }
        val original = layout("stroke", rows)
        val order = profile.defaultOrder.reversed().joinToString(",")
        val updated = layouts.reorder(original) { if (it == profile) order else null } as g
        assertSame(numeric, updated.c.first().a.first())
        val moved = updated.c.flatMap { it.a }.drop(1)
        assertEquals(mainKeys.reversed().map { listOf(it.a, it.f, it.p) },
            moved.map { listOf(it.a, it.f, it.p) })
        assertEquals(mainKeys.map { it.g }, moved.map { it.g })
        assertEquals(mainKeys.map { it.d }, moved.map { it.d })
        assertSame(original, layouts.reorder(updated) { null })
    }

    @Test fun `grouped settings are independent from QWERTY and from each other`() {
        val appearance = Keyboard18HostLayout(loader, abi)
        for ((keys, profile) in listOf(Keyboard18Keys to KeyboardPositions.GROUPED18,
                                     Keyboard14Keys to KeyboardPositions.GROUPED14)) {
            val source = listOf(keys.groups.first(), keys.groups.last()).map { group ->
                key(keys.code(group), Keyboard18Style.label(group, true), hint = keys.hint(group))
            }
            val original = layout("qwerty", listOf(e(1f, source, null)))
            assertSame(original, swap(original, KeyboardPositions.QWERTY, 'q'.code, 'm'.code))
            val updated = swap(original, profile, source.first().a, source.last().a).c.single().a
            assertEquals(listOf(keys.groups.last(), keys.groups.first()), updated.map(appearance::groupOf))
            assertEquals(source.map { it.g }, updated.map { it.g })
        }
    }

    @Test fun `unsupported surfaces and incomplete key swaps keep the original layout`() {
        val q = key('q'.code, "Q")
        val w = key('w'.code, "W")
        val split = layout("qwerty", listOf(e(1f, listOf(q), listOf(w))))
        val number = layout("number", listOf(e(1f, listOf(q, w), null)))
        val missing = layout("qwerty", listOf(e(1f, listOf(q), null)))
        val duplicate = layout("qwerty", listOf(e(1f, listOf(q, q, w), null)))
        for (original in listOf(split, number, missing, duplicate)) {
            assertSame(original, swap(original, KeyboardPositions.QWERTY, 'q'.code, 'w'.code))
        }
    }

    @Test fun `restoring a previously displayed layout neither flips swaps nor loses its original order`() {
        val original = layout("qwerty", listOf(e(1f, listOf(key('q'.code, "Q"), key('w'.code, "W")), null)))
        val reordered = swap(original, KeyboardPositions.QWERTY, 'q'.code, 'w'.code)
        val restored = swap(reordered, KeyboardPositions.QWERTY, 'q'.code, 'w'.code)
        assertEquals(listOf("W", "Q"), restored.c.single().a.map { it.b })
        assertSame(original, layouts.reorder(restored) { null })
    }

    @Test fun `missing punctuation does not stop independent letter swaps`() {
        val profile = KeyboardPositions.QWERTY
        val order = profile.defaultOrder.map {
            when (it) {
                'q'.code -> 'w'.code
                'w'.code -> 'q'.code
                PositionKeyCode.COMMA -> PositionKeyCode.PERIOD
                PositionKeyCode.PERIOD -> PositionKeyCode.COMMA
                else -> it
            }
        }
        val period = key(PositionKeyCode.PERIOD, ".")
        val original = layout("qwerty", listOf(e(1f, listOf(key('q'.code, "Q"), key('w'.code, "W"), period), null)))
        val reordered = layouts.reorder(original) { order.joinToString(",") } as g
        assertEquals(listOf("W", "Q", "."), reordered.c.single().a.map { it.b })
        assertSame(period, reordered.c.single().a.last())
    }

    private fun swap(original: g, profile: KeyboardPositions, first: Int, last: Int): g {
        val order = profile.defaultOrder.map { when (it) { first -> last; last -> first; else -> it } }
        return layouts.reorder(original) { if (it == profile) order.joinToString(",") else null } as g
    }

    private fun key(code: Int, label: String, kind: f = f.CHAR, width: Float = 32f, height: Float = 48f,
                    hint: String? = null, popup: List<String>? = null, action: Int? = null, labelRes: Int? = null,
                    actualHint: String? = hint, repeatable: Boolean = false): c =
        c(code, label, width / 32f, width, height, kind, hint, actualHint, popup,
            null, null, repeatable, null, false, false, labelRes, action != null, action)

    private fun layout(id: String, rows: List<e>) = g(id, id, rows, listOf("PINYIN"), null, 4f, true, null, false)
}
