package io.mo.xatype.keyboard

/** Share screen coordinates to keep labels aligned through native padding, resizing and scaling. */
internal object ShortcutLabelPosition {
    fun centerFraction(left: Float, right: Float, letterCenter: Float?): Float {
        val width = right - left
        if (!left.isFinite() || !width.isFinite() || width <= 0f ||
            letterCenter == null || !letterCenter.isFinite() || letterCenter !in left..right) return 0.5f
        return (letterCenter - left) / width
    }
}
