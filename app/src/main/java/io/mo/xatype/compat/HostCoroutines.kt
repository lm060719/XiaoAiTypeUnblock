package io.mo.xatype.compat

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Calls host suspend functions from hooks, using only the host's own Kotlin runtime.
 *
 * R8 narrows many continuation parameters to ContinuationImpl, which a [Proxy] cannot
 * implement. The stdlib's createCoroutineFromSuspendFunction wrapper is such a
 * ContinuationImpl and runs an arbitrary Function2 block: [start] creates one whose
 * completion is a proxy, so a call that suspends reports back once it resumes. With the
 * caller's context, resumptions are dispatched like the host's own (main thread).
 */
class HostCoroutines(private val loader: ClassLoader) {
    private val symbols = checkNotNull(HostSymbols.strings(HostSymbols.KOTLIN_COROUTINES)) {
        "Host coroutine runtime unavailable"
    }.map { Class.forName(it.removePrefix("L").removeSuffix(";").replace('/', '.'), false, loader) }
    private val constructor = symbols[0].declaredConstructors.single().apply { isAccessible = true }
    /** kotlin.coroutines.Continuation */
    val continuationType: Class<*> = constructor.parameterTypes[0]
    private val contextType = constructor.parameterTypes[1]
    private val blockType = constructor.parameterTypes[2]
    private val getContext = continuationType.methods.single { it.parameterCount == 0 && it.returnType == contextType }
    private val resumeWith = continuationType.methods.single { it.parameterCount == 1 && it.returnType == Void.TYPE }
    /** kotlin.Unit, returned by suspend functions that complete. */
    val unit: Any = symbols[1].let { type ->
        type.declaredFields.single { Modifier.isStatic(it.modifiers) && it.type == type }
    }.apply { isAccessible = true }.get(null)!!
    /** COROUTINE_SUSPENDED */
    val suspended: Any = symbols[2].enumConstants.single { (it as Enum<*>).name == "COROUTINE_SUSPENDED" }

    fun isSuspended(value: Any?): Boolean = value === suspended

    fun context(continuation: Any): Any = getContext.invoke(continuation)!!

    /** The context of a CoroutineScope (single-method interface), such as the service scope. */
    fun scopeContext(scope: Any): Any = generateSequence<Class<*>>(scope.javaClass) { it.superclass }
        .flatMap { it.interfaces.asSequence() }
        .flatMap { it.methods.asSequence() }
        .first { it.parameterCount == 0 && it.returnType == contextType }
        .invoke(scope)!!

    fun resume(continuation: Any, value: Any?) {
        resumeWith.invoke(continuation, value)
    }

    /**
     * Run [call] with a fresh ContinuationImpl. [then] receives its value, or the failure,
     * either before [start] returns or after the call resumes.
     */
    fun start(context: Any, call: (continuation: Any) -> Any?, then: (Result<Any?>) -> Unit) {
        var delivered = false
        val completion = proxy(continuationType, "XatypeContinuation") { method, args ->
            if (method.returnType == contextType) context else {
                if (!delivered) {
                    delivered = true
                    then(decode(args!![0]))
                }
                null
            }
        }
        val block = proxy(blockType, "XatypeSuspendBlock") { _, args -> call(args!![1]!!) }
        val continuation = constructor.newInstance(completion, context, block, null)
        // resumeWith(Unit) enters the block at label 0, as startCoroutine does.
        resumeWith.invoke(continuation, unit)
    }

    /** Chain suspend calls; [done] runs once with the last value or the first failure. */
    fun sequence(context: Any, steps: List<(continuation: Any, previous: Any?) -> Any?>, done: (Result<Any?>) -> Unit) {
        fun next(index: Int, previous: Any?) {
            if (index == steps.size) return done(Result.success(previous))
            start(context, { steps[index](it, previous) }) { result ->
                result.fold({ next(index + 1, it) }, { done(Result.failure(it)) })
            }
        }
        next(0, null)
    }

    /**
     * Inside a hooked suspend function: run [body], which must call `finish` exactly once.
     * Returns Unit when it finished synchronously, otherwise COROUTINE_SUSPENDED and resumes
     * [caller] with Unit later. Failures go to [failed]; the caller always resumes normally.
     */
    fun suspendHook(caller: Any, failed: (Throwable) -> Unit, body: (finish: (Result<Any?>) -> Unit) -> Unit): Any {
        var synchronous = true
        var completed = false
        val finish: (Result<Any?>) -> Unit = { result ->
            result.exceptionOrNull()?.let(failed)
            if (synchronous) completed = true else resume(caller, unit)
        }
        try {
            body(finish)
        } catch (failure: Throwable) {
            if (!completed) finish(Result.failure(failure))
        }
        synchronous = false
        return if (completed) unit else suspended
    }

    private fun proxy(type: Class<*>, name: String, handle: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(type), InvocationHandler { proxy, method, args ->
            when {
                method.name == "equals" && method.parameterCount == 1 -> proxy === args!![0]
                method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                method.name == "toString" && method.parameterCount == 0 -> name
                else -> handle(method, args)
            }
        })

    /** kotlin.Result is inline: a value, or a Failure(exception) box. */
    private fun decode(value: Any?): Result<Any?> {
        val failure = value?.let { candidate ->
            candidate.javaClass.declaredFields.singleOrNull { !Modifier.isStatic(it.modifiers) }
                ?.takeIf { it.type == Throwable::class.java && candidate.toString().startsWith("Failure(") }
                ?.apply { isAccessible = true }?.get(candidate) as? Throwable
        }
        return if (failure != null) Result.failure(failure) else Result.success(value)
    }
}
