package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadabilityScrimTest {
    @Test fun glassScrimSliderHasDistinctZeroMiddleAndFullStrength() {
        val base = 0x00000000
        val tint = 0xff123456.toInt()
        assertEquals(0, ReadabilityScrim.glassColor(base, null))
        assertEquals(0x7f123456, ReadabilityScrim.glassColor(base, BackgroundOpacity.argb(tint, 50)))
        assertEquals(tint, ReadabilityScrim.glassColor(base, tint))
    }

    @Test fun glassOpacitySurvivesWithScrimDisabledAndScrimIsAppliedOnlyOnce() {
        val base = BackgroundOpacity.argb(0xffeeeeee.toInt(), 50)
        assertEquals(base, ReadabilityScrim.glassColor(base, null))
        val scrim = BackgroundOpacity.argb(0xffeeeeee.toInt(), 50)
        // Two independent 50% layers produce about 75%, not the 87.5% of
        // adding the scrim to background and then drawing it again in foreground.
        val actual = ReadabilityScrim.glassColor(base, scrim)
        assertEquals(190, actual ushr 24)
        assertEquals(0xeeeeee, actual and 0xffffff)
    }

    @Test fun fullGlassScrimUsesItsColorOverNativeTintLikeThePreview() {
        val native = 0xffeeeeee.toInt()
        val scrim = 0xff123456.toInt()
        assertEquals(scrim, ReadabilityScrim.glassColor(native, scrim))
        assertEquals(native, ReadabilityScrim.glassColor(native, 0x00123456))
    }

    @Test fun autoColorContrastsWithLabels() {
        assertEquals(ReadabilityScrim.DARK, ReadabilityScrim.autoColor(0xffffffff.toInt(), false))
        assertEquals(ReadabilityScrim.LIGHT, ReadabilityScrim.autoColor(0xe6000000.toInt(), true))
        // Native labels follow the surface: light text on dark surfaces.
        assertEquals(ReadabilityScrim.DARK, ReadabilityScrim.autoColor(null, true))
        assertEquals(ReadabilityScrim.LIGHT, ReadabilityScrim.autoColor(null, false))
    }

    @Test fun opaqueBackgroundsHideTheScrim() {
        val base = 0xff1e1e2e.toInt()
        assertEquals(base, ReadabilityScrim.under(base, ReadabilityScrim.LIGHT, 100))
    }

    @Test fun zeroOpacityLeavesTheBackgroundUnchanged() {
        val base = 0x401e1e2e
        assertEquals(base, ReadabilityScrim.under(base, ReadabilityScrim.LIGHT, 0))
    }

    @Test fun transparentBackgroundBecomesTheScrim() {
        val actual = ReadabilityScrim.under(0x00000000, 0xff123456.toInt(), 40)
        assertEquals(102, actual ushr 24)
        assertEquals(0x123456, actual and 0xffffff)
    }

    @Test fun translucentBackgroundIsComposedOverTheScrim() {
        // 50% white over 50% black: alpha 75%, color one third white.
        val actual = ReadabilityScrim.under(0x80ffffff.toInt(), 0xff000000.toInt(), 50)
        assertEquals(191, actual ushr 24)
        assertEquals(171, actual and 0xff)
        assertEquals((actual shr 8) and 0xff, actual and 0xff)
    }
}
