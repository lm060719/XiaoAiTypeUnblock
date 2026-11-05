package io.mo.xatype.keyboard

import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Host engine data classes and, from 0.2.1358, the engine session, across host builds.
 *
 * 0.2.1358 dropped InputResult.acceptedRawInput, reordered the Composing constructor and
 * moved engines behind a session handle: the callbacks return the handle, and engines run
 * the session's commands (Key, Backspace, ReplaceRaw, ...) on a dedicated thread.
 */
class HostEngine(val abi: KeyboardAbi) {
    private fun type(id: String) = abi.type(id)
    private val int = Int::class.javaPrimitiveType!!
    private val bool = Boolean::class.javaPrimitiveType!!
    private val string = String::class.java
    private val list = List::class.java

    val engineType = type("z8.g")
    val baseInterface = type("z8.q")
    val extendedInterface = type("z8.p")
    val resultType = type("z8.r")
    val stateType = type("z8.u")
    val composingType = type("z8.s")
    val pairType = type("nc.f")
    private val sourceType = type("z8.b")
    val pinyin: Any = engineType.enumConstants.single { (it as Enum<*>).name == "PINYIN" }
    private val systemSource: Any = sourceType.enumConstants.single { (it as Enum<*>).name == "SYSTEM" }
    val idle: Any = type("z8.t").let { idleClass ->
        idleClass.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == idleClass }
            .apply { isAccessible = true }.get(null)!!
    }
    private val candidateConstructor = type("z8.a").getConstructor(string, string, int, sourceType, string)
    private val resultConstructor: Constructor<*> = constructor(resultType,
        arrayOf(bool, string, list, string, int, list, string), arrayOf(bool, string, list, string, int, list))
    /** InputResult still carries acceptedRawInput (through 0.2.1246). */
    val acceptsRaw = resultConstructor.parameterCount == 7
    private val legacyComposing = runCatching { composingType.getConstructor(int, string, string, list) }.getOrNull()
    private val composingConstructor = legacyComposing ?: composingType.getConstructor(string, string, int, list)
    private val pairConstructor = pairType.getConstructor(Any::class.java, Any::class.java)

    private val resultProbe = arrayOf<Any?>(true, "composing-probe", listOf("candidates-probe"), "commit-probe", 17,
        listOf("errors-probe"), "accepted-probe").copyOf(resultConstructor.parameterCount)
    /** InputResult fields in constructor order. */
    private val resultFields = resultConstructor.newInstance(*resultProbe).let { probe ->
        resultProbe.map { value -> fieldWithValue(probe, value!!) }
    }
    private val rawField = fieldWithValue(composing("composing-raw-probe", "composing-display-probe"), "composing-raw-probe")

    fun candidate(text: String, annotation: String?, frequency: Int, display: String?): Any =
        candidateConstructor.newInstance(text, annotation, frequency, systemSource, display)

    fun result(consumed: Boolean, composing: String, candidates: List<Any>, commit: String?, accepted: String?): Any =
        resultConstructor.newInstance(*arrayOf<Any?>(consumed, composing, candidates, commit, 0, emptyList<Any>(), accepted)
            .copyOf(resultConstructor.parameterCount))

    /** A copy of a host result with [candidates] replaced; every other field is retained. */
    fun withCandidates(result: Any, candidates: List<Any>): Any = resultConstructor.newInstance(
        *resultFields.mapIndexed { index, field -> if (index == 2) candidates else field.get(result) }.toTypedArray())

    fun commitOf(result: Any): String? = resultFields[3].get(result) as String?

    fun consumedOf(result: Any): Boolean = resultFields[0].get(result) == true

    fun composing(raw: String, display: String, cursor: Int = 8): Any =
        if (legacyComposing != null) legacyComposing.newInstance(cursor, raw, display, emptyList<Any>())
        else composingConstructor.newInstance(raw, display, cursor, emptyList<Any>())

    /** Composing.rawInput, or "" for the idle state. */
    fun rawOf(state: Any?): String = if (composingType.isInstance(state)) rawField.get(state) as String else ""

    fun pair(first: Any?, second: Any?): Any = pairConstructor.newInstance(first, second)

    // 0.2.1358 engine session.
    val sessions: Boolean = abi.hasType("engine.Handle")
    private val handleType = if (sessions) type("engine.Handle") else null
    private val handleSession = handleType?.abiField(abi, "session")?.apply { isAccessible = true }
    private val sessionEngine = if (sessions) type("engine.Session").abiField(abi, "engine").apply { isAccessible = true } else null
    private val send = handleType?.let { abi.namedMethods(it, "send").single() }
    private val keyConstructor = if (sessions) type("engine.Key").getConstructor(int) else null
    private val replaceRawConstructor = if (sessions) type("engine.ReplaceRaw").getConstructor(string) else null
    /** The Backspace command object. */
    val backspace: Any? = if (sessions) type("engine.Backspace").let { command ->
        command.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == command }
            .apply { isAccessible = true }.get(null)
    } else null

    /** The engine behind what the key callbacks report as current: a handle from 0.2.1358. */
    fun engineOf(current: Any?): Any? =
        if (current != null && handleType?.isInstance(current) == true) sessionEngine!!.get(handleSession!!.get(current))
        else current

    /** Suspend: run [command] through the handle's session; resumes with the InputResult. */
    fun send(handle: Any, command: Any, continuation: Any): Any? = send!!.invoke(handle, command, continuation)

    fun key(code: Int): Any = keyConstructor!!.newInstance(code)
    fun replaceRaw(text: String): Any = replaceRawConstructor!!.newInstance(text)

    private fun constructor(type: Class<*>, vararg shapes: Array<Class<*>>): Constructor<*> =
        shapes.firstNotNullOfOrNull { runCatching { type.getConstructor(*it) }.getOrNull() }
            ?: error("Unsupported constructor: ${type.name}")

    private fun fieldWithValue(instance: Any, value: Any) = instance.javaClass.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        .single { it.get(instance) == value }

    companion object {
        /**
         * ReplaceRaw text an own engine reads as "commit everything", so a flush runs through
         * the session like any other command and its snapshot stays current.
         */
        const val FLUSH = "\u0000xatype-flush"
    }
}
