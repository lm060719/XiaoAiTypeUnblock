package io.mo.xatype.compat

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.provider.LogContentProvider
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends each [SymbolTable]'s outcome to the module app. Host symbols resolve
 * before the host Application exists, so reports wait for its creation.
 */
object AdaptationReporter {
    private val pending = ConcurrentHashMap<String, Bundle>()
    private val hookInstalled = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun install(module: XposedInterface) {
        if (!hookInstalled.compareAndSet(false, true)) return
        val method = Instrumentation::class.java.getDeclaredMethod(
            "callApplicationOnCreate",
            Application::class.java
        )
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            (chain.getArg(0) as? Context)?.let {
                appContext = it.applicationContext ?: it
                flush(module)
            }
            result
        }
    }

    fun report(
        module: XposedInterface,
        packageName: String,
        table: SymbolTable,
        context: Context? = null
    ) {
        if (context != null && appContext == null) appContext = context.applicationContext ?: context
        pending[packageName] = Bundle().apply {
            putString(AdaptationStatus.EXTRA_PACKAGE, packageName)
            putString(AdaptationStatus.EXTRA_SOURCE, table.source.name)
            putStringArrayList(AdaptationStatus.EXTRA_MISSING, ArrayList(table.missing.keys))
            putString(
                AdaptationStatus.EXTRA_MODULE_STAMP,
                XposedUtils.moduleStamp(module)
            )
        }
        flush(module)
    }

    private fun flush(module: XposedInterface) {
        val context = appContext ?: return
        if (pending.isEmpty()) return
        // Starting the module's provider is IPC; keep it off the host's startup path.
        Thread {
            pending.keys.toList().forEach { packageName ->
                val bundle = pending.remove(packageName) ?: return@forEach
                try {
                    bundle.putLong(AdaptationStatus.EXTRA_VERSION_CODE, versionCode(context, packageName))
                    context.contentResolver.call(
                        LogContentProvider.CONTENT_URI,
                        AdaptationStatus.METHOD_REPORT,
                        null,
                        bundle
                    )
                } catch (t: Throwable) {
                    XposedUtils.logWarn(module, "Adaptation report for $packageName failed: $t")
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(context: Context, packageName: String): Long = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(-1L)
}
