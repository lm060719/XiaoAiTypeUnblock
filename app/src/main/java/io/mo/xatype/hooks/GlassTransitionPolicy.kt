package io.mo.xatype.hooks

/** Every translucent native background needs blur before its first visible frame. */
internal object GlassTransitionPolicy {
    fun usesCompositor(enabled: Boolean, backgroundType: Int, opacity: Int, blurRadius: Int): Boolean =
        enabled && backgroundType == 0 && opacity.coerceIn(0, 100) < 100 && blurRadius > 0

    /** The main IME surface is hidden/moved by WM after its exit animation.
     * Ancestor View visibility can change before that animation finishes.
     * Popups still follow their own hierarchy visibility.
     */
    fun showSurface(popup: Boolean, materialVisible: Boolean, hierarchyShown: Boolean,
                    width: Int, height: Int): Boolean =
        width > 0 && height > 0 && materialVisible && (!popup || hierarchyShown)
}
