package io.mo.xatype.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils
import io.mo.xatype.R

/** Compact, accessible actions for the saved appearance list. */
@SuppressLint("ViewConstructor") // Created in code with its label, icon and action; never inflated from XML.
internal class SchemeActionButton(
    context: Context,
    label: String,
    iconRes: Int,
    tone: Tone = Tone.NEUTRAL,
    onClick: () -> Unit
) : AppCompatButton(context) {
    enum class Tone { PRIMARY, NEUTRAL, DESTRUCTIVE }

    private var pressSpring: SpringAnimator? = null

    init {
        val kit = SettingsKit(context)
        val (surfaceRes, textRes) = when (tone) {
            Tone.PRIMARY -> R.color.primary to R.color.on_primary
            Tone.NEUTRAL -> R.color.scheme_action_surface to R.color.text_primary
            Tone.DESTRUCTIVE -> R.color.scheme_delete_surface to R.color.scheme_delete_text
        }
        val surface = kit.color(surfaceRes)
        val foreground = kit.color(textRes)
        val radius = resources.getDimension(R.dimen.scheme_action_radius)
        backgroundTintList = null
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(foreground, ShapeUiStyle.RIPPLE_ALPHA)),
            SmoothSurface(surface, radius), SmoothSurface(Color.WHITE, radius)
        )
        stateListAnimator = null
        elevation = 0f
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.scheme_action_text_size))
        setTextColor(foreground)
        minWidth = 0
        minimumWidth = 0
        minHeight = kit.dimen(R.dimen.scheme_action_min_height)
        minimumHeight = minHeight
        val horizontal = kit.dimen(R.dimen.scheme_action_padding_horizontal)
        val vertical = kit.dimen(R.dimen.scheme_action_padding_vertical)
        setPaddingRelative(horizontal, vertical, horizontal, vertical)
        compoundDrawablePadding = kit.dimen(R.dimen.scheme_action_gap)
        val iconSize = kit.dimen(R.dimen.scheme_action_icon_size)
        val icon = requireNotNull(AppCompatResources.getDrawable(context, iconRes)).mutate().apply {
            setTint(foreground)
            setBounds(0, 0, iconSize, iconSize)
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        setOnClickListener { onClick() }
        pressSpring = SpringAnimator(
            SpringAnimator.MICRO_STIFFNESS, SpringAnimator.MICRO_DAMPING,
            precision = SCALE_PRECISION
        ) { scale -> scaleX = scale; scaleY = scale }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        // The framework can call this during superclass construction.
        pressSpring?.start(scaleX, if (pressed) PRESSED_SCALE else 1f)
    }

    override fun onDetachedFromWindow() {
        pressSpring?.stop()
        scaleX = 1f
        scaleY = 1f
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PRESSED_SCALE = 0.98f
        const val SCALE_PRECISION = 0.001f
    }
}

/** Switch to full-width actions when the panel or the user's font size needs more room. */
internal class SchemeActionRow(context: Context) : LinearLayout(context) {
    private val gap = resources.getDimensionPixelSize(R.dimen.scheme_action_gap)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = gap
        }
        gravity = Gravity.CENTER_VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val minWidth = resources.getDimensionPixelSize(R.dimen.scheme_action_min_width) * resources.configuration.fontScale
        val stacked = available < minWidth * childCount + gap * (childCount - 1)
        orientation = if (stacked) VERTICAL else HORIZONTAL
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val width = if (stacked) LayoutParams.MATCH_PARENT else 0
            val weight = if (stacked) 0f else 1f
            val start = if (!stacked && index > 0) gap else 0
            val top = if (stacked && index > 0) gap else 0
            val previous = child.layoutParams as LayoutParams
            if (previous.width != width || previous.weight != weight ||
                previous.marginStart != start || previous.topMargin != top) {
                previous.apply {
                    this.width = width
                    this.weight = weight
                    marginStart = start
                    topMargin = top
                }
                child.layoutParams = previous
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
