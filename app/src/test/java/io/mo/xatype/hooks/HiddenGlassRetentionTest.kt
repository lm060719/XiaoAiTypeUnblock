package io.mo.xatype.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenGlassRetentionTest {
    private val retention = HiddenGlassRetention()
    private val helper = Any()
    private val material = Any()

    private fun preserve(
        targetHelper: Any = helper,
        targetMaterial: Any? = material,
        enabled: Boolean = true,
        attached: Boolean = true,
        owned: Boolean = true
    ) = retention.shouldPreserve(targetHelper, targetMaterial, enabled, attached, owned)

    @Test fun delayedTeardownKeepsDetachCleanupAndHideProtectedAfterHideReturns() {
        fun onWindowHidden() = retention.retain(helper, material)
        onWindowHidden()
        // Host invokes all three operations in a later Handler callback. The
        // first check must not consume protection needed by the next two.
        repeat(3) { assertTrue(preserve()) }
    }

    @Test fun nextShowRebuildOrDestroyAllowsNormalCleanup() {
        retention.retain(helper, material)
        retention.release()
        assertFalse(preserve())
        // Repeated lifecycle cleanup is safe, and a later hide can retain again.
        retention.release()
        retention.retain(helper, material)
        assertTrue(preserve())
    }

    @Test fun unrelatedHelperDoesNotInheritOrConsumeProtection() {
        retention.retain(helper, material)
        assertFalse(preserve(targetHelper = Any()))
        assertTrue(preserve())
    }

    @Test fun replacementMaterialInvalidatesOldProtection() {
        retention.retain(helper, material)
        assertFalse(preserve(targetMaterial = Any()))
        assertFalse(preserve())
    }

    @Test fun missingMaterialInvalidatesOldProtection() {
        retention.retain(helper, material)
        assertFalse(preserve(targetMaterial = null))
        assertFalse(preserve())
    }

    @Test fun disabledGlassDetachedViewAndLostSurfaceReleaseProtection() {
        for (reason in 0..2) {
            retention.retain(helper, material)
            assertFalse(preserve(enabled = reason != 0, attached = reason != 1, owned = reason != 2))
            assertFalse(preserve())
        }
    }

    @Test fun newWindowReplacesRetainedOwner() {
        val nextHelper = Any()
        val nextMaterial = Any()
        retention.retain(helper, material)
        retention.retain(nextHelper, nextMaterial)
        assertFalse(preserve())
        assertTrue(preserve(targetHelper = nextHelper, targetMaterial = nextMaterial))
    }
}
