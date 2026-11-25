package io.mo.xatype.hooks

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import io.mo.xatype.config.ConfigManager
import java.util.WeakHashMap

/** Decorates the host's own material without changing its palette, alpha or blur. */
internal object OfficialAppearance {
    private data class Geometry(
        val left: Int, val top: Int, val width: Int, val height: Int, val radius: Float
    )

    private class State(
        val foreground: Drawable?, var roundBottom: Boolean, val outlineSource: View
    ) {
        var rim: GlassRim.Spec? = null
        var appliedForeground: Drawable? = foreground
        var geometry: Geometry? = null
        lateinit var attachListener: View.OnAttachStateChangeListener
    }

    private val tracked = WeakHashMap<View, State>()

    fun clear() {
        tracked.toList().forEach { (view, state) -> state.attachListener.onViewDetachedFromWindow(view) }
    }

    fun apply(material: View, roundBottom: Boolean = false) = track(material, material, roundBottom)

    // The host draws its top hairline in a sibling view, sometimes with an outset.
    // Clip it in the material's coordinate space so a rectangular line cannot cross the corners.
    fun clipDockedEdge(edge: View, material: View) = track(edge, material, roundBottom = false)

    private fun track(view: View, outlineSource: View, roundBottom: Boolean) {
        val drawsRim = view === outlineSource
        fun enabled() = if (drawsRim) ConfigManager.isOfficialAppearance()
            else ConfigManager.isAppearanceEnabled()
        if (!enabled()) return
        tracked[view]?.let {
            if (it.outlineSource === outlineSource) {
                if (it.roundBottom != roundBottom) {
                    it.roundBottom = roundBottom
                    view.invalidateOutline()
                }
                return
            }
            it.attachListener.onViewDetachedFromWindow(view)
        }
        val state = State(view.foreground, roundBottom, outlineSource)
        tracked[view] = state
        val originalProvider = view.outlineProvider
        val originalClip = view.clipToOutline
        val viewLocation = IntArray(2)
        val sourceLocation = IntArray(2)
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val geometry = state.geometry ?: return
                val (left, top, width, height, radius) = geometry
                KeyboardSurfaceOutline.set(outline, left, top, left + width, top + height,
                    radius, state.roundBottom)
            }
        }
        fun refresh() {
            if (!enabled()) {
                state.attachListener.onViewDetachedFromWindow(view)
                return
            }
            if (!drawsRim) {
                view.getLocationInWindow(viewLocation)
                outlineSource.getLocationInWindow(sourceLocation)
            }
            val radius = KeyboardSurfaceOutline.radius(outlineSource)
            val geometry = Geometry(
                sourceLocation[0] - viewLocation[0], sourceLocation[1] - viewLocation[1],
                outlineSource.width, outlineSource.height, radius
            )
            if (geometry != state.geometry) {
                state.geometry = geometry
                view.invalidateOutline()
            }
            if (drawsRim || radius > 0f) {
                if (view.outlineProvider !== provider) view.outlineProvider = provider
                if (!view.clipToOutline) view.clipToOutline = true
            } else if (view.outlineProvider === provider) {
                view.outlineProvider = originalProvider
                view.clipToOutline = originalClip
            }
            if (!drawsRim) return
            val rim = GlassRim.spec(view, state.roundBottom)
            if (rim != state.rim) {
                state.rim = rim
                val overlay = rim?.let { GlassRim.Overlay(null, it) }
                state.appliedForeground = when {
                    overlay == null -> state.foreground
                    state.foreground == null -> overlay
                    else -> LayerDrawable(arrayOf(state.foreground, overlay))
                }
            }
            if (view.foreground !== state.appliedForeground) view.foreground = state.appliedForeground
        }
        val listener = ViewTreeObserver.OnPreDrawListener { refresh(); true }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        state.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
                tracked.remove(view)
                if (view.outlineProvider === provider) {
                    view.outlineProvider = originalProvider
                    view.clipToOutline = originalClip
                }
                if (drawsRim && view.foreground === state.appliedForeground) view.foreground = state.foreground
                view.removeOnAttachStateChangeListener(this)
            }
        }
        view.addOnAttachStateChangeListener(state.attachListener)
        refresh()
    }
}
