package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.regex.Pattern

object AiSafetyHook {
    private val SAFETY_BLOCKED_PATTERN = Pattern.compile("\"safety_blocked\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE)

    fun sanitizeJson(input: String?): String? {
        if (!ConfigManager.isAiSafetyEnabled()) return input
        if (input == null || !input.contains("safety_blocked")) return input
        val matcher = SAFETY_BLOCKED_PATTERN.matcher(input)
        if (matcher.find()) {
            return matcher.replaceAll("\"safety_blocked\": false")
        }
        return input
    }

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        val fbSClass = XposedUtils.findClass("fb.s", classLoader)
        if (fbSClass != null) {
            // Hook h(String) -> static
            val methodH = XposedUtils.findMethodExact(fbSClass, "h", String::class.java)
            if (methodH != null) {
                try {
                    module.hook(methodH).intercept { chain ->
                        if (!ConfigManager.isAiSafetyEnabled()) return@intercept chain.proceed()
                        val originalJson = chain.getArg(0) as? String
                        val sanitized = sanitizeJson(originalJson)
                        if (sanitized != originalJson) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(module, "[AI Safety] Sanitized safety_blocked in fb.s.h()")
                            }
                            chain.proceed(arrayOf(sanitized))
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "[AI Safety] Hooked fb.s.h(String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook fb.s.h", t)
                }
            }

            // Hook e(String) -> instance
            val methodE = XposedUtils.findMethodExact(fbSClass, "e", String::class.java)
            if (methodE != null) {
                try {
                    module.hook(methodE).intercept { chain ->
                        if (!ConfigManager.isAiSafetyEnabled()) return@intercept chain.proceed()
                        val originalJson = chain.getArg(0) as? String
                        val sanitized = sanitizeJson(originalJson)
                        if (sanitized != originalJson) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(module, "[AI Safety] Sanitized safety_blocked in fb.s.e()")
                            }
                            chain.proceed(arrayOf(sanitized))
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "[AI Safety] Hooked fb.s.e(String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook fb.s.e", t)
                }
            }

            // Hook f(String) -> instance (streaming parser)
            val methodF = XposedUtils.findMethodExact(fbSClass, "f", String::class.java)
            if (methodF != null) {
                try {
                    module.hook(methodF).intercept { chain ->
                        if (!ConfigManager.isAiSafetyEnabled()) return@intercept chain.proceed()
                        val originalJson = chain.getArg(0) as? String
                        val sanitized = sanitizeJson(originalJson)
                        if (sanitized != originalJson) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(module, "[AI Safety] Sanitized safety_blocked in fb.s.f()")
                            }
                            chain.proceed(arrayOf(sanitized))
                        } else {
                            chain.proceed()
                        }
                    }
                    XposedUtils.log(module, "[AI Safety] Hooked fb.s.f(String)")
                } catch (t: Throwable) {
                    XposedUtils.logError(module, "Failed to hook fb.s.f", t)
                }
            }
        } else {
            XposedUtils.logWarn(module, "[AI Safety] Class fb.s not found, skipping specific fb.s hooks")
        }

        // Translation safety refusal hook (aa.b6)
        val b6Class = XposedUtils.findClass("aa.b6", classLoader)
        if (b6Class != null) {
            val methodM = XposedUtils.findFirstMethodByParamTypes(b6Class, null, Any::class.java, XposedUtils.findClass("sc.c", classLoader) ?: Any::class.java)
            if (methodM != null) {
                XposedUtils.log(module, "[AI Safety] Translation flow handler registered")
            }
        }
    }
}
