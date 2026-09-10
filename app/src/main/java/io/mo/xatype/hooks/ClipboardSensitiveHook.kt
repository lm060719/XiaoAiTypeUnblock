package io.mo.xatype.hooks

import android.os.BaseBundle
import android.os.PersistableBundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.data.LogType
import io.mo.xatype.util.LogBridge
import io.mo.xatype.util.XposedUtils

object ClipboardSensitiveHook {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        // Hook PersistableBundle.getBoolean(String, boolean)
        val methodGetBoolean = XposedUtils.findMethodExact(
            PersistableBundle::class.java,
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (methodGetBoolean != null) {
            try {
                module.hook(methodGetBoolean).intercept { chain ->
                    if (!ConfigManager.isClipboardSensitiveEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    if ("android.content.extra.IS_SENSITIVE" == key) {
                        if (ConfigManager.isVerboseLogEnabled()) {
                            XposedUtils.log(module, "[Clipboard] Bypassed android.content.extra.IS_SENSITIVE check in PersistableBundle")
                        }
                        LogBridge.record(
                            LogType.CLIPBOARD,
                            "绕过剪贴板敏感安全标记",
                            "检测到系统 IS_SENSITIVE 标记，强制返回 false 允许快捷粘贴与联想"
                        )
                        false // Never mark as sensitive
                    } else {
                        chain.proceed()
                    }
                }
                XposedUtils.log(module, "[Clipboard] Hooked PersistableBundle.getBoolean(String, boolean)")
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Failed to hook PersistableBundle.getBoolean", t)
            }
        }

        // Also hook BaseBundle.getBoolean(String, boolean)
        val methodBaseGetBoolean = XposedUtils.findMethodExact(
            BaseBundle::class.java,
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (methodBaseGetBoolean != null) {
            try {
                module.hook(methodBaseGetBoolean).intercept { chain ->
                    if (!ConfigManager.isClipboardSensitiveEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    if ("android.content.extra.IS_SENSITIVE" == key) {
                        if (ConfigManager.isVerboseLogEnabled()) {
                            XposedUtils.log(module, "[Clipboard] Bypassed android.content.extra.IS_SENSITIVE check in BaseBundle")
                        }
                        LogBridge.record(
                            LogType.CLIPBOARD,
                            "绕过剪贴板敏感安全标记",
                            "检测到系统 IS_SENSITIVE 标记 (BaseBundle)，强制返回 false 允许记录"
                        )
                        false
                    } else {
                        chain.proceed()
                    }
                }
                XposedUtils.log(module, "[Clipboard] Hooked BaseBundle.getBoolean(String, boolean)")
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Failed to hook BaseBundle.getBoolean", t)
            }
        }
    }
}
