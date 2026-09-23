package io.mo.xatype.hooks

import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils

object VoiceModerationHook {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        installMiclawErrorHook(module, classLoader)
        installErrorMapperHook(module, classLoader)
        installAsrCallbackHook(module, classLoader)
    }

    private fun installMiclawErrorHook(
        module: XposedInterface,
        classLoader: ClassLoader
    ) {
        val miclawErrorHelperClass =
            XposedUtils.findClass("a8.n", classLoader)

        if (miclawErrorHelperClass == null) {
            XposedUtils.logWarn(
                module,
                "[Voice Moderation] Class a8.n not found"
            )
            return
        }

        val preferredMethodName =
            TargetCompatibility.voiceModerationMethodName(classLoader)

        val candidateNames = linkedSetOf(
            preferredMethodName,
            "f",
            "g"
        )

        val method = candidateNames.firstNotNullOfOrNull { methodName ->
            XposedUtils.findMethodExact(
                miclawErrorHelperClass,
                methodName,
                Context::class.java,
                String::class.java,
                String::class.java
            )?.let { methodName to it }
        }

        if (method == null) {
            XposedUtils.logWarn(
                module,
                "[Voice Moderation] Compatible a8.n moderation entry not found"
            )
            return
        }

        val methodName = method.first
        val executable = method.second

        try {
            module.hook(executable).intercept { chain ->
                if (!ConfigManager.isVoiceModerationEnabled()) {
                    return@intercept chain.proceed()
                }

                val code = chain.getArg(2) as? String
                if ("CONTENT_MODERATION" == code) {
                    if (ConfigManager.isVerboseLogEnabled()) {
                        XposedUtils.log(
                            module,
                            "[Voice Moderation] Suppressed Miclaw " +
                                "CONTENT_MODERATION toast/error"
                        )
                    }
                    null
                } else {
                    chain.proceed()
                }
            }

            XposedUtils.log(
                module,
                "[Voice Moderation] Hooked a8.n.$methodName" +
                    "(Context, String, String)"
            )
        } catch (t: Throwable) {
            XposedUtils.logError(
                module,
                "Failed to hook a8.n.$methodName",
                t
            )
        }
    }

    private fun installErrorMapperHook(
        module: XposedInterface,
        classLoader: ClassLoader
    ) {
        val s8FClass = XposedUtils.findClass("s8.f", classLoader) ?: return
        val methodM = XposedUtils.findMethodExact(
            s8FClass,
            "m",
            Int::class.javaPrimitiveType ?: Integer.TYPE,
            String::class.java
        ) ?: return

        try {
            module.hook(methodM).intercept { chain ->
                if (!ConfigManager.isVoiceModerationEnabled()) {
                    return@intercept chain.proceed()
                }

                val errorCode =
                    (chain.getArg(0) as? Number)?.toInt() ?: 0

                if (errorCode == 30002) {
                    if (ConfigManager.isVerboseLogEnabled()) {
                        XposedUtils.log(
                            module,
                            "[Voice Moderation] Intercepted error 30002 " +
                                "in s8.f.m()"
                        )
                    }
                    chain.proceed(
                        arrayOf(
                            -1,
                            chain.getArg(1)
                        )
                    )
                } else {
                    chain.proceed()
                }
            }

            XposedUtils.log(
                module,
                "[Voice Moderation] Hooked s8.f.m(int, String)"
            )
        } catch (t: Throwable) {
            XposedUtils.logError(
                module,
                "Failed to hook s8.f.m",
                t
            )
        }
    }

    private fun installAsrCallbackHook(
        module: XposedInterface,
        classLoader: ClassLoader
    ) {
        val s8DClass = XposedUtils.findClass("s8.d", classLoader) ?: return
        val methodE = XposedUtils.findMethodExact(
            s8DClass,
            "e",
            Bundle::class.java
        ) ?: return

        try {
            module.hook(methodE).intercept { chain ->
                if (!ConfigManager.isVoiceModerationEnabled()) {
                    return@intercept chain.proceed()
                }

                val bundle = chain.getArg(0) as? Bundle
                if (bundle?.getInt("code", -1) == 30002) {
                    if (ConfigManager.isVerboseLogEnabled()) {
                        XposedUtils.log(
                            module,
                            "[Voice Moderation] Suppressed ASR error " +
                                "30002 callback in s8.d.e()"
                        )
                    }
                    return@intercept null
                }

                chain.proceed()
            }

            XposedUtils.log(
                module,
                "[Voice Moderation] Hooked s8.d.e(Bundle)"
            )
        } catch (t: Throwable) {
            XposedUtils.logError(
                module,
                "Failed to hook s8.d.e",
                t
            )
        }
    }
}
