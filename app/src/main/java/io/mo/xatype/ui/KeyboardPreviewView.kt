package io.mo.xatype.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.View
import io.mo.xatype.hooks.BackgroundOpacity
import io.mo.xatype.hooks.ReadabilityScrim

/**
 * A drawn approximation of the keyboard for the appearance page. It mirrors
 * the saved settings, and [focus] grays out everything except the part the
 * open setting changes.
 */
class KeyboardPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Style(
        val enabled: Boolean = false,
        val dark: Boolean = false,
        val cornerRadiusDp: Int = 16,
        val opacity: Int = 85,
        val blurDp: Int = 50,
        val bgType: Int = 0,
        val bgColor: Int = 0xff1e1e2e.toInt(),
        val textColor: Int? = null,
        val scrimEnabled: Boolean = true,
        val scrimColor: Int? = null,
        val scrimOpacity: Int = 35,
        val letterKey: Int? = null,
        val letterOpacity: Int = 100,
        val functionKey: Int? = null,
        val functionOpacity: Int = 100,
        val menuCard: Int? = null,
        val menuOpacity: Int = 100,
        val clipboardCard: Int? = null,
        val clipboardOpacity: Int = 100
    )

    enum class Focus { NONE, CORNER, BACKGROUND, TEXT, SCRIM, LETTER_KEY, FUNCTION_KEY, MENU_CARD, CLIPBOARD_CARD }

    var style = Style()
        set(value) { field = value; invalidate() }

    var focus = Focus.NONE
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private val grayFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private val accent = 0xff0066ff.toInt()

    init {
        // BlurMaskFilter needs software rendering before Android 9.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun lit(vararg regions: Focus) = focus == Focus.NONE || focus in regions

    /** Unfocused parts become a flat neutral gray, so the backdrop no longer shows through. */
    private fun shade(color: Int, vararg regions: Focus): Int {
        if (lit(*regions)) return color
        if (Color.alpha(color) == 0) return color
        val l = (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000
        val g = 170 + (l - 128) / 5
        return Color.argb(maxOf(Color.alpha(color), 220), g, g, g)
    }

    /** Grayed labels stay a darker gray than grayed keycaps so the layout reads. */
    private fun labelShade(region: Focus) = if (lit(region)) labelColor() else 0xff8e8e93.toInt()

    // Native colors approximated from the stock light and dark themes.
    private val nativeSurface get() = if (style.dark) 0xff1c1c1e.toInt() else 0xfff2f3f5.toInt()
    private val nativeLetter get() = if (style.dark) 0xff3a3a3c.toInt() else 0xffffffff.toInt()
    private val nativeFunction get() = if (style.dark) 0xff2a2a2c.toInt() else 0xffd9dde3.toInt()
    private val nativeCard get() = if (style.dark) 0xff2c2c2e.toInt() else 0xffffffff.toInt()

    private val custom get() = style.enabled

    private fun surfaceColor(): Int {
        if (!custom) return nativeSurface
        val base = if (style.bgType == 1) style.bgColor or (0xff shl 24) else nativeSurface
        return BackgroundOpacity.argb(base, style.opacity)
    }

    private fun scrimColor(): Int? {
        if (!custom || !style.scrimEnabled || style.scrimOpacity == 0) return null
        val surfaceDark = if (style.bgType == 1) luma(style.bgColor) < 150 else style.dark
        val rgb = style.scrimColor ?: ReadabilityScrim.autoColor(style.textColor, surfaceDark)
        return BackgroundOpacity.argb(rgb or (0xff shl 24), style.scrimOpacity)
    }

    private fun labelColor(): Int {
        if (custom) style.textColor?.let { return it or (0xff shl 24) }
        val surfaceDark = if (custom && style.bgType == 1) luma(style.bgColor) < 150 else style.dark
        return if (surfaceDark) Color.WHITE else 0xe6000000.toInt()
    }

    private fun keycap(color: Int?, opacity: Int, fallback: Int): Int =
        if (custom && color != null) BackgroundOpacity.argb(color or (0xff shl 24), opacity) else fallback

    private fun luma(color: Int) =
        (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawBackdrop(canvas, w, h, blurred = false)

        val top = dp(14f)
        val radius = (if (custom) style.cornerRadiusDp else 16) * resources.displayMetrics.density
        path.reset()
        rect.set(0f, top, w, h + radius)
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)
        val glass = !custom || style.bgType == 0
        if (glass) drawBackdrop(canvas, w, h, blurred = true)
        drawSurface(canvas, w, h, top, glass)
        when (focus) {
            Focus.MENU_CARD -> drawMenu(canvas, w, h, top)
            Focus.CLIPBOARD_CARD -> drawClipboard(canvas, w, h, top)
            else -> drawKeyboard(canvas, w, h, top)
        }
        canvas.restore()

        if (focus == Focus.CORNER) {
            stroke.color = accent
            stroke.strokeWidth = dp(2f)
            rect.set(dp(1f), top + dp(1f), w - dp(1f), h + radius)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    /** Stand-in app content, so transparency and blur are visible. */
    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float, blurred: Boolean) {
        val gray = !lit(Focus.BACKGROUND, Focus.SCRIM)
        fill.colorFilter = if (gray) grayFilter else null
        fill.shader = LinearGradient(0f, 0f, w, h, 0xffffd6a5.toInt(), 0xff9bd0ff.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = RadialGradient(w * 0.78f, h * 0.35f, w * 0.35f, 0xffff5e7e.toInt(), 0x00ff5e7e, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = RadialGradient(w * 0.2f, h * 0.8f, w * 0.3f, 0xff2b2d42.toInt(), 0x002b2d42, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        // Chat bubbles and text lines: sharp outside, blurred under glass.
        val blur = if (custom) style.blurDp.coerceAtLeast(20) else 50
        if (blurred) fill.maskFilter = BlurMaskFilter(dp(blur / 6f), BlurMaskFilter.Blur.NORMAL)
        val lineH = dp(9f)
        var y = dp(4f)
        var i = 0
        while (y < h) {
            val right = i % 2 == 1
            val len = w * (0.35f + (i * 37 % 40) / 100f)
            fill.color = if (right) 0xff95ec69.toInt() else 0xffffffff.toInt()
            val left = if (right) w - len - dp(12f) else dp(12f)
            rect.set(left, y, left + len, y + lineH * 2.4f)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
            fill.color = 0xff1c1e21.toInt()
            rect.set(left + dp(8f), y + lineH * 0.7f, left + len * 0.7f, y + lineH * 1.2f)
            canvas.drawRoundRect(rect, dp(2f), dp(2f), fill)
            y += lineH * 3.4f
            i++
        }
        fill.maskFilter = null
        fill.colorFilter = null
    }

    private fun drawSurface(canvas: Canvas, w: Float, h: Float, top: Float, glass: Boolean) {
        val scrim = scrimColor()
        var surface = surfaceColor()
        // Solid colors sit over the scrim; glass gets it as a foreground layer.
        if (!glass && scrim != null) surface = ReadabilityScrim.under(surface, scrim, 100)
        fill.color = shade(surface, Focus.BACKGROUND, Focus.SCRIM)
        canvas.drawRect(0f, top, w, h, fill)
        if (glass && scrim != null) {
            fill.color = shade(scrim, Focus.SCRIM, Focus.BACKGROUND)
            canvas.drawRect(0f, top, w, h, fill)
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float, bold: Boolean = false) {
        text.color = labelShade(Focus.TEXT)
        text.textSize = size
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawKey(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, region: Focus) {
        rect.set(l, t, r, b)
        fill.color = shade(color, region)
        val keyRadius = dp(6f)
        canvas.drawRoundRect(rect, keyRadius, keyRadius, fill)
        if (focus == region) {
            stroke.color = accent
            stroke.strokeWidth = dp(1.5f)
            canvas.drawRoundRect(rect, keyRadius, keyRadius, stroke)
        }
    }

    /** Candidate words on the left, toolbar icons drawn as simple glyphs on the right. */
    private fun drawCandidates(canvas: Canvas, w: Float, top: Float, barH: Float) {
        val cy = top + barH / 2
        val words = listOf("你好", "我们", "今天", "怎么样")
        var x = dp(14f)
        text.textSize = dp(14f)
        words.forEachIndexed { index, word ->
            val ww = text.measureText(word)
            drawLabel(canvas, word, x + ww / 2, cy, dp(14f), bold = index == 0)
            x += ww + dp(20f)
        }
        stroke.color = labelShade(Focus.TEXT)
        stroke.strokeWidth = dp(1.6f)
        val s = dp(5f)
        val ax = w - dp(22f)
        canvas.drawLine(ax - s, cy - s / 2, ax, cy + s / 2, stroke)
        canvas.drawLine(ax, cy + s / 2, ax + s, cy - s / 2, stroke)
    }

    private fun drawKeyboard(canvas: Canvas, w: Float, h: Float, top: Float) {
        val barH = dp(34f)
        drawCandidates(canvas, w, top, barH)

        val pad = dp(4f)
        val gap = dp(5f)
        val rowsTop = top + barH
        val rowH = (h - rowsTop - pad) / 4f
        val keyW = (w - pad * 2 - gap * 9) / 10f
        val letter = keycap(style.letterKey, style.letterOpacity, nativeLetter)
        val function = keycap(style.functionKey, style.functionOpacity, nativeFunction)
        val labelSize = (rowH * 0.42f).coerceAtMost(dp(17f))

        fun row(index: Int) = rowsTop + rowH * index + gap / 2 to rowsTop + rowH * (index + 1) - gap / 2

        fun letters(keys: String, index: Int, startX: Float) {
            val (t, b) = row(index)
            keys.forEachIndexed { i, c ->
                val l = startX + i * (keyW + gap)
                drawKey(canvas, l, t, l + keyW, b, letter, Focus.LETTER_KEY)
                drawLabel(canvas, c.toString(), l + keyW / 2, (t + b) / 2, labelSize)
            }
        }

        fun functionKey(label: String, index: Int, l: Float, r: Float) {
            val (t, b) = row(index)
            drawKey(canvas, l, t, r, b, function, Focus.FUNCTION_KEY)
            drawLabel(canvas, label, (l + r) / 2, (t + b) / 2, labelSize * 0.78f)
        }

        letters("QWERTYUIOP", 0, pad)
        letters("ASDFGHJKL", 1, pad + (keyW + gap) / 2)
        val wide = keyW * 1.5f + gap / 2
        functionKey("分词", 2, pad, pad + wide)
        letters("ZXCVBNM", 2, pad + wide + gap)
        functionKey("⌫", 2, w - pad - wide, w - pad)

        val (t, b) = row(3)
        var x = pad
        listOf("符", "123", "，").forEach { label ->
            if (label == "，") {
                drawKey(canvas, x, t, x + keyW, b, letter, Focus.LETTER_KEY)
                drawLabel(canvas, label, x + keyW / 2, (t + b) / 2, labelSize)
            } else {
                functionKey(label, 3, x, x + keyW * 1.2f)
                x += keyW * 0.2f
            }
            x += keyW + gap
        }
        val enterW = keyW * 1.9f
        val spaceR = w - pad - enterW - gap * 3 - keyW * 2.2f
        drawKey(canvas, x, t, spaceR, b, letter, Focus.LETTER_KEY)
        drawLabel(canvas, "空格", (x + spaceR) / 2, (t + b) / 2, labelSize * 0.78f)
        x = spaceR + gap
        drawKey(canvas, x, t, x + keyW, b, letter, Focus.LETTER_KEY)
        drawLabel(canvas, "。", x + keyW / 2, (t + b) / 2, labelSize)
        x += keyW + gap
        functionKey("中/英", 3, x, x + keyW * 1.2f)
        x += keyW * 1.2f + gap
        functionKey("发送", 3, x, w - pad)
    }

    private fun drawPanelTitle(canvas: Canvas, w: Float, top: Float, title: String) {
        drawLabel(canvas, title, w / 2, top + dp(17f), dp(14f), bold = true)
    }

    private fun drawMenu(canvas: Canvas, w: Float, h: Float, top: Float) {
        drawPanelTitle(canvas, w, top, "功能")
        val card = keycap(style.menuCard, style.menuOpacity, nativeCard)
        val labels = listOf("语音", "表情", "剪贴板", "常用语", "手写", "键盘调节", "皮肤", "设置")
        val pad = dp(10f)
        val gap = dp(8f)
        val gridTop = top + dp(36f)
        val cellW = (w - pad * 2 - gap * 3) / 4f
        val cellH = (h - gridTop - pad - gap) / 2f
        labels.forEachIndexed { i, label ->
            val l = pad + (i % 4) * (cellW + gap)
            val t = gridTop + (i / 4) * (cellH + gap)
            drawKey(canvas, l, t, l + cellW, t + cellH, card, Focus.MENU_CARD)
            fill.color = labelShade(Focus.TEXT)
            fill.alpha = fill.alpha / 4
            canvas.drawCircle(l + cellW / 2, t + cellH * 0.4f, cellH * 0.16f, fill)
            drawLabel(canvas, label, l + cellW / 2, t + cellH * 0.76f, dp(11f))
        }
    }

    private fun drawClipboard(canvas: Canvas, w: Float, h: Float, top: Float) {
        drawPanelTitle(canvas, w, top, "剪贴板")
        val card = keycap(style.clipboardCard, style.clipboardOpacity, nativeCard)
        val entries = listOf("明天下午三点在公司楼下见", "https://github.com", "收货地址：北京市海淀区…", "验证码 482913")
        val pad = dp(10f)
        val gap = dp(8f)
        val gridTop = top + dp(36f)
        val cellW = (w - pad * 2 - gap) / 2f
        val cellH = (h - gridTop - pad - gap) / 2f
        text.textAlign = Paint.Align.LEFT
        entries.forEachIndexed { i, entry ->
            val l = pad + (i % 2) * (cellW + gap)
            val t = gridTop + (i / 2) * (cellH + gap)
            drawKey(canvas, l, t, l + cellW, t + cellH, card, Focus.CLIPBOARD_CARD)
            text.color = labelShade(Focus.TEXT)
            text.textSize = dp(12f)
            text.typeface = Typeface.DEFAULT
            val shown = text.breakText(entry, true, cellW - dp(20f), null)
            canvas.drawText(entry, 0, shown, l + dp(10f), t + cellH / 2 + dp(4f), text)
        }
        text.textAlign = Paint.Align.CENTER
    }
}
