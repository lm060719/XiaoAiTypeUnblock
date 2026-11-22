package io.mo.xatype.util

import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Resources that outlive a hook invocation and need explicit teardown on module update. */
internal object HookLifetime {
    @Volatile var retired = false
        private set
    private val mainCleanup = LinkedHashSet<() -> Unit>()
    private val workerCleanup = LinkedHashSet<() -> Unit>()
    private val busy = LinkedHashMap<String, () -> Boolean>()
    private val hostEngines = ArrayList<WeakReference<Any>>()

    @Synchronized
    fun onMainCleanup(cleanup: () -> Unit) {
        check(!retired)
        mainCleanup.add(cleanup)
    }

    @Synchronized
    fun onWorkerCleanup(cleanup: () -> Unit) {
        check(!retired)
        workerCleanup.add(cleanup)
    }

    @Synchronized
    fun waitForIdle(description: String, condition: () -> Boolean) {
        busy[description] = condition
    }

    @Synchronized
    fun begin(condition: () -> Boolean): Boolean = !retired && condition()

    @Synchronized
    fun trackEngine(engine: Any) {
        check(!retired)
        hostEngines.removeAll { it.get() == null }
        hostEngines.add(WeakReference(engine))
    }

    @Synchronized
    fun releaseEngine(engine: Any) {
        hostEngines.removeAll { it.get().let { value -> value == null || value === engine } }
    }

    fun worker(name: String): ((() -> Unit) -> Unit) {
        val executor by lazy(LazyThreadSafetyMode.NONE) {
            Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }.also { worker ->
                onWorkerCleanup {
                    worker.shutdown()
                    check(worker.awaitTermination(4, TimeUnit.SECONDS)) { "$name is still running" }
                }
            }
        }
        return { job ->
            synchronized(this) {
                if (!retired) executor.execute { if (!retired) job() }
            }
        }
    }

    fun retire(module: XposedInterface): Boolean {
        val cleanup = onMain {
            val actions = synchronized(this) {
                val busyResource = busy.entries.firstOrNull { it.value() }?.key
                    ?: "custom keyboard engine".takeIf { hostEngines.any { reference -> reference.get() != null } }
                if (busyResource != null) {
                    XposedUtils.logWarn(module, "Hot reload deferred: $busyResource is still active")
                    return@onMain null
                }
                retired = true
                mainCleanup.toList().asReversed() to workerCleanup.toList().asReversed()
            }
            actions.first.forEach { it() }
            actions.second
        } ?: return false
        cleanup.forEach { it() }
        return true
    }

    private fun <T> onMain(action: () -> T): T? {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) return action()
        val claimed = AtomicBoolean(false)
        val task = FutureTask(Callable { if (claimed.compareAndSet(false, true)) action() else null })
        if (!Handler(main).post(task)) return null
        return try {
            task.get(4, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            if (claimed.compareAndSet(false, true)) null else task.get()
        }
    }
}
