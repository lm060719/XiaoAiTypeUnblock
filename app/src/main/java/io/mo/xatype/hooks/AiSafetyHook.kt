package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.regex.Pattern

object AiSafetyHook {

    private val SAFETY_BLOCKED_PATTERN = Pattern.compile(
        "\"safety_blocked\"\\s*:\\s*true",
        Pattern.CASE_INSENSITIVE
    )

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
        val parserClassName = TargetCompatibility.aiSafetyParserClassName(classLoader)
        val parserClass = XposedUtils.findClass(parserClassName, classLoader)

        if (parserClass != null) {
            var installedCount = 0

            listOf("h", "e", "f").forEach { methodName ->
                val method = XposedUtils.findMethodExact(
                    parserClass,
                    methodName,
                    String::class.java
                ) ?: return@forEach

                try {
                    module.hook(method).intercept { chain ->
                        if (!ConfigManager.isAiSafetyEnabled()) {
                            return@intercept chain.proceed()
                        }

                        val originalJson = chain.getArg(0) as? String
                        val sanitized = sanitizeJson(originalJson)

                        if (sanitized != originalJson) {
                            if (ConfigManager.isVerboseLogEnabled()) {
                                XposedUtils.log(
                                    module,
                                    "[AI Safety] Sanitized safety_blocked in " +
                                        "$parserClassName.$methodName()"
                                )
                            }
                            chain.proceed(arrayOf(sanitized))
                        } else {
                            chain.proceed()
                        }
                    }

                    installedCount++
                    XposedUtils.log(
                        module,
                        "[AI Safety] Hooked $parserClassName.$methodName(String)"
                    )
                } catch (t: Throwable) {
                    XposedUtils.logError(
                        module,
                        "Failed to hook $parserClassName.$methodName",
                        t
                    )
                }
            }

            if (installedCount == 0) {
                XposedUtils.logWarn(
                    module,
                    "[AI Safety] No compatible String parser methods found in $parserClassName"
                )
            }
        } else {
            XposedUtils.logWarn(
                module,
                "[AI Safety] Parser class $parserClassName not found"
            )
        }

        val b6Class = XposedUtils.findClass("aa.b6", classLoader)
        if (b6Class != null) {
            val continuationClass =
                XposedUtils.findClass("sc.c", classLoader) ?: Any::class.java

            val methodM = XposedUtils.findFirstMethodByParamTypes(
                b6Class,
                null,
                Any::class.java,
                continuationClass
            )

            if (methodM != null) {
                XposedUtils.log(
                    module,
                    "[AI Safety] Translation flow handler registered"
                )
            }
        }
    }
}
