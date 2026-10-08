package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardBottomSpacingPolicyTest {
    @Test fun dockedKeyboardCanRemoveAndAddSpacingEvenWhenNativeSpacingIsZero() {
        for (native in listOf(0f, 4f, 34f)) {
            for (spacing in listOf(-1, 0, 20, 100, 101)) {
                val actual = KeyboardBottomSpacingPolicy.calculate(spacing) {
                    KeyboardBottomSpacingPolicy.observe("profile")
                    KeyboardBottomSpacingPolicy.observe(false)
                    KeyboardBottomSpacingPolicy.observe(true) // later navigation-mode local
                    native
                }
                assertEquals(spacing.coerceIn(0, 100).toFloat(), actual, 0f)
            }
        }
    }

    @Test fun floatingAndUnrecognizedLayoutsRetainTheirNativeSpacing() {
        assertEquals(6f, KeyboardBottomSpacingPolicy.calculate(80) {
            KeyboardBottomSpacingPolicy.observe(true)
            KeyboardBottomSpacingPolicy.observe(false)
            6f
        }, 0f)
        assertEquals(34f, KeyboardBottomSpacingPolicy.calculate(80) { 34f }, 0f)
    }

    @Test fun exceptionsAndNestedCallsRestoreTheOuterScope() {
        assertEquals(20f, KeyboardBottomSpacingPolicy.calculate(20) {
            try {
                KeyboardBottomSpacingPolicy.calculate(50) { error("test") }
            } catch (_: IllegalStateException) { }
            assertEquals(6f, KeyboardBottomSpacingPolicy.calculate(50) {
                KeyboardBottomSpacingPolicy.observe(true)
                6f
            }, 0f)
            KeyboardBottomSpacingPolicy.observe(false)
            34f
        }, 0f)
        KeyboardBottomSpacingPolicy.observe(false) // must not leak into the next scope
        assertEquals(34f, KeyboardBottomSpacingPolicy.calculate(80) { 34f }, 0f)
    }

    @Test fun readsFromOtherThreadsCannotSelectTheLayout() {
        assertEquals(34f, KeyboardBottomSpacingPolicy.calculate(80) {
            Thread { KeyboardBottomSpacingPolicy.observe(false) }.apply { start(); join() }
            34f
        }, 0f)
    }
}
