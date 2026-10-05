package io.mo.xatype.hooks

import io.github.libxposed.api.XposedInterface
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.lang.reflect.Method
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
        val parsers = HostSymbols.methods(HostSymbols.AI_SAFETY_PARSERS).ifEmpty {
            legacyParsers(classLoader)
        }
        var installedCount = 0

        parsers.forEach { method ->
            val label = "${method.declaringClass.name}.${method.name}"
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
                                "[AI Safety] Sanitized safety_blocked in $label()"
                            )
                        }
                        chain.proceed(arrayOf(sanitized))
                    } else {
                        chain.proceed()
                    }
                }

                installedCount++
                XposedUtils.log(module, "[AI Safety] Hooked $label(String)")
            } catch (t: Throwable) {
                XposedUtils.logError(module, "Failed to hook $label", t)
            }
        }

        if (installedCount == 0) {
            XposedUtils.logWarn(module, "[AI Safety] No compatible String parser methods found")
        }
    }

    private fun legacyParsers(classLoader: ClassLoader): List<Method> {
        val parserClassName = TargetCompatibility.aiSafetyParserClassName(classLoader)
        val parserClass = XposedUtils.findClass(parserClassName, classLoader) ?: return emptyList()
        return listOf("h", "e", "f").mapNotNull { methodName ->
            XposedUtils.findMethodExact(parserClass, methodName, String::class.java)
        }
    }
}
