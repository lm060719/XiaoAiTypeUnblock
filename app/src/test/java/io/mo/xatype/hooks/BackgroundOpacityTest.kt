package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundOpacityTest {
    @Test fun fullOpacityPreservesOriginalLightDarkAndTranslucentColors() {
        for (color in intArrayOf(0xffe5e5e7.toInt(), 0xff18191b.toInt(), 0x805a718c.toInt(), 0x00123456)) {
            assertEquals(color, BackgroundOpacity.argb(color, 100))
            assertEquals(packed(color), BackgroundOpacity.compose(packed(color), 100))
        }
    }

    @Test fun halfOpacityScalesOriginalAlphaWithoutChangingRgb() {
        assertEquals(0x40a1b2c3, BackgroundOpacity.argb(0x80a1b2c3.toInt(), 50))
        assertEquals(packed(0x40a1b2c3), BackgroundOpacity.compose(packed(0x80a1b2c3.toInt()), 50))
    }

    @Test fun zeroOpacityRemovesOnlyTheBackgroundAlpha() {
        assertEquals(0x00e5e5e7, BackgroundOpacity.argb(0xffe5e5e7.toInt(), 0))
        assertEquals(packed(0x00e5e5e7), BackgroundOpacity.compose(packed(0xffe5e5e7.toInt()), 0))
    }

    @Test fun outOfRangePreferencesAreClamped() {
        assertEquals(0x00123456, BackgroundOpacity.argb(0x80123456.toInt(), -10))
        assertEquals(0x80123456.toInt(), BackgroundOpacity.argb(0x80123456.toInt(), 110))
    }

    @Test fun wideGamutColorsKeepComponentsAndColorSpace() {
        val componentsAndSpace = 0x3c00380034000007L
        val color = componentsAndSpace or (800L shl 6)
        assertEquals(color, BackgroundOpacity.compose(color, 100))
        assertEquals(componentsAndSpace or (400L shl 6), BackgroundOpacity.compose(color, 50))
        assertEquals(componentsAndSpace, BackgroundOpacity.compose(color, 0))
    }

    @Test fun unspecifiedComposeColorRemainsUnspecified() {
        for (opacity in listOf(0, 50, 100)) {
            assertEquals(0x10L, BackgroundOpacity.compose(0x10L, opacity))
        }
    }

    private fun packed(argb: Int): Long = (argb.toLong() and 0xffffffffL) shl 32
}
