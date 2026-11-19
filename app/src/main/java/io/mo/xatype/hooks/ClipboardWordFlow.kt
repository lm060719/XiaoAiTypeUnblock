package io.mo.xatype.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.abs

@SuppressLint("ViewConstructor") // Created in code alongside the selection state.
internal class ClipboardWordFlow(
    context: Context,
    private val columnGap: Int,
    private val onDragToggle: (Int, View) -> Unit
) : ViewGroup(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val range = ClipboardWordDragRange()
    private var anchor = -1
    private var dragging = false
    private var startX = 0f
    private var startY = 0f

    fun startDrag(chip: View): Boolean {
        if (anchor == -1 || getChildAt(anchor) !== chip) return false
        dragging = true
        parent.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishDrag()
                anchor = chipAt(event.x, event.y)
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (anchor != -1 && (dragging || (abs(dx) > touchSlop && isRangeDrag(dx, dy)))) {
                    if (!dragging) startDrag(getChildAt(anchor))
                    updateRange(event)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishDrag()
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility") // Individual chips handle clicks and accessibility actions.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateRange(event)
            MotionEvent.ACTION_UP -> {
                updateRange(event)
                finishDrag()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishDrag()
        }
        return true
    }

    fun finishDrag() {
        if (dragging) parent?.requestDisallowInterceptTouchEvent(false)
        range.finish()
        dragging = false
        anchor = -1
    }

    private fun updateRange(event: MotionEvent) {
        val viewport = parent as ScrollView
        val visibleTop = viewport.scrollY - top
        val visibleBottom = visibleTop + viewport.height
        for (index in 0 until event.historySize) {
            moveTo(event.getHistoricalX(index), event.getHistoricalY(index), visibleTop, visibleBottom)
        }
        moveTo(event.x, event.y, visibleTop, visibleBottom)
    }

    private fun moveTo(x: Float, y: Float, visibleTop: Int, visibleBottom: Int) {
        if (y < visibleTop || y >= visibleBottom) return
        val target = chipAt(x, y)
        if (target == -1) return
        range.move(anchor, target) { index ->
            onDragToggle(index, getChildAt(index))
        }
    }

    private fun chipAt(x: Float, y: Float): Int {
        for (index in 0 until childCount) {
            val chip = getChildAt(index)
            if (x >= chip.left && x < chip.right && y >= chip.top && y < chip.bottom) return index
        }
        return -1
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        val childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (index in 0 until childCount) getChildAt(index).measure(childWidth, childHeight)
        setMeasuredDimension(width, resolveSize(rows(width, place = false), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        rows(right - left, place = true)
    }

    private fun rows(width: Int, place: Boolean): Int {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
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

internal class ClipboardWordScrollView(context: Context) : ScrollView(context) {
    private var startX = 0f
    private var startY = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (isRangeDrag(event.x - startX, event.y - startY)) return false
            }
        }
        return super.onInterceptTouchEvent(event)
    }
}

private fun isRangeDrag(dx: Float, dy: Float): Boolean =
    abs(dx) > 0f && abs(dx) >= abs(dy) * 0.5f
