package io.mo.xatype.compat

import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * Resolves every member of [ModernKeyboardProfile]. Anchors were checked on
 * 0.2.910, 0.2.974 and 0.2.1053, whose helper method names all differ.
 */
internal object ModernKeyboardFingerprint {
    private const val SERVICE = "com.mi.ime.MiInputMethodService"

    /** Parcel read order of the miuix material token; fixed by its IPC format. */
    private const val TOKEN_BLEND_INDEX = 37
    private const val TOKEN_BLUR_INDEX = 48

    fun resolve(bridge: DexKitBridge, loader: ClassLoader): ModernKeyboardProfile {
        val service = Class.forName(SERVICE, false, loader)
        val helper = service.getDeclaredField("hyperMaterialHelper").type
        val helperData = checkNotNull(bridge.getClassData(helper)) { "helper class data" }
        val helperMethods = helperData.methods
        fun helperMethod(what: String, filter: (MethodData) -> Boolean) =
            single(helperMethods.filter(filter), what)

        val apply = helperMethod("helper apply") {
            it.paramTypeNames == listOf(View::class.java.name) && it.returnTypeName == "boolean" &&
                "applyMaterial failed, disabling blur" in it.usingStrings
        }
        val serviceField = single(helper.declaredFields.filter { it.type == service }, "helper service")
        val materialField = single(
            helper.declaredFields.filter { it.type == View::class.java }, "helper material view"
        )
        // The state cleared when applying the material fails.
        val stateField = single(
            apply.usingFields.map { it.field }
                .filter { it.className == helper.name }
                .map { helper.getDeclaredField(it.name) }
                .filter { field -> field.type.methods.any { it.name == "setValue" } }
                .distinct(),
            "helper material state"
        )
        val support = helperMethod("material support") { method ->
            method.paramCount == 0 && method.returnTypeName == "boolean" &&
                method.usingFields.any { it.field.className == helper.name && it.field.name == stateField.name }
        }
        val update = helperMethod("material update") {
            it.paramCount == 0 && it.returnTypeName == "void" &&
                "packageMaterialVersions" in it.usingStrings
        }
        val cleanup = helperMethod("material cleanup") {
            it.paramCount == 0 && it.returnTypeName == "void" && "inner shadow" in it.usingStrings
        }
        val refresh = helperMethod("material refresh") {
            it.paramCount == 0 && it.returnTypeName == "void" &&
                "pass-window blur" in it.usingStrings && "inner shadow" !in it.usingStrings
        }
        val visibility = helperMethod("material visibility") { method ->
            method.paramTypeNames == listOf("boolean") && method.returnTypeName == "void" &&
                method.invokes.count { it.className == View::class.java.name && it.name == "setVisibility" } >= 2
        }
        val detach = helperMethod("material detach") { method ->
            method.paramCount == 0 && method.returnTypeName == "void" && method.usingStrings.isEmpty() &&
                method.invokes.any { it.name == "removeView" }
        }
        val attach = helperMethod("material attach") {
            it.paramTypeNames == listOf("boolean", FrameLayout::class.java.name, "int") &&
                it.returnTypeName == "boolean"
        }

        // Token lazies: helper fields with a getValue() other than the Compose states.
        val tokenFields = helper.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) && field.type != stateField.type &&
                field.type.methods.any { it.name == "getValue" && it.parameterCount == 0 }
        }
        if (tokenFields.size != 2) throw FingerprintMiss("material token lazies: ${tokenFields.size}")
        val tokenFactory = helperMethod("material token factory") {
            it.paramTypeNames == listOf("boolean") && "frosted" in it.usingStrings
        }
        val token = tokenFactory.getReturnTypeInstance(loader)
        val tokenWrites = checkNotNull(
            bridge.getMethodData(token.getDeclaredConstructor(Parcel::class.java))
        ).usingFields.filter { it.usingType.isWrite() }.map { it.field }
        val blend = tokenWrites.getOrNull(TOKEN_BLEND_INDEX)
        val blur = tokenWrites.getOrNull(TOKEN_BLUR_INDEX)
        if (blend?.typeName != "int[]" || blur?.typeName != "int") {
            throw FingerprintMiss("material token parcel layout")
        }

        val renderer = single(
            bridge.findMethod {
                matcher {
                    paramCount(0)
                    returnType("void")
                    usingStrings("InnerShadowRenderer")
                }
            }, "inner shadow renderer"
        )
        val rendererType = renderer.getClassInstance(loader)
        val rendererFields = rendererType.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val rendererService = single(rendererFields.filter { it.type == service }, "renderer service")
        val rendererView = single(rendererFields.filter { it.type == View::class.java }, "renderer view")
        // The cached effect parameters; clearing them forces a rebuild.
        val rendererEffect = single(
            rendererFields.filter {
                !Modifier.isFinal(it.modifiers) && !it.type.isPrimitive &&
                    it.type != View::class.java && it.type.name != "android.graphics.RuntimeShader"
            }, "renderer effect"
        )

        val api = single(
            bridge.findMethod {
                matcher {
                    name = "<clinit>"
                    usingStrings("persist.sys.background_blur_supported")
                }
            }, "material API"
        )
        val apiClear = single(
            api.getClassInstance(loader).declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 2 && it.parameterTypes[0] == View::class.java
            }, "material API clear"
        )

        val palette = single(
            bridge.findClass {
                matcher { usingStrings(listOf("KeyboardColors("), StringMatchType.StartsWith) }
            }, "KeyboardColors"
        ).getInstance(loader)
        val paletteFields = DataClassLabels.resolve(palette)
        requireLabels("KeyboardColors", paletteFields, ModernKeyboardProfile.PALETTE_LABELS)
        val appsPanel = palette.getDeclaredField(paletteFields.getValue("appsPanel")).type
        val appsPanelFields = DataClassLabels.resolve(appsPanel)
        requireLabels("appsPanel", appsPanelFields, ModernKeyboardProfile.APPS_PANEL_LABELS)

        // The @Composable accessor that picks the light or dark palette.
        val factory = single(
            bridge.findMethod {
                matcher {
                    returnType(palette)
                    paramCount(1)
                }
            }.filter { it.className != palette.name }, "palette factory"
        )
        // The base light/dark palettes the helper attaches with; the holder
        // also keeps derived variants built by copy().
        val holderFields = attach.usingFields.map { it.field }
            .filter { it.typeName == palette.name }
            .distinctBy { it.className + "." + it.name }
        if (holderFields.size != 2 || holderFields.map { it.className }.toSet().size != 1) {
            throw FingerprintMiss("palette holder: " + holderFields.size + " fields")
        }

        return ModernKeyboardProfile(
            helperClassName = helper.name,
            rendererClassName = rendererType.name,
            paletteClassName = palette.name,
            paletteFactoryClassName = factory.className,
            paletteHolderClassName = holderFields.first().className,
            materialSupportMethod = support.name,
            materialUpdateMethod = update.name,
            materialRefreshMethod = refresh.name,
            materialCleanupMethod = cleanup.name,
            materialVisibilityMethod = visibility.name,
            rendererUpdateMethod = renderer.name,
            materialApiClassName = api.className,
            helperServiceField = serviceField.name,
            helperMaterialField = materialField.name,
            helperStateField = stateField.name,
            helperTokenFields = tokenFields.map { it.name },
            helperApplyMethod = apply.name,
            helperDetachMethod = detach.name,
            helperAttachMethod = attach.name,
            rendererServiceField = rendererService.name,
            rendererViewField = rendererView.name,
            rendererEffectField = rendererEffect.name,
            paletteFactoryMethod = factory.name,
            paletteHolderFields = holderFields.map { it.name },
            materialApiClearMethod = apiClear.name,
            tokenBlurField = blur.name,
            tokenBlendField = blend.name,
            paletteFields = ModernKeyboardProfile.PALETTE_LABELS.associateWith(paletteFields::getValue),
            appsPanelFields = ModernKeyboardProfile.APPS_PANEL_LABELS.associateWith(appsPanelFields::getValue)
        )
    }

    private fun requireLabels(what: String, found: Map<String, String>, needed: List<String>) {
        val missing = needed.filter { it !in found }
        if (missing.isNotEmpty()) throw FingerprintMiss("$what labels missing: $missing")
    }
}
