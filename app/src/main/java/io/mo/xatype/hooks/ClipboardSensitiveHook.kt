package io.mo.xatype.hooks

import android.os.BaseBundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

object ClipboardSensitiveHook {
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun install(module: XposedInterface, @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        // ClipDescription extras are a PersistableBundle, which inherits getBoolean
        // from BaseBundle; hooking the base declaration covers both bundle types.
        val method = XposedUtils.findMethodExact(
            BaseBundle::class.java,
            "getBoolean",
            String::class.java,
            java.lang.Boolean.TYPE
        )
        if (method == null) {
            XposedUtils.logWarn(module, "[Clipboard] BaseBundle.getBoolean(String, boolean) not found; sensitive flag stays")
            return
        }
        try {
            module.hook(method).intercept { chain ->
                // Every Bundle read in the process lands here: compare the key before anything else.
                if (chain.getArg(0) != EXTRA_IS_SENSITIVE || !ConfigManager.isClipboardSensitiveEnabled()) {
                    return@intercept chain.proceed()
                }
                XposedUtils.logDebug(module) { "[Clipboard] Bypassed $EXTRA_IS_SENSITIVE" }
                false
            }
            XposedUtils.log(module, "[Clipboard] Hooked BaseBundle.getBoolean(String, boolean)")
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Failed to hook BaseBundle.getBoolean", t)
        }
    }
}
