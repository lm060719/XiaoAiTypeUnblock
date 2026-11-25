package io.mo.xatype.hooks

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import io.mo.xatype.config.ConfigManager
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.sqrt

/** Keep the IME buffer inside the panel and display corners. */
internal object ImeWindowCornerGuard {
    private class WindowState(val listener: View.OnAttachStateChangeListener) {
        var material: WeakReference<View>? = null
    }

    private val tracked = WeakHashMap<View, WindowState>()

    fun clear() {
        tracked.toList().forEach { (view, state) -> state.listener.onViewDetachedFromWindow(view) }
    }

    fun track(decor: View, material: View) {
        if (material.rootView !== decor) return
        track(decor)
        tracked[decor]?.material = WeakReference(material)
    }

    fun track(decor: View) {
        if (tracked.containsKey(decor)) return
        val topMask = TopCornerMask()
        decor.overlay.add(topMask)
        val decorLocation = IntArray(2)
        val materialLocation = IntArray(2)
        var lastGeometry: Triple<Int, Int, Float>? = null
        val listener = ViewTreeObserver.OnPreDrawListener {
            val geometry = Triple(decor.width, decor.height, bottomRadius(decor))
            if (geometry != lastGeometry) {
                lastGeometry = geometry
                decor.invalidateOutline()
            }
            val material = tracked[decor]?.material?.get()
            if (ConfigManager.isAppearanceEnabled() && material?.isShown == true &&
                material.rootView === decor) {
                decor.getLocationInWindow(decorLocation)
                material.getLocationInWindow(materialLocation)
                val left = materialLocation[0] - decorLocation[0]
                val top = materialLocation[1] - decorLocation[1]
                val radius = KeyboardSurfaceOutline.radius(material)
                    .coerceAtMost(minOf(material.width / 2f, material.height.toFloat()))
                topMask.update(left, top, material.width, radius)
            } else {
                topMask.update(0, 0, 0, 0f)
            }
            true
        }
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
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
                view.overlay.remove(topMask)
                tracked.remove(view)
                view.outlineProvider = originalProvider
                view.clipToOutline = originalClip
                view.removeOnAttachStateChangeListener(this)
            }
        }
        tracked[decor] = WindowState(attachListener)
        decor.addOnAttachStateChangeListener(attachListener)
    }

    @SuppressLint("DiscouragedApi") // Framework dimensions are a fallback for IME insets without corners.
    fun bottomRadius(decor: View): Float {
        if (!ConfigManager.isAppearanceEnabled()) return 0f
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

    /** Remove coverage beyond a one-pixel AA band after the window's views have drawn. */
    private class TopCornerMask : Drawable() {
        private val outside = Path()
        private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private var radius = 0f

        fun update(left: Int, top: Int, width: Int, radius: Float) {
            val bottom = top + ceil(radius.toDouble()).toInt()
            if (this.radius == radius && bounds.left == left && bounds.top == top &&
                bounds.width() == width) return
            this.radius = radius
            setBounds(left, top, left + width, bottom)
            outside.rewind()
            val outerRadius = radius + 1f
            for (row in 0 until bottom - top) {
                val dy = radius - row - 0.5f
                val extent = radius - sqrt(outerRadius * outerRadius - dy * dy)
                val count = ceil(extent - 0.5f).toInt()
                if (count <= 0) break
                val y = (top + row).toFloat()
                outside.addRect(left.toFloat(), y, (left + count).toFloat(), y + 1f, Path.Direction.CW)
                val right = left + width
                outside.addRect((right - count).toFloat(), y, right.toFloat(), y + 1f, Path.Direction.CW)
            }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (!outside.isEmpty) canvas.drawPath(outside, clear)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

}
