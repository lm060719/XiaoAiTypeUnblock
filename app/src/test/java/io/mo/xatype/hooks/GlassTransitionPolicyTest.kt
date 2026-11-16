package io.mo.xatype.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTransitionPolicyTest {
    @Test fun mainWindowKeepsBlurWhileItsViewHierarchyHidesBeforeTheExitLeash() {
        assertTrue(GlassTransitionPolicy.showSurface(false, true, true, 1080, 800))
        // hideWindow has hidden the decor, but WM is still animating its surface.
        assertTrue(GlassTransitionPolicy.showSurface(false, true, false, 1080, 800))
    }

    @Test fun hiddenPopupAndExplicitlyHiddenMaterialDoNotLeaveBlurBehind() {
        assertTrue(GlassTransitionPolicy.showSurface(true, true, true, 800, 400))
        assertFalse(GlassTransitionPolicy.showSurface(true, true, false, 800, 400))
        assertFalse(GlassTransitionPolicy.showSurface(false, false, false, 1080, 800))
        assertFalse(GlassTransitionPolicy.showSurface(false, true, false, 0, 800))
        assertFalse(GlassTransitionPolicy.showSurface(false, true, false, 1080, 0))
    }

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
