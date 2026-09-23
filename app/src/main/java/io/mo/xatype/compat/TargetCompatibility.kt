package io.mo.xatype.compat

import android.content.Context
import android.view.View
import java.util.WeakHashMap

enum class TargetGeneration {
    LEGACY,
    V209,
    UNKNOWN
}

object TargetCompatibility {

    private val detectedGenerations = WeakHashMap<ClassLoader, TargetGeneration>()

    @Synchronized
    fun detect(classLoader: ClassLoader): TargetGeneration {
        detectedGenerations[classLoader]?.let { return it }

        val generation = when {
            matchesV209Structure(classLoader) -> TargetGeneration.V209
            matchesLegacyStructure(classLoader) -> TargetGeneration.LEGACY
            else -> TargetGeneration.UNKNOWN
        }
        detectedGenerations[classLoader] = generation
        return generation
    }

    fun aiSafetyParserClassName(classLoader: ClassLoader): String {
        return when (detect(classLoader)) {
            TargetGeneration.V209 -> "fb.t"
            TargetGeneration.LEGACY -> "fb.s"
            TargetGeneration.UNKNOWN -> {
                if (hasStringParserMethods("fb.t", classLoader)) "fb.t" else "fb.s"
            }
        }
    }

    fun voiceModerationMethodName(classLoader: ClassLoader): String {
        return when (detect(classLoader)) {
            TargetGeneration.V209 -> "g"
            TargetGeneration.LEGACY -> "f"
            TargetGeneration.UNKNOWN -> {
                val clazz = findClass("a8.n", classLoader)
                when {
                    hasMethod(
                        clazz,
                        "g",
                        Void.TYPE,
                        Context::class.java,
                        String::class.java,
                        String::class.java
                    ) -> "g"

                    else -> "f"
                }
            }
        }
    }

    private fun matchesV209Structure(classLoader: ClassLoader): Boolean {
        val serviceClass = findClass("com.mi.ime.MiInputMethodService", classLoader) ?: return false
        val helperField = runCatching {
            serviceClass.getDeclaredField("hyperMaterialHelper")
        }.getOrNull() ?: return false
        if (helperField.type.name != "bb.b0") return false

        val helperClass = findClass("bb.b0", classLoader) ?: return false
        val clipboardClass = findClass("bb.u", classLoader) ?: return false
        val paletteClass = findClass("na.j", classLoader) ?: return false

        val helperMatches =
            hasMethod(helperClass, "b", Boolean::class.javaPrimitiveType, View::class.java) &&
                hasMethod(helperClass, "g", Boolean::class.javaPrimitiveType) &&
                helperClass.declaredFields.any {
                    it.name == "a" && it.type.name == "com.mi.ime.MiInputMethodService"
                }

        val clipboardMatches =
            clipboardClass.declaredFields.any {
                it.type.name == "android.content.ClipboardManager"
            }

        val paletteMatches =
            paletteClass.declaredFields.count {
                it.type == Long::class.javaPrimitiveType
            } >= 30

        return helperMatches && clipboardMatches && paletteMatches
    }

    private fun matchesLegacyStructure(classLoader: ClassLoader): Boolean {
        val helperClass = findClass("bb.u", classLoader) ?: return false
        val legacyColors = findClass("na.d", classLoader) ?: return false

        val helperMatches =
            hasMethod(helperClass, "h", Boolean::class.javaPrimitiveType) &&
                helperClass.declaredFields.any {
                    it.name == "a" && it.type.name == "com.mi.ime.MiInputMethodService"
                }

        val colorsMatch =
            legacyColors.declaredFields.any {
                it.type == Long::class.javaPrimitiveType
            }

        return helperMatches && colorsMatch
    }

    private fun hasStringParserMethods(
        className: String,
        classLoader: ClassLoader
    ): Boolean {
        val clazz = findClass(className, classLoader) ?: return false
        return listOf("h", "e", "f").count { name ->
            clazz.declaredMethods.any { method ->
                method.name == name &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        } >= 2
    }

    private fun hasMethod(
        clazz: Class<*>?,
        name: String,
        returnType: Class<*>?,
        vararg parameterTypes: Class<*>
    ): Boolean {
        if (clazz == null) return false

        return clazz.declaredMethods.any { method ->
            method.name == name &&
                (returnType == null || method.returnType == returnType) &&
                method.parameterTypes.contentEquals(parameterTypes)
        }
    }

    private fun findClass(
        className: String,
        classLoader: ClassLoader
    ): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}
