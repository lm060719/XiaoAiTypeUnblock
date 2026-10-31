package io.mo.xatype.keyboard18

import android.inputmethodservice.InputMethodService
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.shape.ShapeHostBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Reuse the full fingerprinted engine ABI validation, with independent 18-key sessions. */
class Keyboard18HostBridge(private val loader: ClassLoader, private val profile: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)) {
    private fun type(name: String) = profile.type(name)
    private val abi = ShapeHostBridge(loader, profile)
    val engineType = abi.engineType
    val baseInterface = abi.baseInterface
    val pinyin = abi.pinyin
    private val extended = type("z8.p")
    private val string = String::class.java
    private val int = Int::class.javaPrimitiveType!!
    private val bool = Boolean::class.javaPrimitiveType!!
    private val source = type("z8.b").enumConstants.single { (it as Enum<*>).name == "SYSTEM" }
    private val idle = type("z8.t").let { cls -> cls.declaredFields.single {
        Modifier.isStatic(it.modifiers) && it.type == cls }.apply { isAccessible = true }.get(null) }
    private val candidateConstructor = type("z8.a").getConstructor(string, string, int, type("z8.b"), string)
    private val resultConstructor = type("z8.r").getConstructor(bool, string, List::class.java, string,
        int, List::class.java, string)
    private val stateConstructor = type("z8.s").getConstructor(int, string, string, List::class.java)
    private val pairConstructor = type("nc.f").getConstructor(Any::class.java, Any::class.java)

    fun result(value: Pinyin18Result): Any = resultConstructor.newInstance(value.consumed, value.display,
        value.candidates.take(PAGE_SIZE).mapIndexed(::candidate), value.commit, 0, emptyList<Any>(), value.raw)
    private fun candidate(index: Int, value: Pinyin18Candidate): Any = candidateConstructor.newInstance(
        value.text, value.spelling, 1000 - index, source, value.text)
    fun create(engine: Keyboard18Engine, service: InputMethodService, onInput: () -> Unit): Any =
        Proxy.newProxyInstance(loader, arrayOf(extended), Session(engine, service, onInput))
    fun session(proxy: Any?): Session? = if (proxy != null && Proxy.isProxyClass(proxy.javaClass))
        Proxy.getInvocationHandler(proxy) as? Session else null

    inner class Session(val engine: Keyboard18Engine, var service: InputMethodService, val onInput: () -> Unit) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            return when (profile.canonical(method)) {
                "equals" -> proxy === arguments[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Keyboard18Engine(resolved ABI)"
                "a" -> pinyin
                "b" -> engine.select(arguments[0] as Int).orEmpty()
                "clear", "destroy", "t" -> { engine.clear(); null }
                "d" -> result(engine.restore(arguments[0] as String))
                "e" -> false
                "f" -> if (engine.raw.isEmpty()) idle else stateConstructor.newInstance(8,
                    engine.raw, engine.display, emptyList<Any>())
                "g", "r", "filterSingleChar" -> result(engine.snapshot(false))
                "singleCharFilterSupported" -> false
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
                    pairConstructor.newInstance(selected.orEmpty(), selected != null)
                }
                "v" -> engine.pinyinVariants()
                else -> error("Unsupported 18-key host method: ${method.name}")
            }
        }
    }
    companion object { private const val PAGE_SIZE = 20 }
}
