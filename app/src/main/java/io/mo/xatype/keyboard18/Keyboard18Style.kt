package io.mo.xatype.keyboard18

/** Shared key geometry and typography for the native layout and settings preview. */
object Keyboard18Style {
    const val SINGLE_WEIGHT = 1f
    /** Equal-width keys: the 7/6/5 rows share one grid instead of widening paired letters. */
    const val PAIR_WEIGHT = 1f
    const val CONTROL_WEIGHT = 1f
    const val FALLBACK_SPACING = 5f
    const val MAIN_TEXT_SCALE = 0.9f
    const val PREVIEW_TEXT_SIZE = 18f
    const val PREVIEW_HINT_SCALE = 0.55f
    const val PREVIEW_RADIUS = 8
    const val PREVIEW_GAP = 2
    private const val LETTER_SEPARATOR = "\u2009"

    fun weight(group: String) = if (group.length == 2) PAIR_WEIGHT else SINGLE_WEIGHT
    fun label(group: String, uppercase: Boolean = false) =
        (if (uppercase) group.uppercase() else group).map(Char::toString).joinToString(LETTER_SEPARATOR)

    // Compose TextUnit packs the unit tag above the raw float value. Retain the tag
    // and the host's font scaling rather than substituting a fixed pixel size.
    fun scaleTextUnit(value: Long, scale: Float = MAIN_TEXT_SCALE): Long {
        val size = Float.fromBits(value.toInt())
        if (!size.isFinite() || size <= 0f) return value
        return (value and -0x100000000L) or ((size * scale).toRawBits().toLong() and 0xffffffffL)
    }
}
