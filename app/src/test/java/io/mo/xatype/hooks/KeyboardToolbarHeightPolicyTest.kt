package io.mo.xatype.hooks

import org.junit.Assert.*
import org.junit.Test

class KeyboardToolbarHeightPolicyTest {
    @Test fun dockedToolbarAndTotalShrinkByTheSameAmount() {
        val bar = KeyboardToolbarHeightPolicy.height(48) {
            KeyboardToolbarHeightPolicy.observe(false)
            58f
        }
        val total = KeyboardToolbarHeightPolicy.total(300f) {
            KeyboardToolbarHeightPolicy.delta(48) {
                assertTrue(KeyboardToolbarHeightPolicy.isNativeRead())
                KeyboardToolbarHeightPolicy.observe(false)
                58f
            }
        }
        assertEquals(48f, bar)
        assertEquals(290f, total)
        assertEquals(300f - 58f, total - bar)
        assertFalse(KeyboardToolbarHeightPolicy.isNativeRead())
    }

    @Test fun floatingAndUnknownLayoutsKeepNativeHeights() {
        for (floating in listOf(true, null)) {
            assertEquals(58f, KeyboardToolbarHeightPolicy.height(48) {
                KeyboardToolbarHeightPolicy.observe(floating)
                58f
            })
            assertEquals(0f, KeyboardToolbarHeightPolicy.delta(48) {
                KeyboardToolbarHeightPolicy.observe(floating)
                58f
            })
        }
    }

    @Test fun heightLimitsAndLayoutChangesUseCurrentNativeHeight() {
        assertEquals(48f, KeyboardToolbarHeightPolicy.height(0) {
            KeyboardToolbarHeightPolicy.observe(false); 58f
        })
        assertEquals(80f, KeyboardToolbarHeightPolicy.height(999) {
            KeyboardToolbarHeightPolicy.observe(false); 58f
        })
        assertEquals(6f, KeyboardToolbarHeightPolicy.delta(48) {
            KeyboardToolbarHeightPolicy.observe(false); 42f
        })
    }

    @Test fun tallerToolbarAddsSpaceWithoutConsumingTheKeyRows() {
        val total = KeyboardToolbarHeightPolicy.total(300f) {
            KeyboardToolbarHeightPolicy.delta(80) {
                KeyboardToolbarHeightPolicy.observe(false); 58f
            }
        }
        assertEquals(322f, total)
        assertEquals(300f - 58f, total - 80f)
    }

    @Test fun nativeFailureRestoresScopes() {
        runCatching { KeyboardToolbarHeightPolicy.delta(48) { error("native") } }
        assertFalse(KeyboardToolbarHeightPolicy.isNativeRead())
        assertEquals(290f, KeyboardToolbarHeightPolicy.total(300f) { -10f })
        assertEquals(58f, KeyboardToolbarHeightPolicy.height(48) { 58f })
    }

    @Test fun nestedMeasurementsRestoreOuterFloatingState() {
        assertEquals(48f, KeyboardToolbarHeightPolicy.height(48) {
            KeyboardToolbarHeightPolicy.observe(false)
            assertEquals(0f, KeyboardToolbarHeightPolicy.delta(48) {
                KeyboardToolbarHeightPolicy.observe(true); 42f
            })
            KeyboardToolbarHeightPolicy.observe(true)
            58f
        })
    }

    @Test fun hiddenOrInvalidNativeHeightsArePreserved() {
        for (height in listOf(0f, -1f, Float.POSITIVE_INFINITY, Float.NaN)) {
            assertEquals(height, KeyboardToolbarHeightPolicy.height(48) {
                KeyboardToolbarHeightPolicy.observe(false); height
            })
            assertEquals(height, KeyboardToolbarHeightPolicy.total(height) { error("hidden height") })
        }
    }
}
