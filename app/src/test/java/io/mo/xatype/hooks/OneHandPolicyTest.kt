package io.mo.xatype.hooks

import org.junit.Assert.*
import org.junit.Test

class OneHandPolicyTest {
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
