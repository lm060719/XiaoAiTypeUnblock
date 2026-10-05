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

/**
 * Obfuscated members used by the modern (Compose) appearance hooks. The
 * defaults are the names shared by the verified 0.2.974 and 0.2.1053 builds;
 * [HostFingerprints] resolves every entry for other builds.
 */
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
    val rendererUpdateMethod: String,
    val materialApiClassName: String,
    val helperServiceField: String = "a",
    val helperMaterialField: String = "i",
    val helperStateField: String = "e",
    val helperTokenFields: List<String> = listOf("r", "s"),
    val helperApplyMethod: String = "b",
    val helperDetachMethod: String = "e",
    val helperAttachMethod: String = "f",
    val rendererServiceField: String = "a",
    val rendererViewField: String = "c",
    val rendererEffectField: String = "d",
    val paletteFactoryMethod: String = "z",
    /** Static palettes on the holder; light and dark are told apart by `isDark`. */
    val paletteHolderFields: List<String> = listOf("d", "e"),
    val materialApiClearMethod: String = "a",
    val tokenBlurField: String = "p",
    val tokenBlendField: String = "e",
    /** KeyboardColors property label to field name. */
    val paletteFields: Map<String, String> = VERIFIED_PALETTE_FIELDS,
    /** AppsPanel colors property label to field name. */
    val appsPanelFields: Map<String, String> = VERIFIED_APPS_PANEL_FIELDS
) {
    fun palette(label: String): String = paletteFields.getValue(label)

    fun appsPanel(label: String): String = appsPanelFields.getValue(label)

    fun toProperties(): List<String> = buildList {
        SCALARS.forEach { (key, getter) -> add(key + "=" + getter(this@ModernKeyboardProfile)) }
        add("helperTokenFields=" + helperTokenFields.joinToString(","))
        add("paletteHolderFields=" + paletteHolderFields.joinToString(","))
        paletteFields.forEach { (label, field) -> add("palette.$label=$field") }
        appsPanelFields.forEach { (label, field) -> add("apps.$label=$field") }
    }

    companion object {
        /** Labels the hooks read or write; resolution fails unless all are found. */
        val PALETTE_LABELS = listOf(
            "keyboardBackground", "keyboardBackgroundTop", "toolbarBackground",
            "keyBackgroundDefault", "keyBackgroundPressed", "keyBackgroundSpecial",
            "keyBackgroundEnter", "keyBackgroundEnterGradientEnd", "keyTextColor",
            "keyTextColorSecondary", "keyTextColorEnter", "keyHintColor",
            "modeSelectorTitleColor", "navBackIconColor", "toolbarIconColor",
            "toolbarIconColorCollapse", "symbolLockIndicatorInactive", "bottomBarIconColor",
            "candidateExpandIconColor", "modeSelectorUnselectedIconColor",
            "modeSelectorUnselectedTextColor", "appsPanel", "isDark"
        )
        val APPS_PANEL_LABELS = listOf(
            "background", "cardBackground", "cardIconColor", "cardTextColor",
            "toggleActiveColor", "backArrowColor", "tooltipBackground", "tooltipTextColor"
        )

        private val VERIFIED_PALETTE_FIELDS = PALETTE_LABELS.zip(
            listOf(
                "a", "b", "u", "c", "d", "e", "f", "g", "h", "i", "j", "k",
                "l", "m", "w", "x", "y", "A", "I", "L", "M", "U", "Y"
            )
        ).toMap()
        private val VERIFIED_APPS_PANEL_FIELDS = APPS_PANEL_LABELS.zip(
            listOf("a", "b", "c", "d", "f", "g", "h", "i")
        ).toMap()

        private val SCALARS: List<Pair<String, (ModernKeyboardProfile) -> String>> = listOf(
            "helperClassName" to { it.helperClassName },
            "rendererClassName" to { it.rendererClassName },
            "paletteClassName" to { it.paletteClassName },
            "paletteFactoryClassName" to { it.paletteFactoryClassName },
            "paletteHolderClassName" to { it.paletteHolderClassName },
            "materialSupportMethod" to { it.materialSupportMethod },
            "materialUpdateMethod" to { it.materialUpdateMethod },
            "materialRefreshMethod" to { it.materialRefreshMethod },
            "materialCleanupMethod" to { it.materialCleanupMethod },
            "materialVisibilityMethod" to { it.materialVisibilityMethod },
            "rendererUpdateMethod" to { it.rendererUpdateMethod },
            "materialApiClassName" to { it.materialApiClassName },
            "helperServiceField" to { it.helperServiceField },
            "helperMaterialField" to { it.helperMaterialField },
            "helperStateField" to { it.helperStateField },
            "helperApplyMethod" to { it.helperApplyMethod },
            "helperDetachMethod" to { it.helperDetachMethod },
            "helperAttachMethod" to { it.helperAttachMethod },
            "rendererServiceField" to { it.rendererServiceField },
            "rendererViewField" to { it.rendererViewField },
            "rendererEffectField" to { it.rendererEffectField },
            "paletteFactoryMethod" to { it.paletteFactoryMethod },
            "materialApiClearMethod" to { it.materialApiClearMethod },
            "tokenBlurField" to { it.tokenBlurField },
            "tokenBlendField" to { it.tokenBlendField }
        )

        fun fromProperties(lines: List<String>): ModernKeyboardProfile {
            val values = lines.associate { it.substringBefore('=') to it.substringAfter('=') }
            fun v(key: String) = values[key] ?: throw IllegalArgumentException("missing $key")
            fun prefixed(prefix: String) = values.filterKeys { it.startsWith(prefix) }
                .mapKeys { it.key.removePrefix(prefix) }
            return ModernKeyboardProfile(
                v("helperClassName"), v("rendererClassName"), v("paletteClassName"),
                v("paletteFactoryClassName"), v("paletteHolderClassName"),
                v("materialSupportMethod"), v("materialUpdateMethod"), v("materialRefreshMethod"),
                v("materialCleanupMethod"), v("materialVisibilityMethod"),
                v("rendererUpdateMethod"), v("materialApiClassName"),
                helperServiceField = v("helperServiceField"),
                helperMaterialField = v("helperMaterialField"),
                helperStateField = v("helperStateField"),
                helperTokenFields = v("helperTokenFields").split(','),
                helperApplyMethod = v("helperApplyMethod"),
                helperDetachMethod = v("helperDetachMethod"),
                helperAttachMethod = v("helperAttachMethod"),
                rendererServiceField = v("rendererServiceField"),
                rendererViewField = v("rendererViewField"),
                rendererEffectField = v("rendererEffectField"),
                paletteFactoryMethod = v("paletteFactoryMethod"),
                paletteHolderFields = v("paletteHolderFields").split(','),
                materialApiClearMethod = v("materialApiClearMethod"),
                tokenBlurField = v("tokenBlurField"),
                tokenBlendField = v("tokenBlendField"),
                paletteFields = prefixed("palette."),
                appsPanelFields = prefixed("apps.")
            )
        }
    }
}

object TargetCompatibility {

    private val detectedGenerations = WeakHashMap<ClassLoader, TargetGeneration>()
    private val v209Keyboard = ModernKeyboardProfile(
        "bb.b0", "bb.t1", "na.j", "na.u", "na.x",
        "g", "j", "k", "m", "n", "b", "xe.b"
    )
    private val v21053Keyboard = ModernKeyboardProfile(
        "ab.i0", "ab.e2", "ma.k", "ma.v", "ma.x",
        "h", "k", "l", "n", "o", "c", "we.b"
    )

    fun modernKeyboardProfile(classLoader: ClassLoader): ModernKeyboardProfile? =
        HostSymbols.modernKeyboard() ?: when (detect(classLoader)) {
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
