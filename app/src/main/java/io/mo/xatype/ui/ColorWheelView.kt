package io.mo.xatype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A circular HSV color wheel view allowing intuitive color selection by sliding on the wheel.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x26000000
    }
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0x33000000
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }
    private val thumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var centerX = 0f
    private var centerY = 0f
    private var wheelRadius = 0f
    private val thumbRadius = dp(11f)

    var hue: Float = 0f
        private set
    var saturation: Float = 0f
        private set
    var brightness: Float = 1f
        private set

    var onColorChangedListener: ((color: Int, hue: Float, saturation: Float, brightness: Float, fromUser: Boolean) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        wheelRadius = (min(w, h) / 2f - thumbRadius - dp(4f)).coerceAtLeast(10f)

        val colors = intArrayOf(
            Color.RED,
            Color.YELLOW,
            Color.GREEN,
            Color.CYAN,
            Color.BLUE,
            Color.MAGENTA,
            Color.RED
        )
        val sweepShader = SweepGradient(centerX, centerY, colors, null)
        val radialShader = RadialGradient(
            centerX, centerY, wheelRadius,
            Color.WHITE, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        wheelPaint.shader = ComposeShader(sweepShader, radialShader, PorterDuff.Mode.SRC_OVER)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = dp(220f).toInt()
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(defaultSize, widthSize)
            else -> defaultSize
        }
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(defaultSize, heightSize)
            else -> defaultSize
        }
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (wheelRadius <= 0f) return

        // 1. Draw color wheel
        canvas.drawCircle(centerX, centerY, wheelRadius, wheelPaint)

        // 2. Overlay brightness darkening if brightness < 1
        if (brightness < 1f) {
            blackOverlayPaint.color = Color.BLACK
            blackOverlayPaint.alpha = ((1f - brightness) * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, wheelRadius, blackOverlayPaint)
        }

        // 3. Draw border
        canvas.drawCircle(centerX, centerY, wheelRadius, borderPaint)

        // 4. Calculate thumb position
        val rad = Math.toRadians(hue.toDouble())
        val dist = saturation * wheelRadius
        val thumbX = (centerX + dist * cos(rad)).toFloat()
        val thumbY = (centerY + dist * sin(rad)).toFloat()

        // 5. Draw thumb indicator
        canvas.drawCircle(thumbX, thumbY, thumbRadius + dp(0.5f), thumbShadowPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbRingPaint)
        thumbFillPaint.color = getColor()
        canvas.drawCircle(thumbX, thumbY, thumbRadius - dp(2.5f), thumbFillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y, fromUser = true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                updateFromTouch(event.x, event.y, fromUser = true)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float, y: Float, fromUser: Boolean) {
        if (wheelRadius <= 0f) return
        val dx = x - centerX
        val dy = y - centerY
        val dist = hypot(dx, dy)
        val sat = (dist / wheelRadius).coerceIn(0f, 1f)
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f

        this.hue = angle
        this.saturation = sat

        // If touching wheel while brightness was 0, restore brightness to 1 so user immediately sees color
        if (brightness < 0.05f) {
            this.brightness = 1f
        }

        invalidate()
        onColorChangedListener?.invoke(getColor(), hue, saturation, brightness, fromUser)
    }

    fun setColor(color: Int, fromUser: Boolean = false) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        this.hue = hsv[0]
        this.saturation = hsv[1]
        this.brightness = hsv[2]
        invalidate()
        onColorChangedListener?.invoke(color, hue, saturation, brightness, fromUser)
    }

    fun setBrightness(brightness: Float, fromUser: Boolean = false) {
        this.brightness = brightness.coerceIn(0f, 1f)
        invalidate()
        onColorChangedListener?.invoke(getColor(), hue, saturation, this.brightness, fromUser)
    }

    fun getColor(): Int {
        return Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    }

    fun getPureColor(): Int {
        return Color.HSVToColor(floatArrayOf(hue, saturation, 1f))
    }

    fun getColorHex(): String {
        val c = getColor()
        return String.format("#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
