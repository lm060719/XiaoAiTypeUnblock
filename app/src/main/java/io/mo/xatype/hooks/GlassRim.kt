package io.mo.xatype.hooks

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.View
import io.mo.xatype.config.ConfigManager
import kotlin.math.cos

/**
 * A thin specular rim on the keyboard card, like the edge of a glass panel
 * lit from one side: light falls from the upper left, so the top-left corner
 * is brightest, the top edge stays bright, the right side is dimmest, and the
 * opposite (lower right) edge catches a weaker reflection through the glass.
 * Light surfaces get a faint dark hairline outside the highlight, since white
 * alone does not separate a pale keyboard from pale app content.
 */
internal object GlassRim {
    const val DEFAULT_INTENSITY = 60

    /**
     * The outward edge normal that faces the light, in degrees counter-clockwise
     * from the right with y up: upper left, a little above the diagonal.
     */
    const val LIGHT_ANGLE = 120f
    val LIGHT_NAMES = listOf("左上", "正上", "右上")

    /** [ConfigManager.KEY_RIM_LIGHT] as a light angle: upper left, top, or the mirrored upper right. */
    fun lightAngle(direction: Int): Float = when (direction) {
        1 -> 90f
        2 -> 180f - LIGHT_ANGLE
        else -> LIGHT_ANGLE
    }

    private const val AMBIENT = 0.13f
    private const val COUNTER_REFLECTION = 0.4f

    /**
     * Rim brightness, 0..1, for an edge whose outward normal points at
     * [normalDegrees] (same convention as [LIGHT_ANGLE]). Fitted to a glass
     * card lit from the upper left: 1 facing the light, about 0.78 on the top
     * edge, 0.34 on the left, 0.47 at the opposite corner, 0.12 side-on.
     */
    fun lightWeight(normalDegrees: Float, lightAngle: Float = LIGHT_ANGLE): Float {
        val c = cos(Math.toRadians((normalDegrees - lightAngle).toDouble())).toFloat()
        val key = if (c > 0f) c * c else 0f
        val back = if (c < 0f) c * c else 0f
        return (AMBIENT + key + COUNTER_REFLECTION * back) / (1f + AMBIENT)
    }

    data class Spec(
        val intensity: Int,
        val radiusPx: Float,
        /** Floating cards round and outline all four corners; docked ones run off the bottom. */
        val roundBottom: Boolean,
        val surfaceDark: Boolean,
        val density: Float,
        val lightAngle: Float = LIGHT_ANGLE
    )

    /** White highlight alpha. A light surface needs more of it to read. */
    fun highlightAlpha(intensity: Int, surfaceDark: Boolean): Int =
        scaled(intensity, if (surfaceDark) 190 else 255)

    /** Dark outer hairline, for light surfaces only. */
    fun hairlineAlpha(intensity: Int, surfaceDark: Boolean): Int =
        if (surfaceDark) 0 else scaled(intensity, 40)

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
            density,
            lightAngle(ConfigManager.getRimLight())
        )
    }

    /** Draws the rim inside a card's bounds. Not thread-safe; keep one per drawing owner. */
    class Painter {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        private val oval = RectF()
        private val sweepPositions = FloatArray(SWEEP_STOPS) { it / (SWEEP_STOPS - 1f) }
        private val sweepWeights = FloatArray(SWEEP_STOPS)
        private var sweepLight = Float.NaN

        fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, spec: Spec) {
            val width = right - left
            val height = bottom - top
            if (width <= 0f || height <= 0f || spec.intensity <= 0) return
            val d = spec.density
            val radius = spec.radiusPx.coerceIn(0f, if (spec.roundBottom) minOf(width, height) / 2 else width / 2)
            // A docked card continues below the window: keep its bottom edge out of view.
            val overshoot = if (spec.roundBottom) 0f else radius + 4 * d
            val ring = Ring(left, top, right, bottom, overshoot, radius, spec)
            if (spec.lightAngle != sweepLight) {
                sweepLight = spec.lightAngle
                // Android sweeps clockwise from 3 o'clock with y down; the light angle runs counter-clockwise.
                for (i in sweepWeights.indices) sweepWeights[i] = lightWeight(360f - 360f * sweepPositions[i], sweepLight)
            }

            var inset = 0f
            val hairline = hairlineAlpha(spec.intensity, spec.surfaceDark)
            if (hairline > 0) {
                val lineWidth = maxOf(0.6f * d, 1f)
                strokeRing(canvas, ring, inset, lineWidth, hairline, black = true, lit = false)
                inset += lineWidth
            }
            // A bright core with a soft falloff inside it, as a lit glass edge reads.
            val highlight = highlightAlpha(spec.intensity, spec.surfaceDark)
            val core = maxOf(d, 1f)
            strokeRing(canvas, ring, inset, core, highlight, black = false, lit = true)
            strokeRing(canvas, ring, inset + core, 0.8f * d, (highlight * 0.45f).toInt(), black = false, lit = true)
            strokeRing(canvas, ring, inset + core + 0.8f * d, d, (highlight * 0.18f).toInt(), black = false, lit = true)
        }

        private class Ring(
            val left: Float, val top: Float, val right: Float, val bottom: Float,
            val overshoot: Float, val radius: Float, val spec: Spec
        )

        /**
         * One concentric band of the rim, [inset] in from the card edge. Straight
         * edges take the light of their fixed normal; corners sweep between them.
         */
        private fun strokeRing(
            canvas: Canvas, ring: Ring, inset: Float, lineWidth: Float,
            alpha: Int, black: Boolean, lit: Boolean
        ) {
            if (alpha <= 0 || lineWidth <= 0f) return
            val spec = ring.spec
            val center = inset + lineWidth / 2
            val l = ring.left + center
            val t = ring.top + center
            val r = ring.right - center
            val b = if (spec.roundBottom) ring.bottom - center else ring.bottom + ring.overshoot
            if (r <= l || b <= t) return
            val rr = (ring.radius - center).coerceIn(0f, minOf(r - l, b - t) / 2)
            // Square corners: let the horizontal edges cover the corner pixels.
            val ext = if (rr <= 0f) lineWidth / 2 else 0f
            val light = spec.lightAngle
            fun color(weight: Float): Int {
                val a = (alpha * (if (lit) weight else 1f) + 0.5f).toInt().coerceIn(0, 255)
                return if (black) a shl 24 else white(a)
            }
            stroke.strokeWidth = lineWidth

            stroke.shader = null
            stroke.color = color(lightWeight(90f, light))
            canvas.drawLine(l + rr - ext, t, r - rr + ext, t, stroke)
            if (spec.roundBottom) {
                stroke.color = color(lightWeight(270f, light))
                canvas.drawLine(l + rr - ext, b, r - rr + ext, b, stroke)
            }

            val sideTop = t + rr
            if (spec.roundBottom) {
                val sideBottom = b - rr
                if (sideBottom > sideTop) {
                    stroke.color = color(lightWeight(180f, light))
                    canvas.drawLine(l, sideTop, l, sideBottom, stroke)
                    stroke.color = color(lightWeight(0f, light))
                    canvas.drawLine(r, sideTop, r, sideBottom, stroke)
                }
            } else {
                // Docked sides lie on the screen edge; let them go after the corner.
                val fadeEnd = (ring.top + ring.radius + 40 * spec.density).coerceAtLeast(sideTop + 1f)
                for ((x, normal) in listOf(l to 180f, r to 0f)) {
                    val c = color(lightWeight(normal, light))
                    stroke.color = OPAQUE
                    stroke.shader = LinearGradient(0f, sideTop, 0f, fadeEnd, c, c and 0x00ffffff, Shader.TileMode.CLAMP)
                    canvas.drawLine(x, sideTop, x, fadeEnd, stroke)
                }
                stroke.shader = null
            }

            if (rr > 0f) {
                val colors = if (lit) IntArray(SWEEP_STOPS) { color(sweepWeights[it]) } else null
                fun corner(cx: Float, cy: Float, start: Float) {
                    oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
                    if (colors != null) {
                        stroke.color = OPAQUE
                        stroke.shader = SweepGradient(cx, cy, colors, sweepPositions)
                    } else {
                        stroke.shader = null
                        stroke.color = color(1f)
                    }
                    canvas.drawArc(oval, start, 90f, false, stroke)
                }
                corner(l + rr, t + rr, 180f)
                corner(r - rr, t + rr, 270f)
                if (spec.roundBottom) {
                    corner(r - rr, b - rr, 0f)
                    corner(l + rr, b - rr, 90f)
                }
                stroke.shader = null
            }
        }

        private fun white(alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or 0xffffff

        private companion object {
            const val SWEEP_STOPS = 37
            const val OPAQUE = -0x1000000
        }
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
