package io.mo.xatype.hooks

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

internal class ClipboardSplitPanel(
    private val host: View,
    private val parent: FrameLayout,
    private val words: List<String>,
    roundBottom: Boolean,
    private val commit: (String) -> Boolean
) {
    private val context = host.context
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val palette = if (dark) darkPalette else lightPalette
    private val pane = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val flow = WordFlow(context, dp(CHIP_GAP))
    private val previous = button("上一页") { showPage(page - 1) }
    private val next = button("下一页") { showPage(page + 1) }
    private val pageLabel = TextView(context)
    private val lastPage = words.lastIndex / PAGE_SIZE
    private var page = 0
    private val onHostLayout = View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) alignPane()
    }
    private val onHostDetach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            // The parent may still be dispatching the detach to its children.
            Handler(context.mainLooper).post { close() }
        }
    }

    init {
        val corner = dp(PANEL_RADIUS).toFloat()
        val bottom = if (roundBottom) corner else 0f
        pane.apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) accessibilityPaneTitle = "剪贴板拆分"
            setPadding(dp(PANEL_INSET), 0, dp(PANEL_INSET), dp(PANEL_BOTTOM_INSET))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadii = floatArrayOf(corner, corner, corner, corner, bottom, bottom, bottom, bottom)
            }
            clipToOutline = roundBottom
        }
        pane.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "拆分"
                textSize = TITLE_SIZE
                setTextColor(palette.text)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("返回") { close() })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.addView(flow, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        pane.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (lastPage > 0) {
            pane.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(previous)
                addView(pageLabel.apply {
                    gravity = Gravity.CENTER
                    setTextColor(palette.text)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(next)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        showPage(0)
    }

    fun show() {
        alignPane()
        parent.addView(pane)
        host.visibility = View.INVISIBLE
        host.addOnLayoutChangeListener(onHostLayout)
        host.addOnAttachStateChangeListener(onHostDetach)
    }

    private fun alignPane() {
        val bounds = FrameLayout.LayoutParams(host.layoutParams as FrameLayout.LayoutParams)
        if (bounds.width == WRAP_CONTENT) bounds.width = host.width
        if (bounds.height == WRAP_CONTENT) bounds.height = host.height
        pane.layoutParams = bounds
        pane.layoutDirection = host.layoutDirection
        pane.pivotX = host.pivotX
        pane.pivotY = host.pivotY
        pane.scaleX = host.scaleX
        pane.scaleY = host.scaleY
        pane.translationX = host.translationX
        pane.translationY = host.translationY
    }

    private fun close() {
        if (pane.parent !== parent) return
        host.removeOnLayoutChangeListener(onHostLayout)
        host.removeOnAttachStateChangeListener(onHostDetach)
        parent.removeView(pane)
        host.visibility = View.VISIBLE
    }

    private fun showPage(index: Int) {
        page = index
        flow.removeAllViews()
        val start = page * PAGE_SIZE
        for (word in words.subList(start, minOf(start + PAGE_SIZE, words.size))) {
            val chip = button(word, chip = true) {
                if (runCatching { commit(word) }.getOrDefault(false)) {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else {
                    Toast.makeText(context, "无法输入到当前输入框", Toast.LENGTH_SHORT).show()
                    close()
                }
            }
            flow.addView(chip)
        }
        scroll.scrollTo(0, 0)
        previous.isEnabled = page > 0
        next.isEnabled = page < lastPage
        pageLabel.text = String.format(Locale.getDefault(), "%d / %d", page + 1, lastPage + 1)
    }

    private fun button(label: String, chip: Boolean = false, action: (View) -> Unit): Button =
        Button(context, null, 0).apply {
            text = label
            isAllCaps = false
            textSize = BUTTON_SIZE
            gravity = Gravity.CENTER
            isFocusable = true
            minimumWidth = 0
            minimumHeight = 0
            minWidth = dp(TOUCH_SIZE)
            minHeight = dp(TOUCH_SIZE)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(palette.disabledText, palette.text)
            ))
            if (chip) {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val shape = GradientDrawable().apply {
                setColor(if (chip) palette.chip else palette.surface)
                cornerRadius = dp(CHIP_RADIUS).toFloat()
                if (chip) setStroke(dp(CHIP_STROKE), palette.outline)
            }
            val ripple = RippleDrawable(ColorStateList.valueOf(palette.ripple), shape, null)
            val verticalInset = if (chip) dp(CHIP_VERTICAL_INSET) else 0
            background = InsetDrawable(ripple, 0, verticalInset, 0, verticalInset)
            setPadding(dp(BUTTON_PADDING), verticalInset, dp(BUTTON_PADDING), verticalInset)
            setOnClickListener(action)
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private class WordFlow(context: Context, private val columnGap: Int) : ViewGroup(context) {
        override fun generateDefaultLayoutParams() = LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            for (index in 0 until childCount) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
            }
            setMeasuredDimension(width, resolveSize(rows(width, place = false), heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            rows(right - left, place = true)
        }

        private fun rows(width: Int, place: Boolean): Int {
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            var x = 0
            var y = 0
            var rowHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                if (x > 0 && x + childWidth > width) {
                    x = 0
                    y += rowHeight
                    rowHeight = 0
                }
                if (place) {
                    val childLeft = if (rtl) width - x - childWidth else x
                    child.layout(childLeft, y, childLeft + childWidth, y + childHeight)
                }
                x += childWidth + columnGap
                rowHeight = maxOf(rowHeight, childHeight)
            }
            return y + rowHeight
        }
    }

    private data class Palette(
        val surface: Int,
        val chip: Int,
        val text: Int,
        val disabledText: Int,
        val outline: Int,
        val ripple: Int
    )

    private companion object {
        const val PAGE_SIZE = 80
        const val TOUCH_SIZE = 48
        const val TITLE_SIZE = 17f
        const val BUTTON_SIZE = 15f
        const val PANEL_RADIUS = 16
        const val CHIP_RADIUS = 9
        const val CHIP_STROKE = 1
        const val PANEL_INSET = 8
        const val PANEL_BOTTOM_INSET = 4
        const val BUTTON_PADDING = 8
        const val CHIP_VERTICAL_INSET = 5
        const val CHIP_GAP = 4
        val lightPalette = Palette(
            surface = 0xFFFDFDFE.toInt(),
            chip = 0xFFE6ECF6.toInt(),
            text = 0xFF18202D.toInt(),
            disabledText = 0xFF747B86.toInt(),
            outline = 0xFF748094.toInt(),
            ripple = 0x2218202D
        )
        val darkPalette = Palette(
            surface = 0xFF20242C.toInt(),
            chip = 0xFF343E50.toInt(),
            text = 0xFFF2F5FA.toInt(),
            disabledText = 0xFF929BAA.toInt(),
            outline = 0xFF8693AA.toInt(),
            ripple = 0x32F2F5FA
        )
    }
}
