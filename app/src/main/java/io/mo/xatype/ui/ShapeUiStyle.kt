package io.mo.xatype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.mo.xatype.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Page tokens, using the module's palette with Android 12 wallpaper accents when available. */
internal class ShapeUiStyle(val context: Context) {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    val background = color(R.color.shape_background)
    val surface = color(R.color.shape_surface)
    val primary = color(R.color.shape_primary)
    val secondary = color(R.color.shape_secondary)
    val outline = color(R.color.shape_outline)
    val errorSurface = color(R.color.shape_error_surface)
    val errorText = color(R.color.shape_error_text)
    val accent = if (Build.VERSION.SDK_INT >= 31) color(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        else color(R.color.shape_accent)
    val accentText = if (Build.VERSION.SDK_INT >= 31) accent else color(R.color.shape_accent_text)
    val accentSurface = if (Build.VERSION.SDK_INT >= 31) color(if (dark) android.R.color.system_accent1_900 else android.R.color.system_accent1_50)
        else color(R.color.shape_accent_surface)
    val onAccent = if (Build.VERSION.SDK_INT >= 31) color(if (dark) android.R.color.system_accent1_900 else android.R.color.white)
        else color(R.color.shape_on_accent)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(color: Int = surface, radius: Int = CARD_RADIUS): Drawable = SmoothSurface(color, dp(radius).toFloat())
    fun ripple(color: Int = surface, radius: Int = CARD_RADIUS): Drawable = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, RIPPLE_ALPHA)),
        surface(color, radius), surface(android.graphics.Color.WHITE, radius))

    companion object {
        const val XS = 4
        const val SM = 8
        const val MD = 12
        const val LG = 16
        const val XL = 24
        const val XXL = 32
        const val TOUCH = 48
        const val BUTTON = 56
        const val ICON = 24
        const val ICON_TILE = 48
        const val SWITCH_WIDTH = 60
        const val MAX_WIDTH = 720
        const val CARD_RADIUS = 28
        const val SMALL_RADIUS = 12
        const val TITLE = 24f
        const val HEADING = 17f
        const val BODY = 15f
        const val CAPTION = 13f
        const val METRIC = 25f
        const val RIPPLE_ALPHA = 28
        const val DISABLED_ALPHA = 0.45f
        const val CORNER_SEGMENTS = 24
        const val CORNER_POWER = 0.5f
    }
}

/** Cached superellipse path; large surfaces meet their straight edges with smooth corners. */
internal class SmoothSurface(color: Int, private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()
    override fun onBoundsChange(bounds: Rect) {
        path.reset()
        val r = min(radius, min(bounds.width(), bounds.height()) / 2f)
        val centers = listOf(bounds.right - r to bounds.top + r, bounds.left + r to bounds.top + r,
            bounds.left + r to bounds.bottom - r, bounds.right - r to bounds.bottom - r)
        fun smooth(value: Float) = sign(value) * abs(value).pow(ShapeUiStyle.CORNER_POWER)
        centers.forEachIndexed { corner, (cx, cy) ->
            for (step in 0..ShapeUiStyle.CORNER_SEGMENTS) {
                val angle = (corner + step.toFloat() / ShapeUiStyle.CORNER_SEGMENTS) * (PI.toFloat() / 2f)
                val x = cx + r * smooth(cos(angle))
                val y = cy - r * smooth(sin(angle))
                if (corner == 0 && step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        path.close()
    }
    override fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is unused")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
