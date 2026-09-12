package io.mo.xatype.hooks

/** Change alpha without replacing the keyboard's RGB values or color space. */
internal object BackgroundOpacity {
    fun argb(color: Int, percent: Int): Int {
        val alpha = (color ushr 24) * percent.coerceIn(0, 100) / 100
        return (color and 0x00ffffff) or (alpha shl 24)
    }

    fun compose(color: Long, percent: Int): Long {
        // Compose sRGB uses ARGB in the high word. Other spaces encode a
        // 10-bit alpha at bits 6..15; leave RGB and the space identifier intact.
        if (color and 0xffffffffL == 0L) {
            return (argb((color ushr 32).toInt(), percent).toLong() and 0xffffffffL) shl 32
        }
        if (color == 0x10L) return color // Color.Unspecified
        val alpha = ((color ushr 6) and 0x3ffL) * percent.coerceIn(0, 100) / 100
        return (color and (0x3ffL shl 6).inv()) or (alpha shl 6)
    }
}
