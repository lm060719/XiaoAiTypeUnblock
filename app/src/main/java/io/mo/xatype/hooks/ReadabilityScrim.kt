package io.mo.xatype.hooks

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import io.mo.xatype.config.ConfigManager

/**
 * A tint layer kept under the keyboard background. Labels, candidates and
 * toolbar icons then keep contrast whatever app content shows through glass.
 * An opaque background covers it completely, so only translucent setups change.
 */
internal object ReadabilityScrim {
    const val DARK = 0xff121212.toInt()
    const val LIGHT = 0xfff5f5f5.toInt()

    /** Light labels get a dark scrim and dark labels a light one. */
    fun autoColor(textColor: Int?, surfaceDark: Boolean): Int {
        val lightText = textColor?.let { luma(it) >= 128 } ?: surfaceDark
        return if (lightText) DARK else LIGHT
    }

    /** [base] composited over [scrim] at [percent] opacity (source-over). */
    fun under(base: Int, scrim: Int, percent: Int): Int {
        val sa = (scrim ushr 24) * percent.coerceIn(0, 100) / 100
        if (sa == 0) return base
        val ba = base ushr 24
        val sw = sa * (255 - ba) / 255
        val oa = ba + sw
        if (oa == 0) return base
        fun channel(shift: Int): Int =
            (((base shr shift) and 0xff) * ba + ((scrim shr shift) and 0xff) * sw + oa / 2) / oa
        return (oa shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun luma(color: Int): Int =
        (299 * ((color shr 16) and 0xff) + 587 * ((color shr 8) and 0xff) + 114 * (color and 0xff)) / 1000

    /** The configured scrim with its alpha applied, or null when disabled. */
    fun current(context: Context): Int? {
        if (!ConfigManager.isStyleEnabled() || !ConfigManager.isScrimEnabled()) return null
        val percent = ConfigManager.getScrimOpacity()
        if (percent == 0) return null
        val custom = parse(ConfigManager.getScrimColor())
        val rgb = custom ?: run {
            val surfaceDark = if (ConfigManager.getBgType() == 1) {
                // Matches the automatic label contrast used for solid backgrounds.
                parse(ConfigManager.getBgColor())?.let { luma(it) < 150 } ?: true
            } else {
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            autoColor(parse(ConfigManager.getTextColor()), surfaceDark)
        }
        return BackgroundOpacity.argb(rgb or (0xff shl 24), percent)
    }

    fun apply(context: Context, base: Int): Int =
        current(context)?.let { under(base, it, 100) } ?: base

    /** Image backgrounds keep their own alpha; the scrim sits beneath the bitmap. */
    fun behind(context: Context, drawable: Drawable): Drawable =
        current(context)?.let { LayerDrawable(arrayOf(ColorDrawable(it), drawable)) } ?: drawable

    /**
     * Dynamic glass: Xiaomi's material apply clears the material background
     * about 20 ms later, so the scrim lives in its foreground instead. The
     * material sits below the key layer, so labels still draw above it.
     */
    fun overlayGlass(material: View) {
        fun apply() {
            material.foreground = if (ConfigManager.getBgType() == 0) {
                current(material.context)?.let { ColorDrawable(it) }
            } else null
        }
        apply()
        material.postDelayed(::apply, 48L)
    }

    private fun parse(value: String): Int? = value.takeIf { it.isNotBlank() }?.let {
        try {
            Color.parseColor(it)
        } catch (_: Throwable) {
            null
        }
    }
}
