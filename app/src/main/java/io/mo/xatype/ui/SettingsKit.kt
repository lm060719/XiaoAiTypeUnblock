package io.mo.xatype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import io.mo.xatype.R
import kotlin.math.roundToInt

/** Builds the grouped settings rows shared by the main tabs. Every size and color comes from resources. */
internal class SettingsKit(val context: Context) {

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    fun dimen(res: Int): Int = context.resources.getDimensionPixelSize(res)
    fun color(res: Int): Int = context.getColor(res)

    fun label(text: CharSequence, sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color(colorRes))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun sectionHeader(title: String, first: Boolean = false) = label(title, 13f, R.color.text_secondary, bold = true).apply {
        setPadding(dimen(R.dimen.space_xs), if (first) dimen(R.dimen.space_sm) else dimen(R.dimen.space_xl),
            dimen(R.dimen.space_xs), dimen(R.dimen.space_sm))
    }

    fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        clipToOutline = true
    }

    fun divider() = View(context).apply {
        setBackgroundColor(color(R.color.card_stroke))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dimen(R.dimen.settings_divider_height)).apply {
            marginStart = dimen(R.dimen.settings_row_padding)
            marginEnd = dimen(R.dimen.settings_row_padding)
        }
    }

    /** A titled card of rows separated by hairlines. */
    fun group(parent: LinearLayout, title: String?, rows: List<View>, first: Boolean = false): LinearLayout {
        title?.let { parent.addView(sectionHeader(it, first)) }
        val card = card()
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(divider())
            card.addView(row)
        }
        parent.addView(card)
        return card
    }

    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        val padding = dimen(R.dimen.settings_row_padding)
        setPadding(padding, dimen(R.dimen.space_md), padding, dimen(R.dimen.space_md))
        isClickable = true
        isFocusable = true
        foreground = selectableBackground()
    }

    fun selectableBackground() = TypedValue().let {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        context.getDrawable(it.resourceId)
    }

    /** [singleLine] keeps settings rows to one subtitle line; the full text belongs in a [Detail]. */
    fun titleBlock(title: CharSequence, subtitle: CharSequence?, singleLine: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 15f, R.color.text_primary, bold = true))
        if (!subtitle.isNullOrEmpty()) {
            addView(label(subtitle, 13f, R.color.text_secondary).apply {
                setPadding(0, dp(2), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
                if (singleLine) {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            })
        }
    }

    fun iconTile(iconRes: Int) = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(color(R.color.primary))
        setBackgroundResource(R.drawable.bg_icon_tile)
        val pad = dp(9)
        setPadding(pad, pad, pad, pad)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun addLeading(row: LinearLayout, iconRes: Int?) {
        iconRes ?: return
        val size = dimen(R.dimen.icon_tile)
        row.addView(iconTile(iconRes), LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dimen(R.dimen.space_md)
        })
    }

    /** The full explanation of a setting, shown in an [InfoSheet] instead of under its title. */
    class Detail(
        val about: CharSequence,
        val notes: List<CharSequence> = emptyList(),
        /** Extra content placed after [about], such as a key map. */
        val extra: ((SettingsKit) -> View)? = null
    )

    private fun trailingIcon(iconRes: Int) = ImageView(context).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A 48dp target that opens the detail sheet without toggling the row. */
    private fun infoButton(title: CharSequence, detail: Detail) = ImageView(context).apply {
        setImageResource(R.drawable.ic_info)
        imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(14)
        setPadding(pad, pad, pad, pad)
        background = TypedValue().let {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            context.getDrawable(it.resourceId)
        }
        contentDescription = "$title 说明"
        setOnClickListener { InfoSheet.show(context, title, detail) }
    }

    class SwitchRow(val view: LinearLayout, val switch: SwitchCompat)

    /** The whole row toggles; [onChange] fires on every change after creation. */
    fun switchRow(
        title: CharSequence,
        subtitle: CharSequence?,
        checked: Boolean,
        iconRes: Int? = null,
        detail: Detail? = null,
        onChange: (Boolean) -> Unit
    ): SwitchRow {
        val row = row()
        addLeading(row, iconRes)
        row.addView(titleBlock(title, subtitle, singleLine = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        detail?.let { row.addView(infoButton(title, it), LinearLayout.LayoutParams(dp(48), dp(48))) }
        val switch = SwitchCompat(context).apply {
            isChecked = checked
            setPadding(0, 0, 0, 0)
            contentDescription = title
            setOnCheckedChangeListener { view, value ->
                confirmHaptic(view)
                onChange(value)
            }
        }
        row.addView(FrameLayout(context).apply {
            addView(switch, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(dimen(R.dimen.settings_trailing_width), dp(48)))
        row.setOnClickListener { switch.toggle() }
        return SwitchRow(row, switch)
    }

    fun navRow(title: CharSequence, subtitle: CharSequence?, iconRes: Int?, detail: Detail? = null,
               onClick: () -> Unit): LinearLayout {
        val row = row()
        addLeading(row, iconRes)
        row.addView(titleBlock(title, subtitle, singleLine = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        detail?.let { row.addView(infoButton(title, it), LinearLayout.LayoutParams(dp(48), dp(48))) }
        row.addView(trailingIcon(R.drawable.ic_shape_chevron),
            LinearLayout.LayoutParams(dimen(R.dimen.settings_trailing_width), dp(48)))
        row.setOnClickListener { onClick() }
        return row
    }

    fun seekBar(max: Int, value: Int, description: String) = SeekBar(context).apply {
        this.max = max
        progress = value
        contentDescription = description
        progressTintList = ColorStateList.valueOf(color(R.color.primary))
        thumbTintList = ColorStateList.valueOf(color(R.color.primary))
    }

    companion object {
        fun confirmHaptic(view: View) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
        }

        fun tickHaptic(view: View) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE
                else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }
}
