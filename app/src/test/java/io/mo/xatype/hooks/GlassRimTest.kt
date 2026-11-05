package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRimTest {
    @Test fun offDrawsNothing() {
        for (dark in listOf(false, true)) {
            assertEquals(0, GlassRim.highlightAlpha(0, dark))
            assertEquals(0, GlassRim.hairlineAlpha(0, dark))
        }
        assertEquals(0, GlassRim.sheenAlpha(0))
    }

    @Test fun lightSurfacesGetAStrongerHighlightAndADarkHairline() {
        for (level in listOf(10, GlassRim.DEFAULT_INTENSITY, 100)) {
            assertTrue(GlassRim.highlightAlpha(level, false) > GlassRim.highlightAlpha(level, true))
            assertTrue(GlassRim.hairlineAlpha(level, false) > 0)
            assertEquals(0, GlassRim.hairlineAlpha(level, true))
        }
    }

    @Test fun strengthScalesMonotonicallyAndStaysWithinAlpha() {
        var previous = -1
        for (level in 0..100) {
            val alpha = GlassRim.highlightAlpha(level, false)
            assertTrue(alpha >= previous)
            assertTrue(alpha in 0..255)
            previous = alpha
        }
        assertEquals(GlassRim.highlightAlpha(100, true), GlassRim.highlightAlpha(150, true))
        assertEquals(0, GlassRim.highlightAlpha(-20, false))
    }

    @Test fun equalSettingsCompareEqualSoTheForegroundIsReused() {
        val a = GlassRim.Spec(60, 48f, roundBottom = false, surfaceDark = true, density = 3f)
        assertEquals(a, a.copy())
        assertTrue(a != a.copy(roundBottom = true))
    }
}
