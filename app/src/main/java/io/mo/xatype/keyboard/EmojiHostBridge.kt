package io.mo.xatype.keyboard

import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod
import io.mo.xatype.shape.ShapeHostBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Emoji decoration and post-commit prediction control share the resolved native engine. */
class EmojiHostBridge(
    private val loader: ClassLoader,
    private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
) {
    private val checked = ShapeHostBridge(loader, abi)
    val engineType = checked.engineType
    val baseInterface = checked.baseInterface
    private val extended = abi.type("z8.p")
    private val composing = abi.type("z8.s")
    private val source = abi.type("z8.b").enumConstants.single { (it as Enum<*>).name == "SYSTEM" }
    private val candidateConstructor = abi.type("z8.a").getConstructor(String::class.java,
        String::class.java, Int::class.javaPrimitiveType, abi.type("z8.b"), String::class.java)
    private val pairConstructor = abi.type("nc.f").getConstructor(Any::class.java, Any::class.java)
    private val resultType = abi.type("z8.r")
    private val resultConstructor = resultType.getConstructor(Boolean::class.javaPrimitiveType,
        String::class.java, List::class.java, String::class.java, Int::class.javaPrimitiveType,
        List::class.java, String::class.java)
    private val getState = baseInterface.abiMethod(abi, "f")
    private val getMode = baseInterface.abiMethod(abi, "a")
    private val predicting = baseInterface.abiMethod(abi, "e")
    private val clear = baseInterface.abiMethod(abi, "clear")
    private val page = extended.abiMethod(abi, "m", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val textField = fieldWithValue(candidateConstructor.newInstance(
        "emoji-probe-text", "emoji-probe-note", 1, source, "emoji-probe-display"), "emoji-probe-text")
    private val rawField = fieldWithValue(composing.getConstructor(Int::class.javaPrimitiveType,
        String::class.java, String::class.java, List::class.java).newInstance(
        0, "emoji-probe-raw", "emoji-probe-display", emptyList<Any>()), "emoji-probe-raw")
    private val pairProbe = pairConstructor.newInstance("selection-probe", true)
    private val commitField = fieldWithValue(pairProbe, "selection-probe")
    private val acceptedField = fieldWithValue(pairProbe, true)
    private val resultProbeValues = arrayOf(true, "composing-probe", listOf("candidates-probe"),
        "commit-probe", 17, listOf("errors-probe"), "accepted-probe")
    private val resultProbe = resultConstructor.newInstance(*resultProbeValues)
    private val resultFields = resultProbeValues.map { fieldWithValue(resultProbe, it) }

    private fun fieldWithValue(instance: Any, value: Any) = instance.javaClass.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        .single { it.get(instance) == value }

    fun supports(engine: Any): Boolean = extended.isInstance(engine) && !Proxy.isProxyClass(engine.javaClass)

    companion object {
        /** The native engine behind an emoji decoration, so diagnostics report the real class. */
        fun unwrap(engine: Any): Any =
            (if (Proxy.isProxyClass(engine.javaClass)) Proxy.getInvocationHandler(engine) as? EmojiHostBridge.Session else null)
                ?.engine ?: engine
    }

    fun create(engine: Any, dictionary: () -> EmojiCandidates?, enabled: () -> Boolean,
               scheme: () -> ShuangpinScheme? = { null },
               prediction: () -> PredictionMode = { PredictionMode.KEEP },
               predictionFailed: (Throwable) -> Unit = {},
               observe: (String) -> Unit = {},
               failed: (Throwable) -> Unit): Any = Proxy.newProxyInstance(loader, arrayOf(extended),
        Session(engine, dictionary, enabled, scheme, prediction, predictionFailed, observe, failed))

    private inner class Session(
        val engine: Any, val dictionary: () -> EmojiCandidates?, val enabled: () -> Boolean,
        val scheme: () -> ShuangpinScheme?, val prediction: () -> PredictionMode,
        val predictionFailed: (Throwable) -> Unit, val observe: (String) -> Unit, val failed: (Throwable) -> Unit
    ) : InvocationHandler {
        private var nativeSize = 0
        private var head = emptyList<Any>()
        private var indices = emptyList<Int?>()
        private var composition = ""
        private var unavailable = false
        private var predictionUnavailable = false
        private var observations = 0

        private fun trace(message: String) {
            // Bounded metadata only; never record composing text or candidate words.
            if (observations++ < 40) runCatching { observe(message) }
        }

        private fun reset() {
            nativeSize = 0; head = emptyList(); indices = emptyList(); composition = ""
        }

        private fun raw(): String {
            val state = getState.invoke(engine)
            return if (composing.isInstance(state)) rawField.get(state) as String else ""
        }

        private fun <T> predictionOr(fallback: T, block: () -> T): T {
            if (predictionUnavailable) return fallback
            return try { block() } catch (failure: Exception) {
                predictionUnavailable = true
                predictionFailed(failure)
                fallback
            }
        }

        private fun predictionMode() = predictionOr(PredictionMode.KEEP, prediction)

        private fun isPrediction() = predicting.invoke(engine) == true && raw().isEmpty()

        private fun discardPrediction(): Boolean = predictionOr(false) {
            // Never clear an unconsumed suffix, even if a future engine reports prediction incorrectly.
            if (isPrediction()) {
                clear.invoke(engine)
                reset()
                trace("post-commit prediction cleared")
                true
            } else false
        }

        private fun selectNative(method: Method, arguments: Array<out Any?>): Any? {
            val mode = predictionMode()
            val result = method.invoke(engine, *arguments) // A native selection is never retried.
            return if (mode == PredictionMode.DISABLE) {
                predictionOr(result) {
                    val committed = when {
                        result is String -> result.isNotEmpty()
                        resultType.isInstance(result) -> (resultFields[3].get(result) as? String).orEmpty().isNotEmpty()
                        result != null -> acceptedField.get(result) == true &&
                            (commitField.get(result) as? String).orEmpty().isNotEmpty()
                        else -> false
                    }
                    if (committed && discardPrediction() && resultType.isInstance(result)) {
                        // The key callback's snapshot predates clear(); preserve its commit and metadata.
                        resultConstructor.newInstance(*resultFields.mapIndexed { index, field ->
                            if (index == 2) emptyList<Any>() else field.get(result)
                        }.toTypedArray())
                    } else result
                }
            } else result
        }

        // ponytail: serialize one engine's input/page callbacks; use versioned snapshots
        // if asynchronous native pagination later needs to run concurrently.
        @Synchronized
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args.orEmpty()
            try {
                val name = abi.canonical(method)
                if (name in setOf("e", "f", "s", "m", "v") && predictionMode() == PredictionMode.DISABLE)
                    discardPrediction()
                return when (name) {
                    "equals" -> proxy === arguments[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "EmojiEngine(resolved ABI)"
                    "s" -> {
                        @Suppress("UNCHECKED_CAST")
                        val original = method.invoke(engine) as List<Any>
                        reset()
                        // Only optional decoration is recoverable. Never retry a native input/commit.
                        try {
                            if (unavailable || !enabled()) return original
                            val table = dictionary() ?: return original
                            val input = raw()
                            val currentScheme = scheme()
                            val mode = (getMode.invoke(engine) as Enum<*>).name
                            val additions = table.additions(input, original.map { textField.get(it) as String },
                                currentScheme, mode == "T9")
                            trace("mode=$mode scheme=${currentScheme?.name ?: "FULL"} inputLength=${input.length} " +
                                "native=${original.size} added=${additions.size}")
                            if (additions.isEmpty()) return original
                            val merged = ArrayList<Any>()
                            val mapping = ArrayList<Int?>()
                            original.forEachIndexed { index, candidate ->
                                merged.add(candidate); mapping.add(index)
                                additions.filter { it.after == index }.forEach { addition ->
                                    merged.add(candidateConstructor.newInstance(addition.text, null, 0, source, addition.text))
                                    mapping.add(null)
                                }
                            }
                            nativeSize = original.size; head = merged; indices = mapping; composition = input
                            merged
                        } catch (failure: Exception) {
                            reset(); unavailable = true; failed(failure); original
                        }
                    }
                    "m" -> {
                        if (head.isEmpty()) return method.invoke(engine, *arguments)
                        val start = arguments[0] as Int
                        val count = arguments[1] as Int
                        if (start < 0 || count <= 0 || start > Int.MAX_VALUE - count) return emptyList<Any>()
                        val prefix = head.drop(start).take(count)
                        if (prefix.size == count) return prefix
                        val nativeStart = maxOf(start, head.size) - (head.size - nativeSize)
                        @Suppress("UNCHECKED_CAST")
                        val rest = page.invoke(engine, nativeStart, count - prefix.size) as List<Any>
                        prefix + rest.take(count - prefix.size)
                    }
                    "b", "n" -> {
                        val index = arguments[0] as Int
                        if (head.isEmpty() || index < 0) return selectNative(method, arguments)
                        val nativeIndex = if (index < head.size) indices[index] else index - (head.size - nativeSize)
                        if (nativeIndex == null) {
                            // A delayed tap must not clear a newer or partially selected composition;
                            // an unreadable state counts as changed rather than escaping into the host.
                            if (runCatching { raw() }.getOrNull() != composition) {
                                trace("selection ignored: composition changed")
                                return if (arguments.size == 1) "" else pairConstructor.newInstance("", false)
                            }
                            val emoji = textField.get(head[index]) as String
                            clear.invoke(engine)
                            reset()
                            trace("emoji selected: native composition cleared, returning commit")
                            if (arguments.size == 1) emoji else pairConstructor.newInstance(emoji, true)
                        } else {
                            val forwarded = arguments.copyOf().mapIndexed { i, arg -> if (i == 0) nativeIndex else arg }.toTypedArray()
                            reset()
                            selectNative(method, forwarded)
                        }
                    }
                    "a", "e", "f", "j", "v", "singleCharFilterSupported" -> method.invoke(engine, *arguments)
                    else -> {
                        reset()
                        // Space/enter may commit through a key callback instead of candidate selection.
                        if (method.returnType == resultType) selectNative(method, arguments)
                        else method.invoke(engine, *arguments)
                    }
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }
}
