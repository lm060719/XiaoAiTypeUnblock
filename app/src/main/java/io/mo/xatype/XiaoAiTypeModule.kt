package io.mo.xatype

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.mo.xatype.compat.AdaptationReporter
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.hooks.AiSafetyHook
import io.mo.xatype.hooks.ClipboardPermanentHook
import io.mo.xatype.hooks.ClipboardSensitiveHook
import io.mo.xatype.hooks.CloudBlacklistHook
import io.mo.xatype.hooks.HyperOsVersionHook
import io.mo.xatype.hooks.InputDiagnosticsHook
import io.mo.xatype.hooks.KeyboardStyleHook
import io.mo.xatype.hooks.KeyboardHeightUnblockHook
import io.mo.xatype.hooks.KeyboardBottomSpacingHook
import io.mo.xatype.hooks.SystemUiNavigationGuardHook
import io.mo.xatype.hooks.VoiceModerationHook
import io.mo.xatype.util.XposedUtils
import java.io.File

class XiaoAiTypeModule : XposedModule() {

    private var processCacheDir: File? = null

    companion object {
        const val TARGET_PACKAGE = "com.xiaomi.type"
        const val PHRASE_PACKAGE = "com.miui.phrase"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (
            param.packageName != TARGET_PACKAGE &&
            param.packageName != PHRASE_PACKAGE &&
            param.packageName != SYSTEM_UI_PACKAGE
        ) return

        // Initialize remote preferences
        ConfigManager.initRemote(this)
        AdaptationReporter.install(this)
        if (param.isFirstPackage) {
            // The process's own storage; phrase classes also load inside the IME.
            processCacheDir = File(param.applicationInfo.deviceProtectedDataDir, "cache")
        }

        val classLoader = param.defaultClassLoader
        XposedUtils.log(this, "================================================")
        XposedUtils.log(this, "XiaoAiTypeUnblock initialized on libxposed API 102")
        XposedUtils.log(this, "Target: ${param.packageName} (FirstPackage=${param.isFirstPackage})")
        XposedUtils.log(this, "Framework: $frameworkName $frameworkVersion (API ${apiVersion})")
        if (param.packageName == TARGET_PACKAGE) {
            XposedUtils.log(
                this,
                "Target compatibility profile: " +
                    TargetCompatibility.detect(classLoader).name
            )
            initHostSymbols(param)
        }
        XposedUtils.log(this, "================================================")

        if (param.packageName == SYSTEM_UI_PACKAGE) {
            try {
                SystemUiNavigationGuardHook.install(this, classLoader)
            } catch (t: Throwable) {
                XposedUtils.logError(this, "Error installing SystemUiNavigationGuardHook", t)
            }
            return
        }

        try {
            if (param.packageName == PHRASE_PACKAGE) {
                ClipboardPermanentHook.install(
                    this,
                    classLoader,
                    param.applicationInfo.sourceDir,
                    processCacheDir
                )
            } else {
                ClipboardPermanentHook.install(this, classLoader)
            }
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing ClipboardPermanentHook", t)
        }

        // com.miui.phrase owns persistence. The remaining hooks target only the
        // Xiaomi input method process.
        if (param.packageName == PHRASE_PACKAGE) return

        if (BuildConfig.INPUT_DIAGNOSTICS) {
            try {
                InputDiagnosticsHook.install(this, classLoader)
            } catch (t: Throwable) {
                XposedUtils.logError(this, "Error installing InputDiagnosticsHook", t)
            }
        }

        try {
            HyperOsVersionHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing HyperOsVersionHook", t)
        }

        try {
            AiSafetyHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing AiSafetyHook", t)
        }

        try {
            VoiceModerationHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing VoiceModerationHook", t)
        }

        try {
            CloudBlacklistHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing CloudBlacklistHook", t)
        }

        try {
            ClipboardSensitiveHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing ClipboardSensitiveHook", t)
        }

        try {
            KeyboardStyleHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing KeyboardStyleHook", t)
        }

        try {
            KeyboardHeightUnblockHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing KeyboardHeightUnblockHook", t)
        }

        try {
            KeyboardBottomSpacingHook.install(this)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing KeyboardBottomSpacingHook", t)
        }

        XposedUtils.log(this, "XiaoAiTypeUnblock hooks installation complete.")
    }

    private fun initHostSymbols(param: XposedModuleInterface.PackageLoadedParam) {
        try {
            val appInfo = param.applicationInfo
            // Device-protected storage stays readable before the first unlock,
            // when the input method already runs.
            val cache = File(appInfo.deviceProtectedDataDir, "cache/xatype-symbols.json")
            val summary = HostSymbols.init(
                param.defaultClassLoader,
                appInfo.sourceDir,
                cache,
                XposedUtils.moduleStamp(this)
            )
            XposedUtils.log(this, "Host symbols: $summary")
            AdaptationReporter.report(this, TARGET_PACKAGE, HostSymbols)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error resolving host symbols", t)
        }
    }
}
