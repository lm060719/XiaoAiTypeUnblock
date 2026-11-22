package io.mo.xatype.hooks

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.mo.xatype.ui.SpringAnimator
import java.util.Locale
import kotlin.math.roundToInt

internal class ClipboardSplitPanel(
    private val host: View,
    private val parent: FrameLayout,
    private val words: List<String>,
    roundBottom: Boolean,
    private val keyPressFeedback: () -> Unit,
    private val commit: (String) -> Boolean
) {
    private val context = host.context
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val palette = if (dark) darkPalette else lightPalette
    private val pane = LinearLayout(context)
    private val scroll = ClipboardWordScrollView(context)
    private val selection = ClipboardWordSelection(words)
    private val confirm = ClipboardConfirmSlider(
        context, palette.chip, palette.text, palette.selection, palette.onSelection,
        onSlideStarted = keyPressFeedback, onConfirm = ::submit
    )
    private val flow = ClipboardWordFlow(context, dp(CHIP_GAP)) { index, chip ->
        toggle(chip, page * PAGE_SIZE + index)
    }
    private val previous = button("上一页") { showPage(page - 1) }
    private val next = button("下一页") { showPage(page + 1) }
    private val pageLabel = TextView(context)
    private val lastPage = words.lastIndex / PAGE_SIZE
    private var page = 0
    private var submitted = false
    private val dismissal = SpringAnimator(
        SpringAnimator.STANDARD_STIFFNESS, SpringAnimator.STANDARD_DAMPING, precision = 0.005f
    ) { progress ->
        pane.alpha = 1f - progress
        pane.translationY = host.translationY + dp(DISMISS_TRAVEL) * progress
    }.apply { onEnd = ::close }
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
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(confirm, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(HEADER_GAP)
                marginEnd = dp(HEADER_GAP)
            })
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

    var onClose: (() -> Unit)? = null

    fun close() {
        if (pane.parent !== parent) return
        dismissal.stop()
        host.removeOnLayoutChangeListener(onHostLayout)
        host.removeOnAttachStateChangeListener(onHostDetach)
        parent.removeView(pane)
        host.visibility = View.VISIBLE
        onClose?.invoke()
    }

    private fun showPage(index: Int) {
        if (submitted) return
        flow.finishDrag()
        page = index
        flow.removeAllViews()
        val start = page * PAGE_SIZE
        for (wordIndex in start until minOf(start + PAGE_SIZE, words.size)) {
            val chip = button(words[wordIndex], chip = true) {
                toggle(it, wordIndex)
            }
            chip.isSelected = wordIndex in selection
            chip.setOnLongClickListener { flow.startDrag(it) }
            flow.addView(chip)
        }
        scroll.scrollTo(0, 0)
        previous.isEnabled = page > 0
        next.isEnabled = page < lastPage
        pageLabel.text = String.format(Locale.getDefault(), "%d / %d", page + 1, lastPage + 1)
    }

    private fun toggle(chip: View, index: Int) {
        if (submitted) return
        val hadSelection = selection.hasSelection
        chip.isSelected = selection.toggle(index)
        if (hadSelection != selection.hasSelection) confirm.setSelectionAvailable(selection.hasSelection)
        keyPressFeedback()
    }

    private fun submit() {
        if (submitted || !selection.hasSelection || pane.parent !== parent) return
        submitted = true
        if (!runCatching { commit(selection.text()) }.getOrDefault(false)) {
            Toast.makeText(context, "无法输入到当前输入框", Toast.LENGTH_SHORT).show()
            close()
            return
        }
        confirm.showConfirmed()
        keyPressFeedback()
        host.visibility = View.VISIBLE
        dismissal.start(0f, 1f)
    }

    private fun button(label: String, chip: Boolean = false, action: (View) -> Unit): Button =
        Button(context, null, 0).apply {
            text = label
            isAllCaps = false
            textSize = BUTTON_SIZE
            gravity = Gravity.CENTER
            isFocusable = true
            isHapticFeedbackEnabled = false
            minimumWidth = 0
            minimumHeight = 0
            minWidth = dp(TOUCH_SIZE)
            minHeight = dp(TOUCH_SIZE)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(palette.disabledText, palette.onSelection, palette.text)
            ))
            if (chip) {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val verticalInset = if (chip) dp(CHIP_VERTICAL_INSET) else 0
            background = buttonBackground(chip, verticalInset)
            setPadding(dp(BUTTON_PADDING), verticalInset, dp(BUTTON_PADDING), verticalInset)
            setOnClickListener {
                if (!submitted) {
                    if (!chip) keyPressFeedback()
                    action(it)
                }
            }
        }

    private fun buttonBackground(chip: Boolean, verticalInset: Int): InsetDrawable {
        val normal = GradientDrawable().apply {
            setColor(if (chip) palette.chip else palette.surface)
            cornerRadius = dp(CHIP_RADIUS).toFloat()
            if (chip) setStroke(dp(CHIP_STROKE), palette.outline)
        }
        val states = StateListDrawable().apply {
            if (chip) {
                val selected = GradientDrawable().apply {
                    setColor(palette.selection)
                    cornerRadius = dp(CHIP_RADIUS).toFloat()
                }
                addState(intArrayOf(android.R.attr.state_selected), selected)
            }
            addState(intArrayOf(), normal)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(palette.ripple), states, null)
        return InsetDrawable(ripple, 0, verticalInset, 0, verticalInset)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private data class Palette(
        val surface: Int,
        val chip: Int,
        val text: Int,
        val disabledText: Int,
        val outline: Int,
        val selection: Int,
        val onSelection: Int,
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
        const val HEADER_GAP = 8
        const val DISMISS_TRAVEL = 8
        val lightPalette = Palette(
            surface = 0xFFFDFDFE.toInt(),
            chip = 0xFFE6ECF6.toInt(),
            text = 0xFF18202D.toInt(),
            disabledText = 0xFF747B86.toInt(),
            outline = 0xFF748094.toInt(),
            selection = 0xFF1559A3.toInt(),
            onSelection = 0xFFFFFFFF.toInt(),
            ripple = 0x2218202D
        )
        val darkPalette = Palette(
            surface = 0xFF20242C.toInt(),
            chip = 0xFF343E50.toInt(),
            text = 0xFFF2F5FA.toInt(),
            disabledText = 0xFF929BAA.toInt(),
            outline = 0xFF8693AA.toInt(),
            selection = 0xFFA9CBFF.toInt(),
            onSelection = 0xFF152D50.toInt(),
            ripple = 0x32F2F5FA
        )
    }
}
