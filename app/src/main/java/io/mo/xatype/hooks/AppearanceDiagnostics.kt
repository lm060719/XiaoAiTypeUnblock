package io.mo.xatype.hooks

import android.inputmethodservice.InputMethodService
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.BuildConfig
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.XposedUtils
import java.util.concurrent.atomic.AtomicInteger

/** Bounded window/material observations; never logs editor or key text. */
internal object AppearanceDiagnostics {
    private val count = AtomicInteger()

    fun record(
        module: XposedInterface,
        service: InputMethodService,
        stage: String,
        material: View? = null,
        compositor: Boolean? = null
    ) {
        if (!BuildConfig.INPUT_DIAGNOSTICS && !ConfigManager.isVerboseLogEnabled()) return
        if (count.getAndIncrement() >= 60) return
        runCatching {
            val configuration = service.resources.configuration
            val window = service.window?.window
            val helper = XposedUtils.getObjectField(service, "hyperMaterialHelper")
            val stateField = TargetCompatibility.modernKeyboardProfile(service.classLoader)
                ?.helperStateField ?: "e"
            val nativeEnabled = helper?.let { XposedUtils.getObjectField(it, stateField) }
            val nativeState = nativeEnabled?.javaClass?.methods?.firstOrNull {
                it.name == "getValue" && it.parameterCount == 0
            }?.invoke(nativeEnabled)
            val passBlur = material?.let {
                runCatching { View::class.java.getMethod("getPassWindowBlurEnabled").invoke(it) }.getOrNull()
            }
            XposedUtils.log(module, "[AppearanceDiag] $stage: " +
                "style=${ConfigManager.isStyleEnabled()} bg=${ConfigManager.getBgType()} " +
                "opacity=${ConfigManager.getOpacity()} blur=${ConfigManager.getBlurRadius()} " +
                "orientation=${configuration.orientation} sw=${configuration.smallestScreenWidthDp} " +
                "widthDp=${configuration.screenWidthDp} density=${configuration.densityDpi} " +
                "night=${configuration.uiMode and 48} nativeState=$nativeState " +
                "material=${material?.javaClass?.name ?: "null"} " +
                "size=${material?.width}x${material?.height} attached=${material?.isAttachedToWindow} " +
                "mainWindow=${material != null && material.rootView === window?.decorView} " +
                "shown=${material?.isShown} passBlur=$passBlur compositor=$compositor " +
                "windowFormat=${window?.attributes?.format}")
        }.onFailure { XposedUtils.logError(module, "[AppearanceDiag] Observation failed", it) }
    }
}
