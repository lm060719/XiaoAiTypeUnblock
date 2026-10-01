package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardHeightPolicyTest {
    @Test fun topCanGrowPastTheNativeLimitAndStillShrink() {
        for (top in listOf(-250, -51, -50, 0, 20, 80)) {
            val result = KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.TOP_DRAG) {
                KeyboardHeightPolicy.overrideClamp(top, -50, 50)
            }
            assertEquals(top.coerceAtMost(50), result)
        }
    }

    @Test fun savedTallRectangleRetainsItsHeightAndBottomPosition() {
        val top = -250
        val bottom = 50
        val extraHeight = KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.DIMENSIONS) {
            KeyboardHeightPolicy.overrideClamp(bottom - top - 50, -50, 50)
        }!!
        assertEquals(250, extraHeight)
        assertEquals(0, top + extraHeight) // yOffset, original bottom stays anchored
        assertEquals(0, top + extraHeight + 50 - bottom) // no negative bottom padding
    }

    @Test fun otherBoundsAndCallsOutsideScopeAreUntouched() {
        assertNull(KeyboardHeightPolicy.overrideClamp(-250, -50, 50))
        KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.DIMENSIONS) {
            assertNull(KeyboardHeightPolicy.overrideClamp(100, 0, 50)) // width
            assertNull(KeyboardHeightPolicy.overrideClamp(-100, -50, 50)) // shrinking
            assertEquals(250, KeyboardHeightPolicy.overrideClamp(250, -50, 50))
            assertNull(KeyboardHeightPolicy.overrideClamp(250, -50, 50)) // consumed
        }
    }

    @Test fun exceptionsAndNestedCallsRestoreScope() {
        KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.TOP_DRAG) {
            try {
                KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.DIMENSIONS) {
                    throw IllegalStateException("test")
                }
            } catch (_: IllegalStateException) {
            }
            assertEquals(-250, KeyboardHeightPolicy.overrideClamp(-250, -50, 50))
        }
        assertNull(KeyboardHeightPolicy.overrideClamp(-250, -50, 50))
    }

    @Test fun scopesDoNotLeakAcrossThreads() {
        KeyboardHeightPolicy.within(KeyboardHeightPolicy.Scope.TOP_DRAG) {
            var result: Int? = 0
            Thread { result = KeyboardHeightPolicy.overrideClamp(-250, -50, 50) }
                .apply { start(); join() }
            assertNull(result)
            assertEquals(-250, KeyboardHeightPolicy.overrideClamp(-250, -50, 50))
        }
    }
}
