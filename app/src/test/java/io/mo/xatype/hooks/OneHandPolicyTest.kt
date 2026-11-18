package io.mo.xatype.hooks

import org.junit.Assert.*
import org.junit.Test

class OneHandPolicyTest {
    @Test fun `menu and swipe share directional toggles without confusing opposite sides`() {
        assertEquals(OneHandPolicy.LEFT, OneHandPolicy.actionSide("左手模式"))
        assertEquals(OneHandPolicy.RIGHT, OneHandPolicy.actionSide("右手模式"))
        assertNull(OneHandPolicy.actionSide("向左"))
        for (requested in listOf(OneHandPolicy.LEFT, OneHandPolicy.RIGHT)) {
            assertEquals(requested, OneHandPolicy.toggle(OneHandPolicy.OFF, requested))
            assertEquals(OneHandPolicy.OFF, OneHandPolicy.toggle(requested, requested))
            assertEquals(requested, OneHandPolicy.toggle(3 - requested, requested))
        }
        for (keyboard in io.mo.xatype.keyboard.ShortcutKeyboard.entries) {
            val mapping = mapOf(keyboard.keys[0] to "左手", keyboard.keys[1] to "右手")
            assertEquals(mapping, keyboard.decode(keyboard.encode(mapping)))
            assertEquals(mapping, keyboard.decode("${keyboard.keys[0]}=左手模式,${keyboard.keys[1]}=右手模式"))
        }
    }
    @Test fun `menu restores remembered side after off and defaults to right without history`() {
        assertEquals(OneHandPolicy.RIGHT, OneHandPolicy.menuTarget(OneHandPolicy.OFF, 0))
        for (side in listOf(OneHandPolicy.LEFT, OneHandPolicy.RIGHT)) {
            val remembered = OneHandPolicy.rememberedSide(side, 3 - side)
            assertEquals(side, remembered)
            assertEquals(OneHandPolicy.OFF, OneHandPolicy.menuTarget(side, remembered))
            assertEquals(side, OneHandPolicy.rememberedSide(OneHandPolicy.OFF, remembered))
            assertEquals(side, OneHandPolicy.menuTarget(OneHandPolicy.OFF, remembered))
        }
        assertEquals("左手", io.mo.xatype.keyboard.KeyboardShortcuts.label("左手模式"))
        assertEquals("右手", io.mo.xatype.keyboard.KeyboardShortcuts.label("右手模式"))
    }
    @Test fun `native centred margins and offset align the same measured width to either edge`() {
        for (available in listOf(360, 393, 411, 800)) for (percent in 70..90) {
            val left = OneHandPolicy.geometry(available, percent, OneHandPolicy.LEFT, true, false)!!
            val right = OneHandPolicy.geometry(available, percent, OneHandPolicy.RIGHT, true, false)!!
            assertEquals(left.leftMargin, right.leftMargin)
            assertEquals(left.rightMargin, right.rightMargin)
            assertEquals(0, left.leftMargin + left.offset)
            assertEquals(0, right.rightMargin - right.offset)
            assertTrue(available - left.leftMargin - left.rightMargin >= 240)
        }
    }

    @Test fun `unsupported contexts retain native geometry and small windows keep usable keys`() {
        assertNull(OneHandPolicy.geometry(400, 80, OneHandPolicy.OFF, true, false))
        assertNull(OneHandPolicy.geometry(400, 80, 99, true, false))
        assertNull(OneHandPolicy.geometry(400, 80, OneHandPolicy.LEFT, false, false))
        assertNull(OneHandPolicy.geometry(400, 80, OneHandPolicy.LEFT, true, true))
        for (available in listOf(-1, 0, 180, 240))
            assertNull(OneHandPolicy.geometry(available, 80, OneHandPolicy.LEFT, true, false))
        val small = OneHandPolicy.geometry(260, 70, OneHandPolicy.LEFT, true, false)!!
        assertEquals(240, 260 - small.leftMargin - small.rightMargin)
        assertEquals(OneHandPolicy.geometry(400, 70, 1, true, false), OneHandPolicy.geometry(400, -1, 1, true, false))
        assertEquals(OneHandPolicy.geometry(400, 90, 1, true, false), OneHandPolicy.geometry(400, 999, 1, true, false))
    }
}
