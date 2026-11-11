package io.mo.xatype.hooks

import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Method

object CloudBlacklistHook {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        // 1. The engine delegate's onPyCloudAttachUpdate. Its class is renamed between
        //    builds (smartengine.c1 up to 0.2.1053, d1 in 0.2.1246).
        val methodAttach = HostSymbols.method(HostSymbols.CLOUD_BLACKLIST) ?: legacyAttachMethod(classLoader)
        if (methodAttach != null) {
            val label = "${methodAttach.declaringClass.name}.onPyCloudAttachUpdate"
            try {
                module.hook(methodAttach).intercept { chain ->
                    if (!ConfigManager.isCloudBlacklistEnabled()) return@intercept chain.proceed()
                    val blacklistStr = chain.getArg(1) as? String
                    if (!blacklistStr.isNullOrEmpty()) {
                        XposedUtils.logDebug(module) { "[Cloud Blacklist] Intercepted and cleared cloud blacklist (len=${blacklistStr.length}) in $label" }
                    }
                    // Replace blacklist string and version with empty strings
                    chain.proceed(arrayOf(chain.getArg(0), "", "", chain.getArg(3), chain.getArg(4)))
                }
                XposedUtils.log(module, "[Cloud Blacklist] Hooked $label()")
            } catch (t: Throwable) {
                HostSymbols.markUnavailable(HostSymbols.CLOUD_BLACKLIST, t)
                XposedUtils.logError(module, "Failed to hook $label", t)
            }
        } else {
            XposedUtils.logWarn(module, "[Cloud Blacklist] onPyCloudAttachUpdate implementation not found")
        }

        // 2. Hook com.iflytek.inputmethod.smart.api.entity.PinyinCloudAttachResult
        val attachResultClass = XposedUtils.findClass("com.iflytek.inputmethod.smart.api.entity.PinyinCloudAttachResult", classLoader)
        if (attachResultClass != null) {
            // Hook getBlackListStr() -> return ""
            val methodGet = XposedUtils.findMethodExact(attachResultClass, "getBlackListStr")
            if (methodGet != null) {
                try {
                    module.hook(methodGet).intercept { chain ->
                        if (!ConfigManager.isCloudBlacklistEnabled()) {
                            chain.proceed()
                        } else {
                            "" // Always return empty blacklist
                        }
                    }
                    XposedUtils.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.getBlackListStr() -> return empty")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook PinyinCloudAttachResult.getBlackListStr", t)
                }
            }

            // Hook setBlackListStr(String) -> set ""
            val methodSet = XposedUtils.findMethodExact(attachResultClass, "setBlackListStr", String::class.java)
            if (methodSet != null) {
                try {
                    module.hook(methodSet).intercept { chain ->
                        if (!ConfigManager.isCloudBlacklistEnabled()) {
                            chain.proceed()
                        } else {
                            chain.proceed(arrayOf(""))
                        }
                    }
                    XposedUtils.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.setBlackListStr(String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook PinyinCloudAttachResult.setBlackListStr", t)
                }
            }

            // Hook fromBundle(Bundle)
            val methodFromBundle = XposedUtils.findMethodExact(attachResultClass, "fromBundle", Bundle::class.java)
            if (methodFromBundle != null) {
                try {
                    // Clear through the public setter rather than its obfuscated backing field.
                    var clearFailureLogged = false
                    module.hook(methodFromBundle).intercept { chain ->
                        val result = chain.proceed()
                        if (ConfigManager.isCloudBlacklistEnabled()) {
                            try {
                                checkNotNull(methodSet) { "setBlackListStr(String) not found" }
                                    .invoke(chain.getThisObject(), "")
                            } catch (t: Throwable) {
                                if (!clearFailureLogged) {
                                    clearFailureLogged = true
                                    // getBlackListStr() is still forced empty above; this path is a fallback.
                                    XposedUtils.logWarn(module, "[Cloud Blacklist] Could not clear blacklist after fromBundle: $t")
                                }
                            }
                        }
                        result
                    }
                    XposedUtils.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.fromBundle(Bundle)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook PinyinCloudAttachResult.fromBundle", t)
                }
            }
        }
    }

    /** Verified on 0.2.701 to 0.2.1053; used only when the fingerprint is unavailable. */
    private fun legacyAttachMethod(classLoader: ClassLoader): Method? =
        XposedUtils.findClass("com.iflytek.inputmethod.smartengine.c1", classLoader)?.let {
            XposedUtils.findMethodExact(
                it,
                "onPyCloudAttachUpdate",
                Integer.TYPE,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
        }
}
