package io.mo.xatype.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import io.mo.xatype.ui.SpringAnimator
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Created in the host process with the panel's colors and callback.
internal class ClipboardConfirmSlider(
    context: Context,
    trackColor: Int,
    trackText: Int,
    thumbColor: Int,
    thumbText: Int,
    private val onSlideStarted: () -> Unit,
    onConfirm: () -> Unit
) : FrameLayout(context) {
    private val fillClip = Rect()
    private val fill = View(context).apply {
        background = capsule(thumbColor)
        alpha = FILL_ALPHA
        clipBounds = fillClip
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = TextView(context).apply {
        text = "右滑输入"
        textSize = LABEL_SIZE
        setTextColor(trackText)
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(THUMB_SIZE + SLIDER_INSET), 0, dp(SLIDER_INSET), 0)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val thumb = TextView(context).apply {
        text = "→"
        textSize = ARROW_SIZE
        setTextColor(thumbText)
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = capsule(thumbColor)
    }
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledTouchSlop
    private val spring = SpringAnimator(
        SpringAnimator.STANDARD_STIFFNESS, SpringAnimator.STANDARD_DAMPING, onUpdate = ::moveThumb
    )
    private var fingerStartX = 0f
    private var fingerStartY = 0f
    private var thumbStart = 0f
    private var slideStarted = false
    private var confirmationVelocity = 0f
    private var velocity: VelocityTracker? = null
    private val travel: Float get() = (width - thumb.width - dp(SLIDER_INSET) * 2).coerceAtLeast(0).toFloat()

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        minimumHeight = dp(TOUCH_HEIGHT)
        contentDescription = "确认输入所选词块"
        isFocusable = true
        setOnClickListener { onConfirm() }
        addView(View(context).apply {
            background = capsule(trackColor)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(TRACK_HEIGHT), Gravity.CENTER_VERTICAL))
        addView(fill, LayoutParams(LayoutParams.MATCH_PARENT, dp(TRACK_HEIGHT), Gravity.CENTER_VERTICAL))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, dp(TRACK_HEIGHT), Gravity.CENTER_VERTICAL))
        addView(thumb, LayoutParams(dp(THUMB_SIZE), dp(THUMB_SIZE), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(SLIDER_INSET)
        })
        setSelectionAvailable(false)
    }

    fun setSelectionAvailable(available: Boolean) {
        if (!available && velocity != null) finishDrag(0f, false)
        isEnabled = available
        thumb.alpha = if (available) 1f else DISABLED_THUMB_ALPHA
    }

    fun showConfirmed() {
        isEnabled = false
        thumb.text = "✓"
        spring.start(thumb.translationX, travel, confirmationVelocity)
        confirmationVelocity = 0f
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (travel <= touchSlop || event.x < thumb.x - touchSlop ||
                    event.x > thumb.x + thumb.width + touchSlop
                ) return false
                spring.stop()
                fingerStartX = event.x
                fingerStartY = event.y
                thumbStart = thumb.translationX
                slideStarted = false
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> velocity?.let {
                it.addMovement(event)
                val distance = event.x - fingerStartX
                moveThumb(thumbStart + distance)
                if (!slideStarted && distance >= touchSlop) {
                    slideStarted = true
                    onSlideStarted()
                }
            }
            MotionEvent.ACTION_UP -> velocity?.let {
                it.addMovement(event)
                val distance = event.x - fingerStartX
                moveThumb(thumbStart + distance)
                it.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                val confirmDistance = minOf(dp(MAX_CONFIRM_DISTANCE).toFloat(), travel * CONFIRM_FRACTION)
                    .coerceAtLeast(touchSlop.toFloat())
                val verticalDistance = abs(event.y - fingerStartY)
                val confirmed = distance >= confirmDistance &&
                    verticalDistance <= maxOf(dp(VERTICAL_TOLERANCE).toFloat(), distance)
                finishDrag(it.xVelocity, confirmed)
                if (confirmed) performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (velocity != null) finishDrag(0f, false)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        velocity?.recycle()
        velocity = null
        confirmationVelocity = 0f
        spring.stop()
        moveThumb(0f)
        super.onDetachedFromWindow()
    }

    private fun moveThumb(offset: Float) {
        thumb.translationX = offset.coerceIn(0f, travel)
        val progress = if (travel > 0) thumb.translationX / travel else 0f
        label.alpha = 1f - progress
        fillClip.set(0, 0, (fill.width * progress).roundToInt(), fill.height)
        fill.clipBounds = fillClip
    }

    private fun finishDrag(pxPerSecond: Float, confirmed: Boolean) {
        velocity?.recycle()
        velocity = null
        parent?.requestDisallowInterceptTouchEvent(false)
        if (confirmed) {
            confirmationVelocity = pxPerSecond
        } else {
            spring.start(thumb.translationX, 0f, pxPerSecond)
        }
    }

    private fun capsule(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(TRACK_HEIGHT / 2).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TOUCH_HEIGHT = 48
        const val TRACK_HEIGHT = 36
        const val THUMB_SIZE = 30
        const val SLIDER_INSET = 3
        const val LABEL_SIZE = 13f
        const val ARROW_SIZE = 19f
        const val DISABLED_THUMB_ALPHA = 0.5f
        const val FILL_ALPHA = 0.22f
        const val CONFIRM_FRACTION = 0.45f
        const val MAX_CONFIRM_DISTANCE = 48
        const val VERTICAL_TOLERANCE = 24
    }
}
