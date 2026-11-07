package io.mo.xatype.hooks

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.inputmethod.InputConnection
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.keyboard.HandwritingRecognizer
import io.mo.xatype.util.XposedUtils
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Handwriting on the letter keys of the Chinese 26-key, 虎码/五笔, 18-key and 14-key keyboards.
 *
 * Touches are read ahead of the keyboard through the IME window callback. A touch that starts
 * on a letter key stays a key press until it travels [CLAIM_DP]; it then becomes the first
 * stroke and the key press is cancelled. While strokes are pending, every touch in the keyboard
 * area is ink, dots included. [RECOGNITION_DELAY_MS] after the last stroke the ink goes to the
 * host's own handwriting engine; the best candidate is committed and the others are offered on
 * a strip above the keys, where a tap replaces it.
 */
class KeyboardHandwriting(private val module: XposedInterface, private val host: Host,
                          private val recognizer: HandwritingRecognizer) {
    interface Host {
        /** A supported keyboard is visible in Chinese mode, and the switch is on. */
        fun active(): Boolean
        /** Screen bounds of the visible letter keys. */
        fun letterBounds(): List<RectF>
        /** A downward swipe shortcut owns the current touch. */
        fun swipeTracking(): Boolean
        /** Commit pending composition first, then run [done]. */
        fun finishComposing(done: () -> Unit)
        fun commit(text: String)
        fun connection(): InputConnection?
    }

    private class Pending(val pointer: Int, val x: Float, val y: Float, val time: Long) {
        val points = FloatList()
    }

    private class FloatList {
        var data = FloatArray(64)
        var size = 0
        fun add(x: Float, y: Float) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }
        fun toArray() = data.copyOf(size)
    }

    private val main = Handler(Looper.getMainLooper())
    private val recognizeLater = Runnable { recognize() }
    private val ink = Ink()
    private var decor = WeakReference<View>(null)
    private var pending: Pending? = null
    private var stroke: FloatList? = null
    private var strokePointer = -1
    private val strokes = ArrayList<FloatArray>()
    /** The session's keyboard area and letter-key box, in screen pixels. */
    private var area: RectF? = null
    private var letters: RectF? = null
    private var candidates: List<String> = emptyList()
    private var committed: String? = null
    private var strip: RectF? = null
    private val items = ArrayList<Pair<RectF, Int>>()
    private var stripGesture = false
    private var stripPressed = -1
    private var generation = 0
    /** Native key input is blocked: a claimed press is cancelled, which Compose reports as a release. */
    var suppressing = false
        private set

    /** Read touches through [service]'s window ahead of the keyboard; idempotent. */
    fun attach(service: InputMethodService) {
        val window = service.window?.window ?: return
        val current = window.callback ?: return
        if (Proxy.isProxyClass(current.javaClass) && Proxy.getInvocationHandler(current) is Dispatch) return
        window.callback = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Window.Callback::class.java),
            Dispatch(current)) as Window.Callback
        val view = window.decorView
        if (decor.get() !== view) {
            decor.get()?.overlay?.remove(ink)
            view.overlay.add(ink)
            decor = WeakReference(view)
        }
        XposedUtils.log(module, "Keyboard handwriting attached to the input window")
    }

    /** Drop ink, pending recognition and the candidate strip (the keyboard was hidden). */
    fun reset() {
        main.removeCallbacks(recognizeLater)
        generation++
        pending = null
        stroke = null
        strokes.clear()
        area = null
        hideStrip()
        stripGesture = false
        suppressing = false
        invalidate()
    }

    private inner class Dispatch(private val original: Window.Callback) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val event = args?.singleOrNull() as? MotionEvent
            if (event != null && method.name == "dispatchTouchEvent") return try {
                touch(event) { original.dispatchTouchEvent(it) }
            } catch (failure: Throwable) {
                XposedUtils.logError(module, "Keyboard handwriting touch failed", failure)
                reset()
                original.dispatchTouchEvent(event)
            }
            return when {
                method.name == "equals" && args?.size == 1 -> proxy === args[0]
                method.name == "hashCode" && args.isNullOrEmpty() -> System.identityHashCode(proxy)
                method.name == "toString" && args.isNullOrEmpty() -> "XatypeHandwritingCallback($original)"
                else -> try {
                    method.invoke(original, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        }
    }

    private fun touch(event: MotionEvent, pass: (MotionEvent) -> Boolean): Boolean {
        val action = event.actionMasked
        val x = event.rawX
        val y = event.rawY
        if (action == MotionEvent.ACTION_DOWN) {
            // Cancellation callbacks of the previous claimed press have drained by now.
            suppressing = false
            stripGesture = false
            strip?.let { bar ->
                if (bar.contains(x, y)) {
                    stripGesture = true
                    stripPressed = items.firstOrNull { it.first.contains(x, y) }?.second ?: -1
                    return true
                }
                hideStrip()
            }
        }
        if (stripGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stripGesture = false
                val index = items.firstOrNull { it.first.contains(x, y) }?.second ?: -1
                if (action == MotionEvent.ACTION_UP && index >= 0 && index == stripPressed) choose(index)
            }
            return true
        }
        if (!host.active()) {
            if (stroke != null || strokes.isNotEmpty()) reset()
            pending = null
            return pass(event)
        }
        val density = density()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                pending = null
                if (strokes.isNotEmpty()) {
                    if (area?.contains(x, y) == true) {
                        main.removeCallbacks(recognizeLater)
                        stroke = FloatList().apply { add(x, y) }
                        strokePointer = event.getPointerId(0)
                        invalidate()
                        return true
                    }
                    recognize()
                    return pass(event)
                }
                val keys = host.letterBounds()
                if (keys.any { it.contains(x, y) }) {
                    letters = RectF(keys[0]).apply { keys.forEach { union(it) } }
                    pending = Pending(event.getPointerId(0), x, y, event.eventTime).apply { points.add(x, y) }
                }
                return pass(event)
            }
            MotionEvent.ACTION_MOVE -> {
                stroke?.let { current ->
                    append(event, strokePointer, current)
                    invalidate()
                    return true
                }
                val candidate = pending ?: return pass(event)
                if (event.pointerCount != 1 || event.findPointerIndex(candidate.pointer) != 0) {
                    pending = null
                    return pass(event)
                }
                append(event, candidate.pointer, candidate.points)
                val dx = x - candidate.x
                val dy = y - candidate.y
                if (hypot(dx, dy) < CLAIM_DP * density) {
                    // Held in place: the native long-press popup owns this touch.
                    if (event.eventTime - candidate.time > ViewConfiguration.getLongPressTimeout()) pending = null
                    return pass(event)
                }
                // A straight downward drag from a shortcut key stays a swipe shortcut.
                if (host.swipeTracking() && dy > 0 && abs(dx) <= dy * DOWNWARD_RATIO) return pass(event)
                suppressing = true
                val cancel = MotionEvent.obtain(event).apply { this.action = MotionEvent.ACTION_CANCEL }
                try { pass(cancel) } finally { cancel.recycle() }
                pending = null
                beginSession()
                stroke = candidate.points
                strokePointer = candidate.pointer
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                stroke?.let { current ->
                    if (event.findPointerIndex(strokePointer) >= 0) current.add(x, y)
                    endStroke(current)
                    return true
                }
                pending = null
                return pass(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (stroke != null) {
                    stroke = null
                    if (strokes.isEmpty()) reset() else schedule()
                    return true
                }
                pending = null
                return pass(event)
            }
            else -> {
                if (stroke != null) {
                    // A second finger ends the stroke drawn by the first.
                    if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == strokePointer)
                        stroke?.let(::endStroke)
                    return true
                }
                pending = null
                return pass(event)
            }
        }
    }

    private fun append(event: MotionEvent, pointer: Int, points: FloatList) {
        val index = event.findPointerIndex(pointer)
        if (index < 0) return
        val offsetX = event.rawX - event.getX(0)
        val offsetY = event.rawY - event.getY(0)
        for (h in 0 until event.historySize) {
            points.add(event.getHistoricalX(index, h) + offsetX, event.getHistoricalY(index, h) + offsetY)
        }
        points.add(event.getX(index) + offsetX, event.getY(index) + offsetY)
    }

    private fun beginSession() {
        hideStrip()
        val keys = letters ?: return
        val bottom = decor.get()?.let { view ->
            val location = IntArray(2).also(view::getLocationOnScreen)
            (location[1] + view.height).toFloat()
        } ?: keys.bottom
        area = RectF(keys.left, keys.top, keys.right, max(keys.bottom, bottom))
    }

    private fun endStroke(current: FloatList) {
        stroke = null
        strokes += current.toArray()
        invalidate()
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(recognizeLater)
        main.postDelayed(recognizeLater, RECOGNITION_DELAY_MS)
    }

    private fun recognize() {
        main.removeCallbacks(recognizeLater)
        val box = area
        val captured = strokes.toList()
        strokes.clear()
        stroke = null
        area = null
        invalidate()
        if (box == null || captured.isEmpty()) return
        val width = box.width().toInt().coerceAtLeast(1)
        val height = box.height().toInt().coerceAtLeast(1)
        val points = captured.map { stroke ->
            IntArray(stroke.size) { i ->
                if (i % 2 == 0) (stroke[i] - box.left).coerceIn(0f, width.toFloat()).toInt()
                else (stroke[i] - box.top).coerceIn(0f, height.toFloat()).toInt()
            }
        }
        val session = generation
        host.finishComposing {
            recognizer.recognize(module, points, width, height) { words ->
                words.firstOrNull()?.let { best ->
                    host.commit(best)
                    committed = best
                    // A newer session or a hidden keyboard does not show stale alternatives.
                    if (session == generation && strokes.isEmpty() && stroke == null) showStrip(words)
                }
            }
        }
    }

    private fun choose(index: Int) {
        val word = candidates.getOrNull(index)
        val previous = committed
        hideStrip()
        if (word == null || previous == null || index == 0) return
        val connection = host.connection() ?: return
        if (connection.getTextBeforeCursor(previous.length, 0)?.toString() != previous) return
        connection.deleteSurroundingText(previous.length, 0)
        host.commit(word)
        committed = word
    }

    private fun showStrip(words: List<String>) {
        val keys = letters ?: return
        val view = decor.get() ?: return
        val density = density()
        val location = IntArray(2).also(view::getLocationOnScreen)
        val height = STRIP_HEIGHT_DP * density
        val margin = 4 * density
        val top = if (keys.top - height - margin >= location[1]) keys.top - height - margin else keys.top + margin
        val bar = RectF(keys.left + margin, top, keys.right - margin, top + height)
        ink.text.textSize = STRIP_TEXT_SP * view.resources.displayMetrics.scaledDensity
        items.clear()
        val padding = 14 * density
        var left = bar.left + 6 * density
        for ((index, word) in words.withIndex()) {
            val width = ink.text.measureText(word) + padding * 2
            if (left + width > bar.right - 6 * density) break
            items += RectF(left, bar.top, left + width, bar.bottom) to index
            left += width
        }
        candidates = words
        strip = bar
        invalidate()
    }

    private fun hideStrip() {
        if (strip == null) return
        strip = null
        items.clear()
        candidates = emptyList()
        invalidate()
    }

    private fun density() = decor.get()?.resources?.displayMetrics?.density ?: 1f

    private fun invalidate() {
        val view = decor.get() ?: return
        ink.setBounds(0, 0, view.width, view.height)
        ink.invalidateSelf()
    }

    /** Ink and candidate strip, drawn on the input window's overlay in screen coordinates. */
    private inner class Ink : Drawable() {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = INK_COLOR
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val path = Path()
        private val location = IntArray(2)

        override fun draw(canvas: Canvas) {
            val view = decor.get() ?: return
            view.getLocationOnScreen(location)
            val dx = -location[0].toFloat()
            val dy = -location[1].toFloat()
            val density = view.resources.displayMetrics.density
            val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            line.strokeWidth = INK_WIDTH_DP * density
            for (points in strokes) drawStroke(canvas, points, points.size, dx, dy, density)
            stroke?.let { drawStroke(canvas, it.data, it.size, dx, dy, density) }
            val bar = strip ?: return
            val radius = 12 * density
            fill.style = Paint.Style.FILL
            fill.color = if (night) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(bar.left + dx, bar.top + dy, bar.right + dx, bar.bottom + dy, radius, radius, fill)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = density
            fill.color = if (night) 0x33FFFFFF else 0x1F000000
            canvas.drawRoundRect(bar.left + dx, bar.top + dy, bar.right + dx, bar.bottom + dy, radius, radius, fill)
            val metrics = text.fontMetrics
            val baseline = (bar.top + bar.bottom) / 2f - (metrics.ascent + metrics.descent) / 2f + dy
            for ((rect, index) in items) {
                text.color = when {
                    index == 0 -> ACCENT_COLOR
                    night -> 0xFFEDEDED.toInt()
                    else -> 0xFF1F1F1F.toInt()
                }
                text.isFakeBoldText = index == 0
                canvas.drawText(candidates[index], rect.centerX() + dx, baseline, text)
            }
        }

        private fun drawStroke(canvas: Canvas, points: FloatArray, size: Int, dx: Float, dy: Float, density: Float) {
            if (size < 2) return
            if (size == 2) {
                fill.style = Paint.Style.FILL
                fill.color = INK_COLOR
                canvas.drawCircle(points[0] + dx, points[1] + dy, INK_WIDTH_DP * density / 2f, fill)
                return
            }
            path.rewind()
            path.moveTo(points[0] + dx, points[1] + dy)
            var index = 2
            while (index + 1 < size) {
                path.lineTo(points[index] + dx, points[index + 1] + dy)
                index += 2
            }
            canvas.drawPath(path, line)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        /** Travel that turns a key press into the first stroke. */
        const val CLAIM_DP = 12f
        /** tan(30°): drags this close to straight down are left to swipe shortcuts. */
        const val DOWNWARD_RATIO = 0.58f
        const val RECOGNITION_DELAY_MS = 500L
        const val STRIP_HEIGHT_DP = 44f
        const val STRIP_TEXT_SP = 20f
        const val INK_WIDTH_DP = 4f
        const val INK_COLOR = 0xFF2F7CF6.toInt()
        const val ACCENT_COLOR = 0xFF2F7CF6.toInt()
    }
}
