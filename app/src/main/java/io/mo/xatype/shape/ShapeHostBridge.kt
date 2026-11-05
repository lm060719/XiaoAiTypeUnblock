package io.mo.xatype.shape

import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.keyboard.HostEngine
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Resolve and validate the fingerprinted ABI before replacing a host engine. */
class ShapeHostBridge(private val loader: ClassLoader, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)) {
    val host = HostEngine(abi)
    val engineType = host.engineType
    val baseInterface = host.baseInterface
    private val extendedInterface = host.extendedInterface
    private val resultType = host.resultType
    private val stateType = host.stateType
    private val pairType = host.pairType
    private val intType = Int::class.javaPrimitiveType!!
    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val voidType = Void.TYPE
    private val stringType = String::class.java
    private val listType = List::class.java
    val pinyin: Any = host.pinyin
    private val idle = host.idle

    init {
        require(baseInterface.isInterface && extendedInterface.isInterface &&
            baseInterface.isAssignableFrom(extendedInterface) && stateType.isInstance(idle))
        val expected = linkedMapOf(
            "a" to (engineType to emptyArray()), "b" to (stringType to arrayOf(intType)),
            "clear" to (voidType to emptyArray()), "d" to (resultType to arrayOf(stringType)),
            "destroy" to (voidType to emptyArray()), "e" to (booleanType to emptyArray()),
            "f" to (stateType to emptyArray()), "g" to (resultType to arrayOf(listType)),
            "i" to (resultType to arrayOf(intType)), "j" to (stringType to emptyArray()),
            "k" to (resultType to emptyArray()), "o" to (resultType to arrayOf(stringType)),
            "q" to (voidType to arrayOf(abi.type("com.mi.ime.MiInputMethodService"), abi.type("z8.e"))),
            "s" to (listType to emptyArray()), "t" to (voidType to emptyArray()),
            "u" to (voidType to arrayOf(abi.type("y8.b"))), "c" to (resultType to emptyArray()),
            "h" to (resultType to arrayOf(Char::class.javaPrimitiveType!!)),
            "l" to (voidType to arrayOf(intType)), "m" to (listType to arrayOf(intType, intType)),
            "n" to (pairType to arrayOf(intType, stringType)), "p" to (voidType to arrayOf(intType)),
            "r" to (resultType to arrayOf(intType)), "v" to (listType to emptyArray())
        )
        // Members added by later hosts; these proxies never offer the features behind them.
        val optional = linkedMapOf(
            // 0.2.1246: the "全部/单字" candidate filter.
            "singleCharFilterSupported" to (booleanType to emptyArray()),
            "filterSingleChar" to (resultType to arrayOf(booleanType)),
            // 0.2.1358: editing inside the composition, engine name and segment state.
            "insertAtCursor" to (resultType to arrayOf(Char::class.javaPrimitiveType!!, intType)),
            "cursorEditing" to (booleanType to emptyArray()),
            "engineName" to (stringType to emptyArray()),
            "deleteAtCursor" to (resultType to arrayOf(intType)),
            "segmentState" to (intType to emptyArray<Class<*>>())
        )
        optional.forEach { (name, signature) ->
            if (abi.namedMethods("z8.q", name).isNotEmpty() || abi.namedMethods("z8.p", name).isNotEmpty())
                expected[name] = signature
        }
        require(extendedInterface.methods.size == expected.size) { "形码接口数量不匹配" }
        expected.forEach { (name, signature) ->
            require(extendedInterface.abiMethod(abi, name, *signature.second).returnType == signature.first) {
                "形码接口签名不匹配：$name"
            }
        }
        // Exercise constructors before the native factory is bypassed.
        candidate(ShapeEntry("测试", "a"))
        result(ShapeResult(true, "", emptyList()))
        host.composing("a", "a")
        host.pair("", false)
    }

    private fun candidate(entry: ShapeEntry): Any = host.candidate(
        entry.text, entry.code, entry.weight.coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt(), entry.text)

    fun result(value: ShapeResult): Any = host.result(value.consumed, value.code,
        value.candidates.take(PAGE_SIZE).map(::candidate), value.commit, value.code)

    fun create(engine: ShapeEngine, onInput: () -> Unit = {}): Any = Proxy.newProxyInstance(
        loader, arrayOf(extendedInterface), Session(engine, onInput))

    fun session(proxy: Any?): ShapeEngine? = if (proxy != null && Proxy.isProxyClass(proxy.javaClass))
        (Proxy.getInvocationHandler(proxy) as? Session)?.engine else null

    fun input(proxy: Any, key: Char): ShapeResult {
        val handler = Proxy.getInvocationHandler(proxy) as Session
        handler.onInput()
        return synchronized(handler.engine) { handler.engine.input(key) }
    }

    private inner class Session(val engine: ShapeEngine, val onInput: () -> Unit) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val arg = args.orEmpty()
            // From 0.2.1358 the host calls engines on its engine thread.
            return synchronized(engine) { dispatch(proxy, abi.canonical(method), method, arg) }
        }

        private fun dispatch(proxy: Any, name: String, method: java.lang.reflect.Method, arg: Array<out Any?>): Any? = when (name) {
            "equals" -> proxy === arg[0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ShapeEngine(resolved ABI)"
            "a" -> pinyin
            "b" -> engine.select(arg[0] as Int).orEmpty()
            "clear", "destroy", "t" -> { engine.clear(); null }
            "d" -> result(engine.replaceCode(arg[0] as String))
            "e" -> false // No prediction or resurrection of previously committed codes.
            "f" -> if (engine.code.isEmpty()) idle else host.composing(engine.code, engine.code)
            "g" -> result(engine.snapshot(false))
            "i" -> { onInput(); result(engine.input((arg[0] as Int).toChar())) }
            "j" -> "" // Host's T9-only buffer.
            "k" -> result(engine.backspace())
            "o" -> { onInput(); result(engine.inputText(arg[0] as String)) }
            "q", "u", "l", "p" -> null
            "s" -> engine.candidates.take(PAGE_SIZE).map(::candidate)
            "c" -> result(engine.input('\''))
            "h" -> { onInput(); result(engine.input(arg[0] as Char)) }
            "m" -> {
                val start = arg[0] as Int
                val count = (arg[1] as Int).coerceIn(0, 60)
                if (start < 0 || start >= engine.candidates.size) emptyList<Any>()
                else engine.candidates.subList(start, start + minOf(count, engine.candidates.size - start)).map(::candidate)
            }
            "n" -> {
                val selected = engine.select(arg[0] as Int)
                host.pair(selected.orEmpty(), selected != null)
            }
            "r", "filterSingleChar", "insertAtCursor", "deleteAtCursor" -> result(engine.snapshot(false))
            "singleCharFilterSupported", "cursorEditing" -> false
            "engineName" -> "xatype-shape"
            "segmentState" -> 0
            "v" -> emptyList<Any>()
            else -> error("Unexpected host method: ${method.name}")
        }
    }

    companion object { const val PAGE_SIZE = 20 }
}
