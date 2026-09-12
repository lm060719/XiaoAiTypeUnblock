package io.mo.xatype.hooks

/** Every translucent native background needs blur before its first visible frame. */
internal object GlassTransitionPolicy {
    fun usesCompositor(enabled: Boolean, backgroundType: Int, opacity: Int, blurRadius: Int): Boolean =
        enabled && backgroundType == 0 && opacity.coerceIn(0, 100) < 100 && blurRadius > 0
}
