package io.mo.xatype.hooks

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import io.mo.xatype.config.ConfigManager

/**
 * A thin specular rim on the keyboard card, like the edge of a glass panel
 * catching light: brightest along the top edge and rounded corners, fading
 * down the sides. A floating card also reflects a little along its bottom.
 * Light surfaces get a faint dark hairline outside the highlight, since white
 * alone does not separate a pale keyboard from pale app content.
 */
internal object GlassRim {
    const val DEFAULT_INTENSITY = 60

    data class Spec(
        val intensity: Int,
        val radiusPx: Float,
        /** Floating cards round and outline all four corners; docked ones run off the bottom. */
        val roundBottom: Boolean,
        val surfaceDark: Boolean,
        val density: Float
    )

    /** White highlight alpha. A light surface needs more of it to read. */
    fun highlightAlpha(intensity: Int, surfaceDark: Boolean): Int =
        scaled(intensity, if (surfaceDark) 150 else 235)

    /** Dark outer hairline, for light surfaces only. */
    fun hairlineAlpha(intensity: Int, surfaceDark: Boolean): Int =
        if (surfaceDark) 0 else scaled(intensity, 40)

    /** Soft sheen just inside the top edge. */
    fun sheenAlpha(intensity: Int): Int = scaled(intensity, 22)

    private fun scaled(intensity: Int, max: Int): Int = (intensity.coerceIn(0, 100) * max + 50) / 100

    /** The rim for [view] under the current settings, or null when it is off. */
    fun spec(view: View, roundBottom: Boolean): Spec? {
        if (!ConfigManager.isStyleEnabled()) return null
        val intensity = ConfigManager.getRimHighlight()
        if (intensity <= 0) return null
        val density = view.resources.displayMetrics.density
        return Spec(
            intensity,
            ConfigManager.getCornerRadius().coerceAtLeast(0) * density,
            roundBottom,
            ReadabilityScrim.surfaceDark(view.context),
            density
        )
    }

    /** Draws the rim inside a card's bounds. Not thread-safe; keep one per drawing owner. */
    class Painter {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, spec: Spec) {
            val width = right - left
            val height = bottom - top
            if (width <= 0f || height <= 0f || spec.intensity <= 0) return
            val d = spec.density
            val radius = spec.radiusPx.coerceIn(0f, width / 2)
            // A docked card continues below the window: keep its bottom edge out of view.
            val overshoot = if (spec.roundBottom) 0f else radius + 4 * d

            val sheen = sheenAlpha(spec.intensity)
            if (sheen > 0) {
                val sheenHeight = minOf(18 * d, height * 0.3f)
                fill.shader = LinearGradient(0f, top, 0f, top + sheenHeight,
                    white(sheen), white(0), Shader.TileMode.CLAMP)
                rect.set(left, top, right, bottom + overshoot)
                canvas.drawRoundRect(rect, radius, radius, fill)
                fill.shader = null
            }

            var inset = 0f
            val hairline = hairlineAlpha(spec.intensity, spec.surfaceDark)
            if (hairline > 0) {
                val lineWidth = maxOf(0.6f * d, 1f)
                strokeRim(canvas, left, top, right, bottom, overshoot, radius, inset, lineWidth,
                    hairline, black = true, spec)
                inset += lineWidth
            }
            strokeRim(canvas, left, top, right, bottom, overshoot, radius, inset, maxOf(d, 1f),
                highlightAlpha(spec.intensity, spec.surfaceDark), black = false, spec)
        }

        private fun strokeRim(
            canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
            overshoot: Float, radius: Float, inset: Float, lineWidth: Float,
            alpha: Int, black: Boolean, spec: Spec
        ) {
            val center = inset + lineWidth / 2
            rect.set(left + center, top + center, right - center,
                if (spec.roundBottom) bottom - center else bottom + overshoot)
            if (rect.width() <= 0f || rect.height() <= 0f) return
            val height = bottom - top
            val cornerEnd = (radius / height).coerceIn(0f, 0.45f)
            val (positions, weights) = if (spec.roundBottom) {
                floatArrayOf(0f, cornerEnd, 0.5f, 1f - cornerEnd, 1f) to
                    floatArrayOf(1f, 0.8f, 0.22f, 0.4f, 0.5f)
            } else {
                // Docked sides lie on the screen edge; let them go after the corner.
                val fadeEnd = ((radius + 40 * spec.density) / height).coerceIn(cornerEnd + 0.01f, 1f)
                floatArrayOf(0f, cornerEnd, fadeEnd, 1f) to floatArrayOf(1f, 0.8f, 0f, 0f)
            }
            val colors = IntArray(weights.size) {
                val a = (alpha * weights[it] + 0.5f).toInt()
                if (black) a shl 24 else white(a)
            }
            stroke.strokeWidth = lineWidth
            stroke.shader = LinearGradient(0f, top, 0f, bottom, colors, positions, Shader.TileMode.CLAMP)
            val r = (radius - center).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, stroke)
            stroke.shader = null
        }

        private fun white(alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or 0xffffff
    }

    /**
     * The keyboard material's foreground: the glass readability scrim, when
     * there is one, then the rim. Equal settings compare equal, so a refresh
     * can keep the drawable already in place.
     */
    class Overlay(val scrim: Int?, val rim: Spec) : Drawable() {
        private val painter = Painter()
        private val scrimPaint = Paint().apply { color = scrim ?: 0 }

        fun matches(scrim: Int?, rim: Spec): Boolean = this.scrim == scrim && this.rim == rim

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            if (scrim != null) canvas.drawRect(b, scrimPaint)
            painter.draw(canvas, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), rim)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
