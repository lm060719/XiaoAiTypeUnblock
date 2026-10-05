package io.mo.xatype.hooks

import android.content.Context
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Method

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
        val executable = HostSymbols.method(HostSymbols.VOICE_MODERATION)
            ?: legacyMiclawMethod(classLoader)
        if (executable == null) {
            XposedUtils.logWarn(
                module,
                "[Voice Moderation] Compatible Miclaw moderation entry not found"
            )
            return
        }
        val label = "${executable.declaringClass.name}.${executable.name}"

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
                "[Voice Moderation] Hooked $label(Context, String, String)"
            )
        } catch (t: Throwable) {
            XposedUtils.logError(module, "Failed to hook $label", t)
        }
    }

    private fun legacyMiclawMethod(classLoader: ClassLoader): Method? {
        val className = TargetCompatibility.voiceModerationClassName(classLoader)
        val clazz = XposedUtils.findClass(className, classLoader) ?: return null
        val preferred = TargetCompatibility.voiceModerationMethodName(classLoader)
        return linkedSetOf(preferred, "f", "g").firstNotNullOfOrNull { methodName ->
            XposedUtils.findMethodExact(
                clazz,
                methodName,
                Context::class.java,
                String::class.java,
                String::class.java
            )
        }
    }

    private fun installErrorMapperHook(
        module: XposedInterface,
        classLoader: ClassLoader
    ) {
        val methodM = HostSymbols.method(HostSymbols.ASR_ERROR_MAPPER)
            ?: XposedUtils.findClass(
                TargetCompatibility.asrManagerClassName(classLoader),
                classLoader
            )?.let {
                XposedUtils.findMethodExact(it, "m", Integer.TYPE, String::class.java)
            }
            ?: return
        val label = "${methodM.declaringClass.name}.${methodM.name}"

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
                                "in $label()"
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
                "[Voice Moderation] Hooked $label(int, String)"
            )
        } catch (t: Throwable) {
            XposedUtils.logError(
                module,
                "Failed to hook $label",
                t
            )
        }
    }

    private fun installAsrCallbackHook(
        module: XposedInterface,
        classLoader: ClassLoader
    ) {
        val methodE = HostSymbols.method(HostSymbols.ASR_ERROR_CALLBACK)
            ?: XposedUtils.findClass(
                TargetCompatibility.asrCallbackClassName(classLoader),
                classLoader
            )?.let { XposedUtils.findMethodExact(it, "e", Bundle::class.java) }
            ?: return
        val label = "${methodE.declaringClass.name}.${methodE.name}"

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
                                "30002 callback in $label()"
                        )
                    }
                    return@intercept null
                }

                chain.proceed()
            }

            XposedUtils.log(
                module,
                "[Voice Moderation] Hooked $label(Bundle)"
            )
        } catch (t: Throwable) {
            XposedUtils.logError(
                module,
                "Failed to hook $label",
                t
            )
        }
    }
}
