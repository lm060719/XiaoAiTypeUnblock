package io.mo.xatype.hooks

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.keyboard.KeyboardShortcutLayout
import io.mo.xatype.keyboard.ShortcutSwipeGesture
import io.mo.xatype.keyboard.ShortcutSwipeHostBridge
import io.mo.xatype.util.XposedUtils
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.min

/** Bind to actual Compose key coordinates; cancel native input only once a downward drag wins. */
class ShortcutSwipeHostHook(private val enabled: () -> Boolean, private val layout: () -> KeyboardShortcutLayout?) {
    private class Key(val owner: WeakReference<View>, var source: Any, val action: String,
                      var callback: Any, val state: Any, val layout: KeyboardShortcutLayout) {
        var coordinates: WeakReference<Any>? = null
        var originalModifier: Any? = null
        var decoratedModifier: Any? = null
        var width = Float.MAX_VALUE
        var blockNative = false
    }
    private class Touch(val key: Key, val pointer: Int, val x: Float, val y: Float, val density: Float,
                        val gesture: ShortcutSwipeGesture = ShortcutSwipeGesture()) {
        var nativeCancelled = false
        var haptic = false
    }
    private val keys = WeakHashMap<View, MutableMap<String, Key>>()
    private val rendering = ThreadLocal<Key?>()
    private var touch: Touch? = null
    private var drawFailureReported = false

    fun install(module: XposedInterface, loader: ClassLoader) {
        val abi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_SWIPE)
        val bridge = ShortcutSwipeHostBridge(loader, abi)
        val renderer = abi.namedMethods(abi.type("z9.w5"), "a").single {
            it.parameterCount >= 30 && it.parameterTypes.first() == bridge.key
        }
        // 0.2.1246 inserted two parameters ahead of onKeyPress, so positions come from types:
        // onKeyPress is the first key callback, and Compose's $changed ints follow the composer
        // with three bits per parameter, ten parameters per int.
        val callbackIndex = renderer.parameterTypes.indexOfFirst { it == abi.type("bd.b") }
        val composerIndex = renderer.parameterTypes.indexOfFirst { it == abi.type("s0.p") }
        check(callbackIndex > MODIFIER_INDEX && composerIndex > callbackIndex) { "key renderer layout" }
        fun changedIndex(parameter: Int) = composerIndex + 1 + parameter / 10
        fun changedShift(parameter: Int) = 1 + 3 * (parameter % 10)
        val text = abi.namedMethods(abi.type("p0.n"), "a").single {
            it.parameterCount == 19 && it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == bridge.modifier
        }
        val dispatch = abi.type("d2.w").abiMethod(abi, "dispatchTouchEvent", MotionEvent::class.java)
        module.hook(renderer).intercept { chain ->
            if (drawFailureReported || !enabled()) return@intercept chain.proceed()
            val source = chain.getArg(0)!!
            val currentLayout = layout() ?: return@intercept chain.proceed()
            val action = bridge.action(source, currentLayout) ?: return@intercept chain.proceed()
            val owner = bridge.view(chain.getArg(composerIndex)!!)
            val id = "${currentLayout}:${source.javaClass.abiField(abi, "a").getInt(source)}:${bridge.label(source)}"
            val key = keys.getOrPut(owner) { LinkedHashMap() }.getOrPut(id) {
                Key(WeakReference(owner), source, action, bridge.unwrap(chain.getArg(callbackIndex)!!), bridge.progressState(), currentLayout)
            }
            key.source = source
            key.callback = bridge.unwrap(chain.getArg(callbackIndex)!!)
            val args = chain.args.toTypedArray()
            if (args[MODIFIER_INDEX] !== key.decoratedModifier && args[MODIFIER_INDEX] !== key.originalModifier) {
                key.originalModifier = args[MODIFIER_INDEX]
                key.decoratedModifier = bridge.positioned(args[MODIFIER_INDEX]!!) {
                    key.coordinates = WeakReference(it)
                    key.width = bridge.bounds(it)?.width() ?: Float.MAX_VALUE
                }
            }
            args[MODIFIER_INDEX] = key.decoratedModifier ?: args[MODIFIER_INDEX]
            args[callbackIndex] = bridge.guarded(key.callback) { key.blockNative }
            // Re-evaluate the injected modifier and callback without changing the renderer's slot layout.
            for (parameter in listOf(MODIFIER_INDEX, callbackIndex)) {
                val index = changedIndex(parameter)
                args[index] = markDifferent(args[index] as Int, changedShift(parameter))
            }
            val previous = rendering.get()
            rendering.set(key)
            try { chain.proceed(args) } finally {
                if (previous == null) rendering.remove() else rendering.set(previous)
            }
        }
        module.deoptimize(renderer)
        module.hook(text).intercept { chain ->
            val key = rendering.get() ?: return@intercept chain.proceed()
            val label = (chain.getArg(0) as String).filterNot(Char::isWhitespace)
            if (!label.equals(bridge.label(key.source).filterNot(Char::isWhitespace), ignoreCase = true))
                return@intercept chain.proceed()
            val args = chain.args.toTypedArray()
            val color = bridge.argb(args[2] as Long)
            args[1] = bridge.drawing(args[1]!!) { scope ->
                if (drawFailureReported) { bridge.drawContent(scope); return@drawing }
                try {
                    val density = key.owner.get()?.resources?.displayMetrics?.density ?: 1f
                    bridge.draw(scope, key.state, key.action, color, min(ShortcutSwipeGesture.ICON_SIZE * density, key.width * 0.65f))
                } catch (failure: Throwable) {
                    if (!drawFailureReported) {
                        drawFailureReported = true
                        XposedUtils.logError(module, "Swipe shortcut drawing unavailable", failure)
                    }
                    bridge.drawContent(scope)
                }
            }
            args[16] = markDifferent(args[16] as Int, TEXT_MODIFIER_SHIFT)
            chain.proceed(args)
        }
        module.deoptimize(text)
        module.hook(dispatch).intercept { chain ->
            val view = chain.thisObject as View
            val event = chain.getArg(0) as MotionEvent
            fun reset() {
                touch?.let { current ->
                    bridge.progress(current.key.state, 0f)
                    // Let pending native cancellation callbacks drain before accepting a fresh callback.
                    current.key.owner.get()?.post { current.key.blockNative = false }
                }
                touch = null
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                reset()
                if (!drawFailureReported && enabled() && event.pointerCount == 1) {
                    val currentLayout = layout()
                    val key = keys[view]?.values?.lastOrNull { candidate ->
                        candidate.layout == currentLayout && candidate.coordinates?.get()?.let {
                            bridge.bounds(it)?.contains(event.rawX, event.rawY)
                        } == true
                    }
                    if (key != null) {
                        key.blockNative = false
                        touch = Touch(key, event.getPointerId(0), event.rawX, event.rawY,
                            view.resources.displayMetrics.density)
                    }
                }
                return@intercept chain.proceed()
            }
            val current = touch?.takeIf { it.key.owner.get() === view } ?: return@intercept chain.proceed()
            val nativeWasCancelled = current.nativeCancelled
            if (drawFailureReported || !enabled() || current.key.layout != layout() || event.pointerCount != 1 ||
                event.actionMasked == MotionEvent.ACTION_CANCEL || event.findPointerIndex(current.pointer) < 0) {
                current.gesture.cancel()
                bridge.progress(current.key.state, 0f)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_UP) reset()
                return@intercept if (nativeWasCancelled) true else chain.proceed()
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                current.gesture.move((event.rawX - current.x) / current.density, (event.rawY - current.y) / current.density)
                if (current.gesture.dragging && !current.nativeCancelled) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    current.nativeCancelled = true
                    current.key.blockNative = true
                    try { chain.proceed(arrayOf(cancel)) } finally { cancel.recycle() }
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                bridge.progress(current.key.state, current.gesture.progress)
                if (current.gesture.armed && !current.haptic) {
                    current.haptic = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val result = current.gesture.release()
                    val cancelled = current.nativeCancelled
                    reset()
                    if (result == ShortcutSwipeGesture.Release.ACTION) runCatching {
                        bridge.dispatch(current.key.callback, bridge.actionKey(current.key.source, current.key.action))
                    }.onFailure { XposedUtils.logError(module, "Swipe shortcut failed: ${current.key.action}", it) }
                    return@intercept if (cancelled) true else chain.proceed()
                }
            }
            if (current.nativeCancelled) true else chain.proceed()
        }
        module.deoptimize(dispatch)
    }

    private companion object {
        /** The key's Modifier, unmoved in every verified renderer. */
        const val MODIFIER_INDEX = 2
        /** Text's Modifier is its second parameter. */
        const val TEXT_MODIFIER_SHIFT = 1 + 3 * 1

        /**
         * Compose only calls changed(), which consumes a slot, for parameters the caller left uncertain.
         * Clearing a caller-certified state to uncertain adds slots on hooked keys only, and a host key
         * group reused by an unhooked key (nine-key "ABC" -> number "2") then reads misaligned slots.
         * Mark certified parameters Different instead: the slot layout stays and the value is re-read.
         */
        fun markDifferent(changed: Int, shift: Int): Int {
            val state = (changed shr shift) and 0x7
            if (state and 0x3 == 0) return changed
            return (changed and (0x7 shl shift).inv()) or (((state and 0x4) or 0x2) shl shift)
        }
    }
}
