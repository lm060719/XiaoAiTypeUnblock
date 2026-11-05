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
    }

    // Normals: 0 right, 90 top, 180 left, 270 bottom.
    @Test fun lightFallsFromTheUpperLeft() {
        val w = { normal: Float -> GlassRim.lightWeight(normal) }
        assertEquals(1f, w(GlassRim.LIGHT_ANGLE), 1e-4f)
        assertTrue(w(135f) > w(45f))   // top-left corner over top-right
        assertTrue(w(90f) > w(180f))   // top edge over left
        assertTrue(w(180f) > w(0f))    // left over right
        assertTrue(w(315f) > w(225f))  // the counter reflection sits lower right
        assertTrue(w(GlassRim.LIGHT_ANGLE + 180f) < w(90f))
        for (deg in 0..359) assertTrue(w(deg.toFloat()) in 0f..1f)
        assertEquals(w(10f), w(370f), 1e-4f)
    }

    @Test fun lightDirectionsMirrorAndCenter() {
        assertEquals(GlassRim.LIGHT_ANGLE, GlassRim.lightAngle(0), 0f)
        assertEquals(GlassRim.LIGHT_ANGLE, GlassRim.lightAngle(7), 0f) // unknown falls back
        val right = GlassRim.lightAngle(2)
        for (deg in 0..359) {
            // Upper right is the upper-left light seen in a mirror (normal θ ↔ 180° − θ).
            assertEquals(GlassRim.lightWeight(180f - deg), GlassRim.lightWeight(deg.toFloat(), right), 1e-4f)
        }
        val top = GlassRim.lightAngle(1)
        assertEquals(1f, GlassRim.lightWeight(90f, top), 1e-4f)
        assertEquals(GlassRim.lightWeight(45f, top), GlassRim.lightWeight(135f, top), 1e-4f)
        assertEquals(GlassRim.LIGHT_NAMES.size, 3)
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
