package io.mo.xatype.hooks

import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

object VoiceModerationHook {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        // 1. Hook a8.n.f (MiclawErrorHelper.f) - Blocks CONTENT_MODERATION Toast
        val miclawErrorHelperClass = XposedUtils.findClass("a8.n", classLoader)
        if (miclawErrorHelperClass != null) {
            val methodF = XposedUtils.findMethodExact(
                miclawErrorHelperClass,
                "f",
                Context::class.java,
                String::class.java,
                String::class.java
            )
            if (methodF != null) {
                try {
                    module.hook(methodF).intercept { chain ->
                        if (!ConfigManager.isVoiceModerationEnabled()) return@intercept chain.proceed()
                        val code = chain.getArg(2) as? String
                        if ("CONTENT_MODERATION" == code) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(module, "[Voice Moderation] Suppressed Miclaw CONTENT_MODERATION toast/error")
                            }
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "[Voice Moderation] Hooked a8.n.f(Context, String, String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook a8.n.f", t)
                }
            }
        } else {
            XposedUtils.logWarn(module, "[Voice Moderation] Class a8.n not found")
        }

        // 2. Hook s8.f.m - Error code 30002 mapper
        val s8FClass = XposedUtils.findClass("s8.f", classLoader)
        if (s8FClass != null) {
            val methodM = XposedUtils.findMethodExact(s8FClass, "m", Int::class.javaPrimitiveType ?: Integer.TYPE, String::class.java)
            if (methodM != null) {
                try {
                    module.hook(methodM).intercept { chain ->
                        if (!ConfigManager.isVoiceModerationEnabled()) return@intercept chain.proceed()
                        val errorCode = (chain.getArg(0) as? Number)?.toInt() ?: 0
                        if (errorCode == 30002) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(module, "[Voice Moderation] Intercepted error 30002 in s8.f.m()")
                            }
                            chain.proceed(arrayOf(-1, chain.getArg(1)))
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "[Voice Moderation] Hooked s8.f.m(int, String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook s8.f.m", t)
                }
            }
        }

        // 3. Hook s8.d.e(Bundle) - ASR Callback error receiver
        val s8DClass = XposedUtils.findClass("s8.d", classLoader)
        if (s8DClass != null) {
            val methodE = XposedUtils.findMethodExact(s8DClass, "e", Bundle::class.java)
            if (methodE != null) {
                try {
                    module.hook(methodE).intercept { chain ->
                        if (!ConfigManager.isVoiceModerationEnabled()) return@intercept chain.proceed()
                        val bundle = chain.getArg(0) as? Bundle
                        if (bundle != null) {
                            val code = bundle.getInt("code", -1)
                            if (code == 30002) {
                                if (ConfigManager.isVerboseLogEnabled()) {
                                    XposedUtils.log(module, "[Voice Moderation] Suppressed ASR error 30002 callback in s8.d.e()")
                                }
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                    XposedUtils.log(module, "[Voice Moderation] Hooked s8.d.e(Bundle)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook s8.d.e", t)
                }
            }
        }
    }
}
