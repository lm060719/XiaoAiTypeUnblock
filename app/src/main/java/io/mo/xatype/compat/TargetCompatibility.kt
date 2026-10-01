package io.mo.xatype.compat

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import java.util.WeakHashMap

enum class TargetGeneration {
    LEGACY,
    V209,
    V21053,
    UNKNOWN
}

/** Verified obfuscated names; palette and material field layouts are shared. */
data class ModernKeyboardProfile(
    val helperClassName: String,
    val rendererClassName: String,
    val paletteClassName: String,
    val paletteFactoryClassName: String,
    val paletteHolderClassName: String,
    val materialSupportMethod: String,
    val materialUpdateMethod: String,
    val materialRefreshMethod: String,
    val materialCleanupMethod: String,
    val materialVisibilityMethod: String,
    val rendererUpdateMethod: String
)

object TargetCompatibility {

    private val detectedGenerations = WeakHashMap<ClassLoader, TargetGeneration>()
    private val v209Keyboard = ModernKeyboardProfile(
        "bb.b0", "bb.t1", "na.j", "na.u", "na.x",
        "g", "j", "k", "m", "n", "b"
    )
    private val v21053Keyboard = ModernKeyboardProfile(
        "ab.i0", "ab.e2", "ma.k", "ma.v", "ma.x",
        "h", "k", "l", "n", "o", "c"
    )

    fun modernKeyboardProfile(classLoader: ClassLoader): ModernKeyboardProfile? =
        when (detect(classLoader)) {
            TargetGeneration.V209 -> v209Keyboard
            TargetGeneration.V21053 -> v21053Keyboard
            else -> null
        }

    fun voiceModerationClassName(classLoader: ClassLoader): String =
        if (detect(classLoader) == TargetGeneration.V21053) "z7.h" else "a8.n"

    fun asrManagerClassName(classLoader: ClassLoader): String =
        if (detect(classLoader) == TargetGeneration.V21053) "r8.f" else "s8.f"

    fun asrCallbackClassName(classLoader: ClassLoader): String =
        if (detect(classLoader) == TargetGeneration.V21053) "r8.d" else "s8.d"

    @Synchronized
    fun detect(classLoader: ClassLoader): TargetGeneration {
        detectedGenerations[classLoader]?.let { return it }

        val generation = when {
            matchesV21053Structure(classLoader) -> TargetGeneration.V21053
            matchesV209Structure(classLoader) -> TargetGeneration.V209
            matchesLegacyStructure(classLoader) -> TargetGeneration.LEGACY
            else -> TargetGeneration.UNKNOWN
        }
        detectedGenerations[classLoader] = generation
        return generation
    }

    fun aiSafetyParserClassName(classLoader: ClassLoader): String {
        return when (detect(classLoader)) {
            TargetGeneration.V21053 -> "eb.s"
            TargetGeneration.V209 -> "fb.t"
            TargetGeneration.LEGACY -> "fb.s"
            TargetGeneration.UNKNOWN -> {
                if (hasStringParserMethods("fb.t", classLoader)) "fb.t" else "fb.s"
            }
        }
    }

    fun voiceModerationMethodName(classLoader: ClassLoader): String {
        return when (detect(classLoader)) {
            TargetGeneration.V21053 -> "g"
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

    private fun matchesV21053Structure(classLoader: ClassLoader): Boolean {
        val service = findClass("com.mi.ime.MiInputMethodService", classLoader) ?: return false
        val helper = findClass("ab.i0", classLoader) ?: return false
        val palette = findClass("ma.k", classLoader) ?: return false
        val factory = findClass("ma.v", classLoader) ?: return false
        if (runCatching { service.getDeclaredField("hyperMaterialHelper").type }.getOrNull() != helper) {
            return false
        }
        return hasMethod(helper, "b", java.lang.Boolean.TYPE, View::class.java) &&
            hasMethod(helper, "h", java.lang.Boolean.TYPE) &&
            hasMethod(helper, "k", Void.TYPE) &&
            hasMethod(helper, "n", Void.TYPE) &&
            hasMethod(helper, "o", Void.TYPE, java.lang.Boolean.TYPE) &&
            hasMethod(helper, "f", java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE, FrameLayout::class.java, Integer.TYPE) &&
            helper.declaredFields.any { it.name == "a" && it.type == service } &&
            helper.declaredFields.any { it.name == "i" && it.type == View::class.java } &&
            palette.declaredFields.count { it.type == java.lang.Long.TYPE } == 40 &&
            factory.declaredMethods.any {
                it.name == "z" && it.parameterTypes.size == 1 && it.returnType == palette
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
