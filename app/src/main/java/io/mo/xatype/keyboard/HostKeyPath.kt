package io.mo.xatype.keyboard

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostCoroutines
import io.mo.xatype.compat.KeyboardAbi
import io.mo.xatype.compat.abiField
import io.mo.xatype.compat.abiMethod
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** One step of a key handled by the module instead of the native handler. */
sealed interface KeyOp {
    /**
     * An engine operation: called directly through 0.2.1246, sent as a session command after.
     * A [literal] is committed when the engine leaves the key unconsumed, as the host does.
     */
    class Engine(val direct: () -> Any, val command: () -> Any, val literal: String? = null) : KeyOp
    class Commit(val text: String) : KeyOp
    class Action(val block: () -> Unit) : KeyOp
}

/**
 * The native key handler and its callbacks. Through 0.2.1246 both are synchronous; from
 * 0.2.1358 the handler, commits and refreshes are suspend functions and engines run behind
 * a session, so module-handled keys run as host coroutines and resume the native caller.
 */
class HostKeyPath(loader: ClassLoader, val abi: KeyboardAbi, val engine: HostEngine = HostEngine(abi)) {
    val callbacksClass: Class<*> = abi.type("ab.b1")
    val keyClass: Class<*> = abi.type("ka.c")
    private val current = abi.namedMethods(callbacksClass, "u").single()
    private val uppercase = callbacksClass.abiMethod(abi, "o")
    private val commit = abi.namedMethods(callbacksClass, "K").single()
    private val refresh = abi.namedMethods(callbacksClass, "y").single()
    /** Releases a one-shot shift (caps lock stays); null on profiles without it. */
    private val shiftRelease = abi.namedMethods(callbacksClass, "h").singleOrNull()
        ?.takeIf { it.parameterCount == 0 && it.returnType == Void.TYPE }
    /** Commits take a continuation from 0.2.1358. */
    val suspending = commit.parameterCount == 2
    val coroutines: HostCoroutines? = if (suspending) HostCoroutines(loader) else null
    val handler: Method by lazy {
        abi.namedMethods(abi.type("ab.f1"), "c").single {
            it.parameterCount >= 2 && it.parameterTypes[0] == keyClass && it.parameterTypes[1] == callbacksClass
        }.also { handler ->
            require(if (suspending) handler.parameterCount == 3 &&
                coroutines!!.continuationType.isAssignableFrom(handler.parameterTypes[2])
            else handler.returnType == Void.TYPE)
        }
    }

    init {
        require(uppercase.returnType == Boolean::class.javaPrimitiveType)
        if (suspending) require(engine.sessions && refresh.parameterCount == 2)
        else require(commit.returnType == Void.TYPE && refresh.returnType == Void.TYPE &&
            current.returnType == engine.baseInterface)
    }

    /** The engine the callbacks currently dispatch to. */
    fun engine(callbacks: Any): Any? = engine.engineOf(current.invoke(callbacks))

    fun uppercase(callbacks: Any): Boolean = uppercase.invoke(callbacks) == true

    /** As the native key path does after committing an uppercase letter. */
    fun releaseShift(callbacks: Any) {
        shiftRelease?.invoke(callbacks)
    }

    /** True for a hooked call that resumes the native handler's own state machine. */
    fun resuming(chain: XposedInterface.Chain): Boolean = chain.args[0] == null

    /** The hook result for a key consumed without any operation. */
    fun consumed(): Any? = coroutines?.unit

    /**
     * Perform [ops] in order and, with [thenNative], continue with the original handler.
     * Returns the hooked handler's result.
     */
    fun perform(module: XposedInterface, chain: XposedInterface.Chain, ops: List<KeyOp>,
                thenNative: Boolean = false, failed: (Throwable) -> Unit): Any? {
        val callbacks = chain.args[1]!!
        val co = coroutines
        if (co == null) {
            for (op in ops) when (op) {
                is KeyOp.Engine -> deliverNow(callbacks, op.direct())
                is KeyOp.Commit -> commit.invoke(callbacks, op.text)
                is KeyOp.Action -> op.block()
            }
            return if (thenNative) chain.proceed() else null
        }
        val caller = chain.args[2]!!
        val context = co.context(caller)
        val self = chain.thisObject
        val args = chain.args.toTypedArray()
        var synchronous = true
        var finished = false
        var syncResult: Any? = null
        fun complete(result: Result<Any?>) {
            result.exceptionOrNull()?.let(failed)
            val value = if (!thenNative) co.unit else try {
                module.getInvoker(handler).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(self, *args)
            } catch (failure: Throwable) {
                failed((failure as? InvocationTargetException)?.targetException ?: failure)
                co.unit
            }
            if (synchronous) {
                finished = true
                syncResult = value
            } else if (!co.isSuspended(value)) co.resume(caller, co.unit)
        }
        fun step(index: Int) {
            if (index == ops.size) return complete(Result.success(null))
            val next: (Result<Any?>) -> Unit = { if (it.isFailure) complete(it) else step(index + 1) }
            when (val op = ops[index]) {
                is KeyOp.Engine -> dispatch(context, callbacks, op.command(), op.literal, next)
                is KeyOp.Commit -> co.start(context, { commit.invoke(callbacks, op.text, it) }, next)
                is KeyOp.Action -> next(runCatching { op.block() })
            }
        }
        try {
            step(0)
        } catch (failure: Throwable) {
            if (!finished) complete(Result.failure(failure))
        }
        synchronous = false
        return if (finished) syncResult else co.suspended
    }

    /** Through 0.2.1246: commit and refresh an engine result synchronously. */
    fun deliverNow(callbacks: Any, result: Any) {
        engine.commitOf(result)?.takeIf { it.isNotEmpty() }?.let { commit.invoke(callbacks, it) }
        refresh.invoke(callbacks, result)
    }

    /** Run a session command, then commit and refresh as the native key path does. */
    private fun dispatch(context: Any, callbacks: Any, command: Any, literal: String?, then: (Result<Any?>) -> Unit) {
        val co = coroutines!!
        val handle = current.invoke(callbacks) ?: return then(Result.failure(IllegalStateException("No engine session")))
        co.start(context, { engine.send(handle, command, it) }) { sent ->
            sent.fold({ result ->
                val text = (if (engine.consumedOf(result!!)) engine.commitOf(result) else literal)?.takeIf { it.isNotEmpty() }
                val steps = listOfNotNull<(Any, Any?) -> Any?>(
                    text?.let { value -> { continuation, _ -> commit.invoke(callbacks, value, continuation) } },
                    { continuation, _ -> refresh.invoke(callbacks, result, continuation) })
                co.sequence(context, steps, then)
            }, { then(Result.failure(it)) })
        }
    }
}

/** The engine factory: an instance method through 0.2.1246, static (manager, type, purpose) after. */
class HostEngineFactory(abi: KeyboardAbi, host: HostEngine) {
    val manager: Class<*> = abi.type("ab.x0")
    val method: Method = abi.namedMethods(manager, "b").single { host.engineType in it.parameterTypes }
    private val static = java.lang.reflect.Modifier.isStatic(method.modifiers)

    init {
        require(method.returnType == host.baseInterface)
    }

    fun manager(chain: XposedInterface.Chain): Any = if (static) chain.args[0]!! else chain.thisObject!!
    fun engineType(chain: XposedInterface.Chain): Any? = chain.args[if (static) 1 else 0]
}

/**
 * Keep pinyin time/date expansions away from module engines. Through 0.2.1246 they have
 * their own method taking the engine; 0.2.1358 inlined them into candidate display, which
 * takes the engine handle, so the published expansion is cleared afterwards instead.
 */
fun installMacroGuard(module: XposedInterface, abi: KeyboardAbi, host: HostEngine, ours: (Any?) -> Boolean) {
    val owner = abi.type("ab.s0")
    val macros = abi.namedMethods(owner, "l").single()
    if (macros.parameterCount == 1) {
        require(macros.returnType == Void.TYPE && macros.parameterTypes[0] == host.baseInterface)
        module.hook(macros).intercept { chain -> if (ours(chain.args[0])) null else chain.proceed() }
    } else {
        val expansion = owner.abiField(abi, "macro").apply { isAccessible = true }
        val publish = abi.namedMethods(abi.type("flow.State"), "setValue").single()
        module.hook(macros).intercept { chain ->
            val result = chain.proceed()
            if (ours(host.engineOf(chain.args[0]))) publish.invoke(expansion.get(chain.thisObject), null)
            result
        }
    }
    module.deoptimize(macros)
}
