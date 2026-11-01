package io.mo.xatype.hooks

import org.junit.Assert.*
import org.junit.Test

class KeyPopupPaletteTest {
    data class Nested(var color: Long)
    data class Colors(
        val popupSelectorBackgroundColor: Long,
        val keyBackgroundEnter: Long,
        val keyBackgroundEnterGradientEnd: Long,
        val keyTextColor: Long,
        val keyTextColorEnter: Long,
        val keyBackgroundDefault: Long,
        val nested: Nested?
    )
    private val original = Colors(1, 2, 3, 4, 5, 6, Nested(7))
    private val palettes = KeyPopupPalette(Colors::class.java)

    @Test fun `popup colors do not modify the shared keyboard palette`() {
        val changed = palettes.apply(original, 10, 20, 30) as Colors
        assertEquals(Colors(10, 20, 20, 30, 30, 6, original.nested), changed)
        assertEquals(Colors(1, 2, 3, 4, 5, 6, Nested(7)), original)
        assertSame(original.nested, changed.nested)
    }

    @Test fun `reset after recomposition recovers original colors even after nested mutation`() {
        val changed = palettes.apply(original, 10, 20, 30)
        original.nested!!.color = 99
        val partial = palettes.apply(changed, null, 21, null) as Colors
        assertEquals(Colors(1, 21, 21, 4, 5, 6, original.nested), partial)
        assertSame(original, palettes.apply(partial, null, null, null))
        assertSame(original, palettes.apply(original, null, null, null))
    }
}
