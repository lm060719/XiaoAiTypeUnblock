package io.mo.xatype.hooks

import org.junit.Assert.*
import org.junit.Test

class GlassBlurRegionTest {
    @Test fun `AOSP uses four radii with window relative bounds`() {
        val template = floatArrayOf(20f, 1f, 0f, 0f, 0f, 0f, 11f, 22f, 33f, 44f)
        val result = GlassBlurRegion.encode(template, 100, 12, 500, 800, 300, 30f, 80f)
        assertArrayEquals(floatArrayOf(100f, 1f, 12f, 500f, 812f, 800f, 30f, 30f, 80f, 80f), result, 0f)
        assertEquals(11f, template[6], 0f)
    }

    @Test fun `HyperOS uses xy pairs and preserves extended platform defaults`() {
        val template = FloatArray(27).apply {
            floatArrayOf(11f, 11f, 22f, 22f, 33f, 33f, 44f, 44f).copyInto(this, 6)
            this[20] = 0.75f
        }
        val result = GlassBlurRegion.encode(template, 150, 0, 1627, 1440, 1404, 68f, 140f)
        assertEquals(3031f, result[5], 0f)
        assertArrayEquals(floatArrayOf(68f, 68f, 68f, 68f, 140f, 140f, 140f, 140f), result.sliceArray(6..13), 0f)
        assertEquals(27, result.size)
        assertEquals(0.75f, result[20], 0f)
    }

    @Test fun `unrecognized platform layouts are rejected before native calls`() {
        listOf(FloatArray(0), FloatArray(10), FloatArray(14)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                GlassBlurRegion.encode(it, 100, 0, 0, 100, 100, 10f, 20f)
            }
        }
    }
}
