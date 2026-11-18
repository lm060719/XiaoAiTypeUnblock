package io.mo.xatype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutLabelPositionTest {
    @Test fun `edge labels follow the native letter instead of the expanded touch centre`() {
        assertEquals(0.65f, ShortcutLabelPosition.centerFraction(0f, 240f, 156f), 0.0001f)
        assertEquals(0.35f, ShortcutLabelPosition.centerFraction(1200f, 1440f, 1284f), 0.0001f)
        assertEquals(0.5f, ShortcutLabelPosition.centerFraction(240f, 400f, 320f), 0.0001f)
        // Moving or shrinking the keyboard preserves the relative anchor.
        assertEquals(0.65f, ShortcutLabelPosition.centerFraction(300f, 492f, 424.8f), 0.0001f)
    }

    @Test fun `unavailable or stale coordinates retain centred labels`() {
        for (center in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 201f))
            assertEquals(0.5f, ShortcutLabelPosition.centerFraction(0f, 200f, center), 0f)
        assertEquals(0.5f, ShortcutLabelPosition.centerFraction(100f, 100f, 100f), 0f)
    }
}
