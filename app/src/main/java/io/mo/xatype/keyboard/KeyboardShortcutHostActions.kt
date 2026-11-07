package io.mo.xatype.keyboard

import io.mo.xatype.compat.HostCoroutines
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiMethod

/** Finish pending input through native candidate callbacks before moving the editor selection. */
class KeyboardShortcutHostActions(loader: ClassLoader, private val abi: KeyboardAbi = KeyboardAbi.require(loader, HostSymbols.KEYBOARD_ACTIONS)) {
    private val callbacks = abi.type("ab.b1")
    private val pending = callbacks.abiMethod(abi, "n")
    private val candidates = callbacks.abiMethod(abi, "d")
    private val text = callbacks.abiMethod(abi, "f")
    private val selected = callbacks.abiMethod(abi, "M")
    private val commit = abi.namedMethods(callbacks, "K").single()
    private val current = abi.namedMethods(callbacks, "u").single()
    private val refresh = callbacks.abiMethod(abi, "F")
    private val finish = callbacks.abiMethod(abi, "J")
    /** Through 0.2.1246: choose(index) and the engine's clear() run synchronously. */
    private val choose = abi.namedMethods(callbacks, "b").singleOrNull()
    private val clear = if (choose != null) current.returnType.abiMethod(abi, "clear") else null
    /** From 0.2.1358: the native candidate selection and the session's Clear command suspend. */
    private val select = if (abi.hasType("candidate.Select")) abi.namedMethods(abi.type("candidate.Select"), "select").single() else null
    private val clearSession = if (abi.hasType("engine.Handle")) abi.namedMethods(abi.type("engine.Handle"), "clear").single() else null
    val suspending = choose == null

    init {
        require(if (suspending) select != null && clearSession != null && commit.parameterCount == 2
            else commit.parameterCount == 1)
    }

    /** Input awaits a candidate choice. */
    fun hasPending(target: Any) = pending.invoke(target) == true

    /** Commit [text] through the native pipeline (main editor or the host's own input boxes). */
    fun commitText(target: Any, text: String) {
        check(!suspending)
        commit.invoke(target, text)
    }

    /** Suspend: [commitText] from 0.2.1358. */
    fun commitText(target: Any, text: String, continuation: Any): Any? {
        check(suspending)
        return commit.invoke(target, text, continuation)
    }

    fun finishInput(target: Any) {
        check(!suspending)
        var previous: String? = null
        var selections = 0
        while (pending.invoke(target) == true) {
            val raw = text.invoke(target) as String
            // A decoder may have no candidate, or fail to progress. Preserve its remaining
            // input literally instead of discarding it or looping on an invalid candidate.
            if (candidates.invoke(target) != true || raw == previous || selections >= 64) {
                if (raw.isNotEmpty()) commit.invoke(target, raw)
                current.invoke(target)?.let { clear!!.invoke(it) }
                refresh.invoke(target)
                break
            }
            previous = raw
            choose!!.invoke(target, selected.invoke(target))
            selections++
        }
        finish.invoke(target)
    }

    /** [finishInput] as host coroutines on the main input's callbacks; [done] runs once. */
    fun finishInput(coroutines: HostCoroutines, context: Any, service: Any, target: Any, done: (Result<Any?>) -> Unit) {
        check(suspending)
        var previous: String? = null
        var selections = 0
        fun loop() {
            if (pending.invoke(target) != true) {
                finish.invoke(target)
                return done(Result.success(null))
            }
            val raw = text.invoke(target) as String
            if (candidates.invoke(target) != true || raw == previous || selections >= 64) {
                val steps = ArrayList<(Any, Any?) -> Any?>()
                if (raw.isNotEmpty()) steps += { continuation, _ -> commit.invoke(target, raw, continuation) }
                current.invoke(target)?.let { handle -> steps += { continuation, _ -> clearSession!!.invoke(handle, continuation) } }
                coroutines.sequence(context, steps) { result ->
                    done(result.mapCatching { refresh.invoke(target); finish.invoke(target) })
                }
                return
            }
            previous = raw
            val index = selected.invoke(target) as Int
            coroutines.start(context, { select!!.invoke(null, service, index, false, it) }) { result ->
                if (result.isFailure) done(result) else {
                    selections++
                    loop()
                }
            }
        }
        loop()
    }
}
