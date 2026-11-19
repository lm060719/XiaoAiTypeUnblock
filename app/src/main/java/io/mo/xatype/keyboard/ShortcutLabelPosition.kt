package io.mo.xatype.keyboard

/** Share screen coordinates to keep labels aligned through native padding, resizing and scaling. */
internal object ShortcutLabelPosition {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class Placement(val x: Float, val baseline: Float)

    fun centerFraction(left: Float, right: Float, letterCenter: Float?): Float {
        val width = right - left
        if (!left.isFinite() || !width.isFinite() || width <= 0f ||
            letterCenter == null || !letterCenter.isFinite() || letterCenter !in left..right) return 0.5f
        return (letterCenter - left) / width
    }

    fun portraitPlacement(width: Float, height: Float, centerFraction: Float, halfLabelWidth: Float,
                          ascent: Float, offsetX: Float, bottomInset: Float): Placement {
        val half = halfLabelWidth.coerceAtMost(width / 2f)
        val x = (width * centerFraction + offsetX).coerceIn(half, width - half)
        val maxBaseline = height - 1f
        val minBaseline = minOf(-ascent + 1f, maxBaseline)
        return Placement(x, (height - bottomInset).coerceIn(minBaseline, maxBaseline))
    }

    fun place(width: Float, height: Float, letter: Bounds?, label: Bounds, offsetX: Float,
              bottomInset: Float, edgeInset: Float, gap: Float): Placement? {
        val center = letter?.let { (it.left + it.right) / 2f } ?: width / 2f
        // Edge keys can absorb an adjacent touch gap. Mirror the nearer edge about
        // the native letter, using up to one inset of tolerance for alignment rounding.
        val keyLeft = (2f * center - width - edgeInset).coerceAtLeast(0f)
        val keyRight = (2f * center + edgeInset).coerceAtMost(width)
        val minX = keyLeft + edgeInset - label.left
        val maxX = keyRight - edgeInset - label.right
        val minBaseline = edgeInset - label.top
        val maxBaseline = height - edgeInset - label.bottom
        if (minX > maxX || minBaseline > maxBaseline) return null

        fun fits(x: Float, baseline: Float): Boolean {
            if (letter == null) return true
            return baseline + label.top >= letter.bottom + gap ||
                baseline + label.bottom <= letter.top - gap ||
                x + label.left >= letter.right + gap ||
                x + label.right <= letter.left - gap
        }

        val x = (center + offsetX).coerceIn(minX, maxX)
        val baseline = (height - bottomInset).coerceIn(minBaseline, maxBaseline)
        if (fits(x, baseline)) return Placement(x, baseline)
        if (fits(x, maxBaseline)) return Placement(x, maxBaseline)
        if (fits(maxX, maxBaseline)) return Placement(maxX, maxBaseline)
        if (fits(minX, maxBaseline)) return Placement(minX, maxBaseline)
        return null
    }
}
