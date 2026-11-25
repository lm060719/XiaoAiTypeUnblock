package io.mo.xatype.hooks

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import io.mo.xatype.config.ConfigManager
import kotlin.math.ceil

/** The panel boundary shared by the background, native edge and compositor blur. */
internal object KeyboardSurfaceOutline {
    fun radius(view: View): Float =
        ConfigManager.getCornerRadius().coerceAtLeast(0) * view.resources.displayMetrics.density

    fun set(
        outline: Outline, left: Int, top: Int, right: Int, bottom: Int,
        radius: Float, roundBottom: Boolean
    ) {
        if (right <= left || bottom <= top) return
        if (radius <= 0f) {
            outline.setRect(left, top, right, bottom)
        } else {
            val overshoot = if (roundBottom) 0 else ceil(radius.toDouble()).toInt()
            outline.setRoundRect(left, top, right, bottom + overshoot, radius)
        }
    }

    fun apply(view: View, radius: Float, roundBottom: Boolean = false) {
        if (radius <= 0f) {
            view.clipToOutline = false
            return
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                set(outline, 0, 0, view.width, view.height, radius, roundBottom)
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()
    }
}
