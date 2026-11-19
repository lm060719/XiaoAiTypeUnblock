package io.mo.xatype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutLabelPositionTest {
    private val label = ShortcutLabelPosition.Bounds(-14f, -11f, 14f, 1f)

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

    @Test fun `tall keys keep the requested offsets beneath the native letter`() {
        val letter = ShortcutLabelPosition.Bounds(38f, 20f, 62f, 44f)
        val actual = ShortcutLabelPosition.place(100f, 70f, letter, label, 4f, 8f, 2f, 1f)
        assertEquals(ShortcutLabelPosition.Placement(54f, 62f), actual)
    }

    @Test fun `portrait uses the configured baseline even near the native letter layout`() {
        val center = ShortcutLabelPosition.centerFraction(0f, 240f, 156f)
        val actual = ShortcutLabelPosition.portraitPlacement(240f, 244f, center, 35f, -35f, 0f, 48.75f)
        assertEquals(156f, actual.x, 0.001f)
        assertEquals(195.25f, actual.baseline, 0f)
    }

    @Test fun `label stays centered when lowering it clears the letter`() {
        val letter = ShortcutLabelPosition.Bounds(36f, 10f, 64f, 32f)
        val actual = ShortcutLabelPosition.place(100f, 47f, letter, label, 0f, 8f, 2f, 1f)
        assertEquals(ShortcutLabelPosition.Placement(50f, 44f), actual)
    }

    @Test fun `short keys put the label to the lower right of the letter`() {
        val letter = ShortcutLabelPosition.Bounds(36f, 10f, 64f, 35f)
        val actual = ShortcutLabelPosition.place(100f, 46f, letter, label, 0f, 8f, 2f, 1f)
        assertEquals(ShortcutLabelPosition.Placement(84f, 43f), actual)
    }

    @Test fun `shortcut falls back to the left when the space on the right is too narrow`() {
        val letter = ShortcutLabelPosition.Bounds(33f, 10f, 69f, 35f)
        val widerLabel = label.copy(left = -14.75f, right = 14.75f)
        val actual = ShortcutLabelPosition.place(100f, 46f, letter, widerLabel, 0f, 8f, 2f, 1f)
        assertEquals(ShortcutLabelPosition.Placement(16.75f, 43f), actual)
    }

    @Test fun `corner placement stays inside a key with extra touch space at the edge`() {
        val letter = ShortcutLabelPosition.Bounds(21f, 10f, 49f, 35f)
        val actual = ShortcutLabelPosition.place(100f, 46f, letter, label, 0f, 8f, 2f, 1f)
        assertNull(actual)
        val compactLabel = ShortcutLabelPosition.Bounds(-9f, -8f, 9f, 1f)
        assertEquals(ShortcutLabelPosition.Placement(61f, 43f),
            ShortcutLabelPosition.place(100f, 46f, letter, compactLabel, 0f, 8f, 2f, 1f))
    }

    @Test fun `skip the label when the letter occupies the available space`() {
        val letter = ShortcutLabelPosition.Bounds(10f, 2f, 50f, 34f)
        assertNull(ShortcutLabelPosition.place(60f, 36f, letter, label, 0f, 8f, 2f, 1f))
    }
}
