package io.mo.xatype.keyboard18

import android.inputmethodservice.InputMethodService
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.keyboard.HostEngine
import io.mo.xatype.shape.ShapeHostBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Reuse the full fingerprinted engine ABI validation, with independent 18-key sessions. */
class Keyboard18HostBridge(private val loader: ClassLoader, private val profile: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)) {
    private val abi = ShapeHostBridge(loader, profile)
    val host: HostEngine = abi.host
    val engineType = abi.engineType
    val baseInterface = abi.baseInterface
    val pinyin = abi.pinyin
    private val extended = host.extendedInterface

    fun result(value: Pinyin18Result): Any = host.result(value.consumed, value.display,
        value.candidates.take(PAGE_SIZE).mapIndexed(::candidate), value.commit, value.raw)
    internal fun state(engine: Keyboard18Engine): Any {
        if (engine.raw.isEmpty()) return host.idle
        // The host sends Composing.rawInput to editors such as search boxes, and also
        // uses it for literal commits. Private-use tap codes would render as tofu.
        // Keep those codes in the engine / acceptedRawInput for decoding and restore.
        val display = engine.display
        return host.composing(display, display)
    }
    private fun candidate(index: Int, value: Pinyin18Candidate): Any =
        host.candidate(value.text, value.spelling, 1000 - index, value.text)
    fun create(engine: Keyboard18Engine, service: InputMethodService, onInput: () -> Unit): Any =
        Proxy.newProxyInstance(loader, arrayOf(extended), Session(engine, service, onInput))
    fun session(proxy: Any?): Session? = if (proxy != null && Proxy.isProxyClass(proxy.javaClass))
        Proxy.getInvocationHandler(proxy) as? Session else null

    inner class Session(val engine: Keyboard18Engine, var service: InputMethodService, val onInput: () -> Unit) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            // From 0.2.1358 the host calls engines on its engine thread; key hooks read state on main.
            return synchronized(engine) { dispatch(proxy, profile.canonical(method), method, arguments) }
        }

        private fun dispatch(proxy: Any, name: String, method: java.lang.reflect.Method, arguments: Array<out Any?>): Any? = when (name) {
            "equals" -> proxy === arguments[0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Keyboard18Engine(resolved ABI)"
            "a" -> pinyin
            "b" -> engine.select(arguments[0] as Int).orEmpty()
            "clear", "destroy", "t" -> { engine.clear(); null }
            // A flush sent through the session commits every pending syllable (see HostEngine.FLUSH).
            "d" -> if (arguments[0] == HostEngine.FLUSH) result(engine.snapshot(commit = engine.commitAll()))
                else result(engine.restore(arguments[0] as String))
            "e" -> false
            "f" -> state(engine)
            // Tapping a reading in the expanded page's pinyin column; the host applies only consumed results.
            "r" -> result(engine.choosePinyin(arguments[0] as Int))
            "g", "filterSingleChar", "insertAtCursor", "deleteAtCursor" -> result(engine.snapshot(false))
            "singleCharFilterSupported", "cursorEditing" -> false
            "engineName" -> "xatype-keyboard18"
            "segmentState" -> 0
            "i" -> { onInput(); result(engine.input((arguments[0] as Int).toChar())) }
            "j" -> ""
            "k" -> result(engine.backspace())
            "o" -> { onInput(); result(engine.inputText(arguments[0] as String)) }
            "q" -> { service = arguments[0] as InputMethodService; null }
            "u", "l", "p" -> null
            "s" -> engine.candidates.take(PAGE_SIZE).mapIndexed(::candidate)
            "c" -> result(engine.input('\''))
            "h" -> { onInput(); result(engine.input(arguments[0] as Char)) }
            "m" -> {
                val start = arguments[0] as Int
                val count = (arguments[1] as Int).coerceIn(0, 60)
                if (start < 0 || start >= engine.candidates.size) emptyList<Any>() else
                    engine.candidates.drop(start).take(count).mapIndexed { index, entry -> candidate(start + index, entry) }
            }
            "n" -> {
                val selected = engine.select(arguments[0] as Int)
                host.pair(selected.orEmpty(), selected != null)
            }
            "v" -> engine.pinyinVariants()
            else -> error("Unsupported 18-key host method: ${method.name}")
        }
    }
    companion object { private const val PAGE_SIZE = 20 }
}
