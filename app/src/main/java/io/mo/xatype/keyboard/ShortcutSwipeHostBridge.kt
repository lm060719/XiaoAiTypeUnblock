package io.mo.xatype.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.max

/** The resolved Compose ABI. No Compose runtime is bundled with the module. */
class ShortcutSwipeHostBridge(private val loader: ClassLoader, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_SWIPE),
                              mapping: (ShortcutKeyboard) -> Map<String, String> = { it.defaults }) {
    private fun type(name: String) = abi.type(name)
    private val keys = KeyboardShortcutHostLayout(loader, abi, mapping)
    val key = keys.key
    val modifier = type("e1.o")
    val callback = type("bd.b")
    private val invoke = callback.abiMethod(abi, "g", Any::class.java)
    private val unit = type("nc.m").abiField(abi, "a").get(null)
    private val state = type("s0.v0")
    private val getValue = type("s0.h2").abiMethod(abi, "getValue")
    private val setValue = state.abiMethod(abi, "setValue", Any::class.java)
    private val policy = type("s0.o0").abiField(abi, "f").get(null)
    private val stateFactory = type("s0.d").abiMethod(abi, "R", Any::class.java, type("s0.c2"))
    private val position = type("androidx.compose.ui.layout.a").abiMethod(abi, "b", modifier, callback)
    private val drawing = type("androidx.compose.ui.draw.a").abiMethod(abi, "c", modifier, callback)
    private val localView = type("d2.u0").abiField(abi, "f").get(null)
    private val readLocal = type("s0.p").abiMethod(abi, "l", type("s0.i1"))
    private val coordinates = type("a2.o")
    private val attached = coordinates.abiMethod(abi, "i")
    private val toScreen = coordinates.abiMethod(abi, "v", Long::class.javaPrimitiveType)
    private val coordinateSize = coordinates.abiMethod(abi, "l")
    private val scope = type("c2.f0")
    private val content = scope.abiMethod(abi, "a")
    private val drawSize = scope.abiMethod(abi, "c")
    private val context = scope.abiMethod(abi, "S")
    private val canvasGetter = context.returnType.abiMethod(abi, "s")
    private val nativeCanvas = type("l1.b").abiField(abi, "a").apply { isAccessible = true }
    private val argb = type("l1.h0").abiMethod(abi, "v", Long::class.javaPrimitiveType)
    // Created on first draw: typefaces are unavailable outside an app process (binding probes).
    private val labelPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    } }
    private val labelGlyphBounds by lazy { Rect() }

    fun label(instance: Any) = keys.label(instance)
    fun letter(instance: Any) = keys.letter(instance)
    fun action(instance: Any, layout: KeyboardShortcutLayout) = keys.action(instance, layout)
    fun actionKey(source: Any, action: String) = keys.actionKey(source, action)
    fun dispatch(target: Any, key: Any) { invoke.invoke(target, key) }
    fun view(composer: Any): View = readLocal.invoke(composer, localView) as View
    fun progressState(): Any = checkNotNull(stateFactory.invoke(null, 0f, policy))
    fun progress(value: Any) = (getValue.invoke(value) as Number).toFloat()
    fun progress(value: Any, progress: Float) { setValue.invoke(value, progress) }
    fun argb(value: Long) = argb.invoke(null, value) as Int
    fun drawContent(value: Any) { content.invoke(value) }

    private fun function(block: (Any) -> Unit): Any = Proxy.newProxyInstance(loader, arrayOf(callback)) { proxy, method, args ->
        when (abi.canonical(method)) {
            "g" -> { block(args!![0]!!); unit }
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "XatypeSwipeCallback"
            else -> error("Unexpected swipe callback ${method.name}")
        }
    }
    private inner class Guard(val original: Any, val blocked: () -> Boolean) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (abi.canonical(method)) {
            "g" -> if (blocked()) unit else this@ShortcutSwipeHostBridge.invoke.invoke(original, args!![0])
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "XatypeSwipeKeyCallback"
            else -> error("Unexpected key callback ${method.name}")
        }
    }
    fun unwrap(value: Any): Any {
        if (!Proxy.isProxyClass(value.javaClass)) return value
        val guard = Proxy.getInvocationHandler(value) as? Guard ?: return value
        return guard.original
    }
    fun guarded(original: Any, blocked: () -> Boolean): Any =
        Proxy.newProxyInstance(loader, arrayOf(callback), Guard(unwrap(original), blocked))
    fun positioned(original: Any, block: (Any) -> Unit): Any = checkNotNull(position.invoke(null, original, function(block)))
    fun drawing(original: Any, block: (Any) -> Unit): Any = checkNotNull(drawing.invoke(null, original, function(block)))
    fun bounds(value: Any): RectF? {
        if (attached.invoke(value) != true) return null
        val size = coordinateSize.invoke(value) as Long
        val width = (size shr 32).toInt().toFloat()
        val height = size.toInt().toFloat()
        val corners = listOf(0f to 0f, width to 0f, width to height, 0f to height).map { (x, y) ->
            val packed = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xffffffffL)
            val result = toScreen.invoke(value, packed) as Long
            Float.fromBits((result shr 32).toInt()) to Float.fromBits(result.toInt())
        }
        return RectF(corners.minOf { it.first }, corners.minOf { it.second },
            corners.maxOf { it.first }, corners.maxOf { it.second }).takeIf {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() && !it.isEmpty
        }
    }
    fun draw(scope: Any, state: Any, action: String, color: Int, iconSize: Float) {
        val progress = progress(state) // Read under Compose's draw observer, so moves invalidate this node.
        if (progress <= 0f) { content.invoke(scope); return }
        val size = drawSize.invoke(scope) as Long
        val width = Float.fromBits((size shr 32).toInt())
        val height = Float.fromBits(size.toInt())
        val canvas = nativeCanvas.get(canvasGetter.invoke(context.invoke(scope))) as Canvas
        val save = canvas.saveLayerAlpha(0f, 0f, max(width, iconSize), max(height, iconSize), ((1f - progress) * 255f).toInt())
        try { content.invoke(scope) } finally { canvas.restoreToCount(save) }
        ShortcutGlyph.draw(canvas, action, width / 2f, height / 2f, iconSize, color, progress)
    }

    /** The drawn key's height in pixels. */
    fun keyHeight(scope: Any): Float = Float.fromBits((drawSize.invoke(scope) as Long).toInt())

    /**
     * Draw [text] after the key's content, avoiding the native letter's measured layout in landscape.
     * [offsetX] is relative to the letter centre, and [bottomInset] is relative to the key bottom.
     */
    fun drawLabel(scope: Any, text: String, color: Int, textSizePx: Float, offsetX: Float, bottomInset: Float,
                  referenceHeight: Float, keyBounds: RectF?, letterBounds: RectF?, landscape: Boolean, density: Float,
                  minimumTextSizePx: Float) {
        content.invoke(scope)
        if (text.isEmpty() || textSizePx <= 0f) return
        val size = drawSize.invoke(scope) as Long
        val width = Float.fromBits((size shr 32).toInt())
        val height = Float.fromBits(size.toInt())
        if (width <= 0f || height <= 0f) return
        val scale = if (referenceHeight > 0f) (height / referenceHeight).coerceIn(MIN_LABEL_SCALE, 1f) else 1f
        labelPaint.textSize = textSizePx * scale
        val measured = labelPaint.measureText(text)
        val maxLabelWidth = width * MAX_LABEL_WIDTH_FRACTION
        if (measured > maxLabelWidth) labelPaint.textSize *= maxLabelWidth / measured

        val position = if (landscape) {
            val letter = localLetterBounds(keyBounds, letterBounds, width, height)
            val edgeInset = LABEL_EDGE_INSET_DP * density * scale
            val gap = LABEL_LETTER_GAP_DP * density * scale
            fun placement() = ShortcutLabelPosition.place(width, height, letter, glyphBounds(text),
                offsetX * scale, bottomInset * scale, edgeInset, gap)

            placement() ?: run {
                val compactSize = minimumTextSizePx * scale
                if (labelPaint.textSize <= compactSize) return
                labelPaint.textSize = compactSize
                placement() ?: return
            }
        } else {
            val center = keyBounds?.let { ShortcutLabelPosition.centerFraction(it.left, it.right, letterBounds?.centerX()) }
                ?: 0.5f
            ShortcutLabelPosition.portraitPlacement(width, height, center, labelPaint.measureText(text) / 2f,
                labelPaint.ascent(), offsetX * scale, bottomInset * scale)
        }
        labelPaint.color = color
        val canvas = nativeCanvas.get(canvasGetter.invoke(context.invoke(scope))) as Canvas
        canvas.drawText(text, position.x, position.baseline, labelPaint)
    }

    private fun glyphBounds(text: String): ShortcutLabelPosition.Bounds {
        val halfAdvance = labelPaint.measureText(text) / 2f
        labelPaint.getTextBounds(text, 0, text.length, labelGlyphBounds)
        return ShortcutLabelPosition.Bounds(minOf(labelGlyphBounds.left.toFloat(), 0f) - halfAdvance,
            labelGlyphBounds.top.toFloat(), maxOf(labelGlyphBounds.right - halfAdvance, halfAdvance),
            labelGlyphBounds.bottom.toFloat())
    }

    private fun localLetterBounds(key: RectF?, letter: RectF?, width: Float,
                                  height: Float): ShortcutLabelPosition.Bounds? {
        if (key == null || letter == null || !key.contains(letter.centerX(), letter.centerY())) return null
        val scaleX = width / key.width()
        val scaleY = height / key.height()
        val center = width * ShortcutLabelPosition.centerFraction(key.left, key.right, letter.centerX())
        val halfLetterWidth = letter.width() * scaleX / 2f
        return ShortcutLabelPosition.Bounds(center - halfLetterWidth, (letter.top - key.top) * scaleY,
            center + halfLetterWidth, (letter.bottom - key.top) * scaleY)
    }

    private companion object {
        /** Floating keys are about two thirds of the docked height; never shrink below half. */
        const val MIN_LABEL_SCALE = 0.5f
        const val MAX_LABEL_WIDTH_FRACTION = 0.92f
        // The native keycap is inset from the touch bounds, including at rounded corners.
        const val LABEL_EDGE_INSET_DP = 6f
        const val LABEL_LETTER_GAP_DP = 1f
    }
}
