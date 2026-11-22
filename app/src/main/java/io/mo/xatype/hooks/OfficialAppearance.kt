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
    private class State(val foreground: Drawable?, var roundBottom: Boolean) {
        var rim: GlassRim.Spec? = null
        var appliedForeground: Drawable? = foreground
        var geometry: Triple<Int, Int, Float>? = null
        lateinit var attachListener: View.OnAttachStateChangeListener
    }

    private val tracked = WeakHashMap<View, State>()

    fun clear() {
        tracked.toList().forEach { (view, state) -> state.attachListener.onViewDetachedFromWindow(view) }
    }

    fun apply(material: View, roundBottom: Boolean = false) {
        if (!ConfigManager.isOfficialAppearance()) return
        tracked[material]?.let {
            if (it.roundBottom != roundBottom) {
                it.roundBottom = roundBottom
                material.invalidateOutline()
            }
            return
        }
        val state = State(material.foreground, roundBottom)
        tracked[material] = state
        val originalProvider = material.outlineProvider
        val originalClip = material.clipToOutline
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = ConfigManager.getCornerRadius().coerceAtLeast(0) *
                    view.resources.displayMetrics.density
                if (radius > 0f) outline.setRoundRect(0, 0, view.width,
                    view.height + if (state.roundBottom) 0 else kotlin.math.ceil(radius.toDouble()).toInt(), radius)
                else outline.setRect(0, 0, view.width, view.height)
            }
        }
        fun refresh() {
            if (!ConfigManager.isOfficialAppearance()) return
            if (material.outlineProvider !== provider) material.outlineProvider = provider
            if (!material.clipToOutline) material.clipToOutline = true
            val geometry = Triple(material.width, material.height,
                ConfigManager.getCornerRadius().toFloat())
            if (geometry != state.geometry) {
                state.geometry = geometry
                material.invalidateOutline()
            }
            val rim = GlassRim.spec(material, state.roundBottom)
            if (rim != state.rim) {
                state.rim = rim
                val overlay = rim?.let { GlassRim.Overlay(null, it) }
                state.appliedForeground = when {
                    overlay == null -> state.foreground
                    state.foreground == null -> overlay
                    else -> LayerDrawable(arrayOf(state.foreground, overlay))
                }
            }
            if (material.foreground !== state.appliedForeground) material.foreground = state.appliedForeground
        }
        val listener = ViewTreeObserver.OnPreDrawListener { refresh(); true }
        material.viewTreeObserver.addOnPreDrawListener(listener)
        state.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
                tracked.remove(view)
                if (view.outlineProvider === provider) {
                    view.outlineProvider = originalProvider
                    view.clipToOutline = originalClip
                }
                if (view.foreground === state.appliedForeground) view.foreground = state.foreground
                view.removeOnAttachStateChangeListener(this)
            }
        }
        material.addOnAttachStateChangeListener(state.attachListener)
        refresh()
    }
}
