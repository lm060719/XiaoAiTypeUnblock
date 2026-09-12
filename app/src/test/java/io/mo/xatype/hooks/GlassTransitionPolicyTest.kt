package io.mo.xatype.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTransitionPolicyTest {
    @Test fun middleOpacityUsesTheSameFirstFrameProtectionAsTransparentGlass() {
        // The reported regression was at 42%; the old 10% cutoff left it on
        // the native blur/opaque navigation fallback during the show animation.
        for (opacity in listOf(0, 10, 11, 42, 50, 85, 99)) {
            assertTrue("opacity=$opacity", GlassTransitionPolicy.usesCompositor(true, 0, opacity, 20))
        }
    }

    @Test fun opaqueAndNonGlassBackgroundsKeepTheirExistingRenderingPath() {
        assertFalse(GlassTransitionPolicy.usesCompositor(true, 0, 100, 20))
        assertFalse(GlassTransitionPolicy.usesCompositor(true, 1, 42, 20))
        assertFalse(GlassTransitionPolicy.usesCompositor(true, 2, 42, 20))
        assertFalse(GlassTransitionPolicy.usesCompositor(false, 0, 42, 20))
        assertFalse(GlassTransitionPolicy.usesCompositor(true, 0, 42, 0))
    }
}
