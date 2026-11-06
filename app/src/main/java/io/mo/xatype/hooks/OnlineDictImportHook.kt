package io.mo.xatype.hooks

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.dict.OnlineDictApplier
import io.mo.xatype.dict.OnlineDictStore
import io.mo.xatype.dict.UserWordEngine
import io.mo.xatype.provider.OnlineDictContentProvider
import io.mo.xatype.util.XposedUtils
import java.io.File
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the module's queued online word list to the iFlytek engine inside the input method, once
 * per request. Relies only on unobfuscated `com.iflytek.*` names; if any is missing it logs and stays idle.
 */
object OnlineDictImportHook {
    private const val ENGINE = "com.iflytek.depend.common.base.SmartEngineManager"
    private const val DECODER = "com.iflytek.inputmethod.smart.api.DecoderManager"
    private const val START_DELAY_MS = 8_000L
    private const val IMPORTED_WORD_TYPE = 1
    private const val ENGINE_CALL_TIMEOUT_S = 60L
    private val running = AtomicBoolean(false)

    fun install(module: XposedInterface, loader: ClassLoader) {
        val engine = XposedUtils.findClass(ENGINE, loader)
            ?: return XposedUtils.logWarn(module, "[OnlineDict] SmartEngineManager not found; online word list inactive")
        val hooks = engine.declaredMethods.filter {
            it.name == "initSmart" && it.returnType == java.lang.Boolean.TYPE &&
                it.parameterTypes.firstOrNull() == Context::class.java
        }
        if (hooks.isEmpty()) return XposedUtils.logWarn(module, "[OnlineDict] initSmart not found; online word list inactive")
        hooks.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                // The host initialises the engine on the thread every later engine call must use.
                val engineLooper = Looper.myLooper() ?: Looper.getMainLooper()
                if (result == true) runCatching {
                    schedule(module, chain.getThisObject(), chain.getArg(0) as Context, engineLooper)
                }.onFailure { XposedUtils.logError(module, "[OnlineDict] Cannot schedule import", it) }
                result
            }
        }
        XposedUtils.log(module, "[OnlineDict] Waiting for engine initialisation")
    }

    private fun schedule(module: XposedInterface, manager: Any?, context: Context, engineLooper: Looper) {
        if (manager == null || !running.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        Thread({
            try {
                Thread.sleep(START_DELAY_MS) // Stay out of the way of the first keyboard appearance.
                sync(module, manager, app, engineLooper)
            } catch (t: Throwable) {
                XposedUtils.logError(module, "[OnlineDict] Import failed", t)
            } finally {
                running.set(false)
            }
        }, "xatype-online-dict").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun sync(module: XposedInterface, manager: Any, app: Context, engineLooper: Looper) {
        val state = app.contentResolver.call(OnlineDictContentProvider.CONTENT_URI, "state", null, null) ?: return
        val revision = state.getLong(OnlineDictStore.REVISION)
        val action = state.getString(OnlineDictStore.ACTION).orEmpty()
        if (revision <= 0 || action.isEmpty()) return
        val dir = File(app.filesDir, "xatype-online-dict")
        val applied = File(dir, "applied-revision")
        if (applied.isFile && applied.readText().trim().toLongOrNull() == revision) return

        val decoder = findDecoder(manager)
        if (decoder == null) {
            XposedUtils.logWarn(module, "[OnlineDict] DecoderManager not reachable from SmartEngineManager")
            report(app, revision, "无法访问输入法词库引擎，当前版本可能不兼容", 0)
            return
        }
        val engine = try {
            ReflectiveEngine(decoder, engineLooper)
        } catch (t: Throwable) {
            XposedUtils.logError(module, "[OnlineDict] Host user-word API differs", t)
            report(app, revision, "输入法用户词接口不匹配，当前版本不兼容", 0)
            return
        }
        // A native crash kills the process before any result is written; without this marker the
        // same request would crash the keyboard again on every start.
        val attempt = File(dir, "attempt-revision")
        if (attempt.isFile && attempt.readText().trim().toLongOrNull() == revision) {
            XposedUtils.logWarn(module, "[OnlineDict] Request $revision crashed the input method before; giving up")
            dir.mkdirs()
            applied.writeText(revision.toString())
            attempt.delete()
            report(app, revision, "导入时输入法异常退出，已停止重试，当前版本可能不兼容", 0)
            return
        }
        dir.mkdirs()
        attempt.writeText(revision.toString())
        XposedUtils.log(module, "[OnlineDict] Applying request $revision ($action) on ${engineLooper.thread.name}")
        val result = (if (action == OnlineDictStore.ACTION_IMPORT)
            app.contentResolver.openInputStream(OnlineDictContentProvider.WORDS_URI) else null).use { words ->
            OnlineDictApplier(engine, dir).apply(action, words)
        }
        XposedUtils.log(module, "[OnlineDict] Request $revision finished: ok=${result.ok} imported=${result.imported} " +
            "words ${result.before}->${result.after} slowest=${result.slowestMs}ms: ${result.message}")
        attempt.delete()
        if (!result.retry) {
            dir.mkdirs()
            applied.writeText(revision.toString())
        }
        report(app, revision, result.message, result.imported)
    }

    private fun report(app: Context, revision: Long, message: String, imported: Int) {
        runCatching {
            app.contentResolver.call(OnlineDictContentProvider.CONTENT_URI, "report", null, Bundle().apply {
                putLong(OnlineDictStore.REVISION, revision)
                putString(OnlineDictStore.HOST_STATUS, message)
                putInt(OnlineDictStore.HOST_IMPORTED, imported)
            })
        }
    }

    /** Field names are obfuscated and shift between versions; the class name is not. */
    private fun findDecoder(root: Any): Any? {
        val seen = IdentityHashMap<Any, Boolean>()
        var level = listOf(root)
        repeat(4) {
            val next = ArrayList<Any>()
            for (obj in level) {
                if (seen.put(obj, true) != null) continue
                if (obj.javaClass.name == DECODER) return obj
                var type: Class<*>? = obj.javaClass
                while (type != null && type.name.startsWith("com.iflytek.")) {
                    for (field in type.declaredFields) {
                        if (field.type.isPrimitive || java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                        val value = runCatching { field.isAccessible = true; field.get(obj) }.getOrNull() ?: continue
                        if (value.javaClass.name == DECODER) return value
                        if (value.javaClass.name.startsWith("com.iflytek.")) next.add(value)
                    }
                    type = type.superclass
                }
            }
            level = next
        }
        return null
    }

    private class ReflectiveEngine(private val decoder: Any, private val looper: Looper) : UserWordEngine {
        private val int = Int::class.javaPrimitiveType!!
        private val getWords: Method = method("getUserWords", int)
        private val import: Method = method("importUserWords", String::class.java, int, int)
        private val delete: Method = method("deleteUserWord", CharArray::class.java, Boolean::class.javaPrimitiveType!!, int)
        private val save: Method = method("saveUserWordsToDictionary", Boolean::class.javaPrimitiveType!!)

        private val handler = Handler(looper)

        private fun method(name: String, vararg types: Class<*>): Method =
            decoder.javaClass.getMethod(name, *types)

        /**
         * The native engine is bound to the thread that initialised it: calling it from any other
         * thread races the host's own calls and crashes the process (SIGSEGV in Phn_ResQuery). That
         * was the main thread up to 0.2.1246; 0.2.1358 moved it to the `ime-iflytek-engine`
         * HandlerThread. Keystrokes run between calls, but each call blocks the engine while it runs.
         */
        private fun <T> onEngine(block: () -> T): T {
            if (Looper.myLooper() == looper) return block()
            val done = CountDownLatch(1)
            var result: Result<T>? = null
            check(handler.post {
                result = runCatching(block)
                done.countDown()
            }) { "输入法引擎线程已退出" }
            check(done.await(ENGINE_CALL_TIMEOUT_S, TimeUnit.SECONDS)) { "输入法引擎线程无响应" }
            return result!!.getOrThrow()
        }

        override fun words(): List<String>? = onEngine {
            (getWords.invoke(decoder, -1) as? List<*>)?.map { it.toString() }
        }

        // Mode 1 only appends; mode 2 would delete the user's own words. On host 0.2.1246 the words
        // imported this way are stored as type 1 (contact context), see [delete].
        override fun importFile(path: String): Int = onEngine { import.invoke(decoder, path, 1, 1) as Int }

        // Verified on 0.2.1246: only type 1 removes words added by importFile; types 0 and -1 remove none.
        override fun delete(words: List<String>) {
            onEngine { words.forEach { delete.invoke(decoder, it.toCharArray(), false, IMPORTED_WORD_TYPE) } }
        }

        override fun save() {
            onEngine { save.invoke(decoder, true) }
        }
    }
}
