package io.mo.xatype.hooks

import android.annotation.SuppressLint
import android.graphics.Outline
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import io.mo.xatype.config.ConfigManager
import java.util.WeakHashMap

/** Clip IME window pixels; CompositorGlassSurface separately rounds its blur region. */
internal object ImeWindowCornerGuard {
    private val tracked = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    fun track(decor: View) {
        if (tracked.containsKey(decor)) return
        var lastGeometry: Triple<Int, Int, Float>? = null
        val listener = ViewTreeObserver.OnPreDrawListener {
            val geometry = Triple(decor.width, decor.height, bottomRadius(decor))
            if (geometry != lastGeometry) {
                lastGeometry = geometry
                decor.invalidateOutline()
            }
            true
        }
        tracked[decor] = listener
        val originalProvider = decor.outlineProvider
        val originalClip = decor.clipToOutline
        decor.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = bottomRadius(view)
                if (radius > 0f) outline.setRoundRect(0, -kotlin.math.ceil(radius.toDouble()).toInt(),
                    view.width, view.height, radius)
                else outline.setRect(0, 0, view.width, view.height)
            }
        }
        decor.clipToOutline = true
        decor.viewTreeObserver.addOnPreDrawListener(listener)
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
                tracked.remove(view)
                view.outlineProvider = originalProvider
                view.clipToOutline = originalClip
                view.removeOnAttachStateChangeListener(this)
            }
        })
    }

    @SuppressLint("DiscouragedApi") // Framework dimensions are a fallback for IME insets without corners.
    fun bottomRadius(decor: View): Float {
        if (!ConfigManager.isStyleEnabled()) return 0f
        if (Build.VERSION.SDK_INT >= 31) {
            val insets = decor.rootWindowInsets
            val left = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            val right = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
            if (left > 0 || right > 0) return maxOf(left, right).toFloat()
        }
        val resources = decor.resources
        for (name in listOf("rounded_corner_radius_bottom", "rounded_corner_radius")) {
            val id = resources.getIdentifier(name, "dimen", "android")
            if (id != 0) resources.getDimension(id).takeIf { it > 0f }?.let { return it }
        }
        return 0f
    }

}
