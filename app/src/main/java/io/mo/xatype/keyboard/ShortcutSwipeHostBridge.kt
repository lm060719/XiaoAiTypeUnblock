package io.mo.xatype.keyboard

import android.graphics.Canvas
import android.graphics.Paint
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
class ShortcutSwipeHostBridge(private val loader: ClassLoader, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_SWIPE)) {
    private fun type(name: String) = abi.type(name)
    private val keys = KeyboardShortcutHostLayout(loader, abi)
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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun label(instance: Any) = keys.label(instance)
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

    /**
     * Draw the action text after the key's own content. [offsetX] is measured from the
     * horizontal centre and [bottomInset] from the key's bottom edge, both in pixels.
     */
    fun drawLabel(scope: Any, text: String, color: Int, textSizePx: Float, offsetX: Float, bottomInset: Float) {
        content.invoke(scope)
        if (text.isEmpty() || textSizePx <= 0f) return
        val size = drawSize.invoke(scope) as Long
        val width = Float.fromBits((size shr 32).toInt())
        val height = Float.fromBits(size.toInt())
        if (width <= 0f || height <= 0f) return
        val canvas = nativeCanvas.get(canvasGetter.invoke(context.invoke(scope))) as Canvas
        labelPaint.textSize = textSizePx
        val measured = labelPaint.measureText(text)
        if (measured > width * 0.92f && measured > 0f) {
            labelPaint.textSize = textSizePx * (width * 0.92f) / measured
        }
        labelPaint.color = color
        val metrics = labelPaint.fontMetrics
        val half = labelPaint.measureText(text) / 2f
        val x = (width / 2f + offsetX).coerceIn(half.coerceAtMost(width / 2f), (width - half).coerceAtLeast(width / 2f))
        val minBaseline = minOf(-metrics.ascent + 1f, height - 1f)
        val baseline = (height - bottomInset).coerceIn(minBaseline, height - 1f)
        canvas.drawText(text, x, baseline, labelPaint)
    }
}
