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

/** Personal phrases, emoji decoration and prediction control share one native candidate mapping. */
class EmojiHostBridge(
    private val loader: ClassLoader,
    private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ENGINE)
) {
    private val checked = ShapeHostBridge(loader, abi)
    val engineType = checked.engineType
    val baseInterface = checked.baseInterface
    private val host = checked.host
    private val extended = host.extendedInterface
    private val resultType = host.resultType
    private val getState = baseInterface.abiMethod(abi, "f")
    private val getMode = baseInterface.abiMethod(abi, "a")
    private val predicting = baseInterface.abiMethod(abi, "e")
    private val clear = baseInterface.abiMethod(abi, "clear")
    private val candidates = baseInterface.abiMethod(abi, "s")
    private val page = extended.abiMethod(abi, "m", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val textField = fieldWithValue(host.candidate(
        "emoji-probe-text", "emoji-probe-note", 1, "emoji-probe-display"), "emoji-probe-text")
    private val pairProbe = host.pair("selection-probe", true)
    private val commitField = fieldWithValue(pairProbe, "selection-probe")
    private val acceptedField = fieldWithValue(pairProbe, true)

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
               personal: () -> PersonalDictionary = { PersonalDictionary.EMPTY },
               personalFailed: (Throwable) -> Unit = {},
               failed: (Throwable) -> Unit): Any = Proxy.newProxyInstance(loader, arrayOf(extended),
        Session(engine, dictionary, enabled, scheme, prediction, predictionFailed, observe, personal, personalFailed, failed))

    private inner class Session(
        val engine: Any, val dictionary: () -> EmojiCandidates?, val enabled: () -> Boolean,
        val scheme: () -> ShuangpinScheme?, val prediction: () -> PredictionMode,
        val predictionFailed: (Throwable) -> Unit, val observe: (String) -> Unit,
        val personal: () -> PersonalDictionary, val personalFailed: (Throwable) -> Unit, val failed: (Throwable) -> Unit
    ) : InvocationHandler {
        private var nativeSize = 0
        private var head = emptyList<Any>()
        private var indices = emptyList<Int?>()
        private var composition = ""
        private var shortcutFirst: String? = null
        private var personalUnavailable = false
        private var unavailable = false
        private var predictionUnavailable = false
        private var observations = 0

        private fun trace(message: String) {
            // Bounded metadata only; never record composing text or candidate words.
            if (observations++ < 40) runCatching { observe(message) }
        }

        private fun reset() {
            nativeSize = 0; head = emptyList(); indices = emptyList(); composition = ""; shortcutFirst = null
        }

        private fun raw(): String = host.rawOf(getState.invoke(engine))

        private fun personalWords(input: String): List<String> {
            if (personalUnavailable) return emptyList()
            return try {
                val mode = (getMode.invoke(engine) as Enum<*>).name
                if (mode != "PINYIN" || scheme() != null) emptyList()
                else {
                    val table = personal()
                    val matched = table.lookup(input)
                    if (table.entries.isNotEmpty() && input.isNotEmpty()) trace(
                        "personal lookup: mode=$mode inputLength=${input.length} " +
                            "boundaries=${input.count { it == '\'' || it == ' ' }} matches=${matched.size}")
                    matched
                }
            } catch (failure: Exception) {
                personalUnavailable = true
                personalFailed(failure)
                emptyList()
            }
        }

        private fun decorate(original: List<Any>): List<Any> {
            reset()
            val input = raw()
            val phrases = personalWords(input)
            val additions = if (unavailable) emptyList() else try {
                if (!enabled()) emptyList() else {
                    val mode = (getMode.invoke(engine) as Enum<*>).name
                    dictionary()?.additions(input, original.map { textField.get(it) as String },
                        scheme(), mode == "T9").orEmpty()
                }
            } catch (failure: Exception) {
                unavailable = true; failed(failure); emptyList()
            }
            if (phrases.isEmpty() && additions.isEmpty()) return original
            val merged = ArrayList<Any>()
            val mapping = ArrayList<Int?>()
            phrases.forEach { phrase ->
                merged.add(host.candidate(phrase, null, 0, phrase)); mapping.add(null)
            }
            val duplicates = phrases.toHashSet()
            original.forEachIndexed { index, candidate ->
                if (textField.get(candidate) !in duplicates) {
                    merged.add(candidate); mapping.add(index)
                }
                additions.filter { it.after == index && it.text !in duplicates }.forEach { addition ->
                    merged.add(host.candidate(addition.text, null, 0, addition.text)); mapping.add(null)
                }
            }
            nativeSize = original.size; head = merged; indices = mapping; composition = input
            shortcutFirst = phrases.firstOrNull()
            trace("candidate enhancements: native=${original.size} personal=${phrases.size} emoji=${additions.size}")
            return merged
        }

        private fun decorateResult(result: Any?): Any? {
            if (result == null || !resultType.isInstance(result)) return result
            // Never retry the native operation if optional candidate decoration fails.
            return try { host.withCandidates(result, decorate(host.candidatesOf(result))) }
            catch (failure: Exception) {
                reset(); personalUnavailable = true; unavailable = true
                personalFailed(failure); failed(failure); result
            }
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
                        resultType.isInstance(result) -> host.commitOf(result).orEmpty().isNotEmpty()
                        result != null -> acceptedField.get(result) == true &&
                            (commitField.get(result) as? String).orEmpty().isNotEmpty()
                        else -> false
                    }
                    if (committed && discardPrediction() && resultType.isInstance(result)) {
                        // The key callback's snapshot predates clear(); preserve its commit and metadata.
                        host.withCandidates(result, emptyList())
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
                val commitsFirst = (name == "h" && arguments.firstOrNull() == ' ') ||
                    (name == "i" && arguments.firstOrNull() == ' '.code) ||
                    (name == "o" && arguments.firstOrNull() == " ") ||
                    (name == "d" && arguments.firstOrNull() == HostEngine.FLUSH)
                if (commitsFirst) {
                    val input = raw()
                    val phrase = if (head.isNotEmpty() && composition == input) shortcutFirst
                        else personalWords(input).firstOrNull()
                    if (phrase != null) {
                        clear.invoke(engine)
                        reset()
                        return host.result(true, "", emptyList(), phrase, "")
                    }
                }
                return when (name) {
                    "equals" -> proxy === arguments[0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "EmojiEngine(resolved ABI)"
                    "s" -> {
                        @Suppress("UNCHECKED_CAST")
                        val original = method.invoke(engine) as List<Any>
                        // Only optional decoration is recoverable. Never retry a native input/commit.
                        try {
                            decorate(original)
                        } catch (failure: Exception) {
                            reset(); unavailable = true; personalUnavailable = true
                            personalFailed(failure); failed(failure); original
                        }
                    }
                    "m" -> {
                        // Some host views request a page before the candidate-strip snapshot.
                        if (head.isEmpty() || runCatching { raw() }.getOrNull() != composition) {
                            @Suppress("UNCHECKED_CAST")
                            val original = candidates.invoke(engine) as List<Any>
                            try { decorate(original) } catch (failure: Exception) {
                                reset(); personalUnavailable = true; unavailable = true
                                personalFailed(failure); failed(failure)
                            }
                        }
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
                        // Shifted native indices are stale too when the decoder changes externally.
                        if (runCatching { raw() }.getOrNull() != composition) {
                            trace("selection ignored: composition changed")
                            return if (arguments.size == 1) "" else host.pair("", false)
                        }
                        val nativeIndex = if (index < head.size) indices[index] else index - (head.size - nativeSize)
                        if (nativeIndex == null) {
                            val text = textField.get(head[index]) as String
                            clear.invoke(engine)
                            reset()
                            trace("custom candidate selected: native composition cleared")
                            if (arguments.size == 1) text else host.pair(text, true)
                        } else {
                            val forwarded = arguments.copyOf().mapIndexed { i, arg -> if (i == 0) nativeIndex else arg }.toTypedArray()
                            reset()
                            selectNative(method, forwarded)
                        }
                    }
                    // The host reads the cursor while building the candidate snapshot, before a tap.
                    "a", "e", "f", "j", "v", "singleCharFilterSupported", "cursorEditing", "engineName",
                    "segmentState", "rawCursor" -> method.invoke(engine, *arguments)
                    else -> {
                        reset()
                        // Space/enter may commit through a key callback instead of candidate selection.
                        if (method.returnType == resultType) decorateResult(selectNative(method, arguments))
                        else method.invoke(engine, *arguments)
                    }
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }
}
