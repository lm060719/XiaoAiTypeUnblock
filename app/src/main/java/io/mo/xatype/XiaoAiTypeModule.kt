package io.mo.xatype

import android.app.Application
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.mo.xatype.compat.AdaptationReporter
import io.mo.xatype.compat.HostSymbols
import io.mo.xatype.compat.TargetCompatibility
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.hooks.AiAbilityUnlockHook
import io.mo.xatype.hooks.AiSafetyHook
import io.mo.xatype.hooks.ClipboardPermanentHook
import io.mo.xatype.hooks.ClipboardSensitiveHook
import io.mo.xatype.hooks.ClipboardSplitHook
import io.mo.xatype.hooks.CloudBlacklistHook
import io.mo.xatype.hooks.HyperOsVersionHook
import io.mo.xatype.hooks.InputDiagnosticsHook
import io.mo.xatype.hooks.EmojiCandidateHook
import io.mo.xatype.hooks.Keyboard18Hook
import io.mo.xatype.hooks.KeyboardBottomSpacingHook
import io.mo.xatype.hooks.KeyboardToolbarHeightHook
import io.mo.xatype.hooks.KeyboardToolbarMenuHook
import io.mo.xatype.hooks.OneHandHook
import io.mo.xatype.hooks.KeyboardHeightUnblockHook
import io.mo.xatype.hooks.KeyboardHapticsHook
import io.mo.xatype.hooks.KeyboardSelectionHook
import io.mo.xatype.hooks.KeyboardPositionsHook
import io.mo.xatype.hooks.KeyboardShortcutsHook
import io.mo.xatype.hooks.KeyboardStyleHook
import io.mo.xatype.hooks.OnlineDictImportHook
import io.mo.xatype.hooks.ShapeInputHook
import io.mo.xatype.hooks.SystemUiNavigationGuardHook
import io.mo.xatype.hooks.ScreenshotClipboardHook
import io.mo.xatype.hooks.TelemetryBlockHook
import io.mo.xatype.hooks.T9ShuangpinHook
import io.mo.xatype.hooks.VoiceModerationHook
import io.mo.xatype.util.XposedUtils
import io.mo.xatype.util.HookLifetime
import java.io.File

class XiaoAiTypeModule : XposedModule() {

    private var processCacheDir: File? = null
    private val packages = LinkedHashMap<Pair<String, ClassLoader>, LoadedPackage>()

    private data class LoadedPackage(
        val name: String,
        val loader: ClassLoader,
        val apk: String,
        val dataDir: String,
        val first: Boolean
    ) {
        // No objects defined in this module's class loader cross the reload boundary.
        fun snapshot(): Array<Any> = arrayOf(name, loader, apk, dataDir, first)
    }

    companion object {
        const val TARGET_PACKAGE = "com.xiaomi.type"
        const val PHRASE_PACKAGE = "com.miui.phrase"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }

    @Synchronized
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (HookLifetime.retired) return
        if (
            param.packageName != TARGET_PACKAGE &&
            param.packageName != PHRASE_PACKAGE &&
            param.packageName != SYSTEM_UI_PACKAGE
        ) return
        val loaded = LoadedPackage(param.packageName, param.defaultClassLoader, param.applicationInfo.sourceDir,
            param.applicationInfo.deviceProtectedDataDir, param.isFirstPackage)
        if (packages.putIfAbsent(loaded.name to loaded.loader, loaded) == null) install(loaded)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val state = synchronized(this) {
            arrayOf(
                packages.values.map(LoadedPackage::snapshot).toTypedArray(),
                ClipboardPermanentHook.pluginLoaders()
            )
        }
        param.setSavedInstanceState(state)
        return HookLifetime.retire(this)
    }

    @Synchronized
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val state = param.savedInstanceState as Array<*>
        val application = XposedUtils.currentApplication()
        if (application != null && packages.isEmpty()) {
            ConfigManager.init(this)
            ConfigManager.syncFromProvider(application)
        }
        for (snapshot in state[0] as Array<*>) {
            val entry = snapshot as Array<*>
            val loaded = LoadedPackage(entry[0] as String, entry[1] as ClassLoader,
                entry[2] as String, entry[3] as String, entry[4] as Boolean)
            packages[loaded.name to loaded.loader] = loaded
            install(loaded, application)
        }
        for (loader in state[1] as Array<*>) {
            ClipboardPermanentHook.reattachPlugin(this, loader as ClassLoader)
        }
        param.oldHookHandles.forEach { it.unhook() }
        XposedUtils.log(this, "Hot reload complete: ${BuildConfig.VERSION_NAME} in ${param.processName}")
    }

    private fun install(loaded: LoadedPackage, application: Application? = null) {
        ConfigManager.init(this)
        AdaptationReporter.install(this, application)
        if (loaded.first) {
            // The process's own storage; phrase classes also load inside the IME.
            processCacheDir = File(loaded.dataDir, "cache")
        }

        val classLoader = loaded.loader
        XposedUtils.log(this, "================================================")
        XposedUtils.log(this, "XiaoAiTypeUnblock initialized on libxposed API 102")
        XposedUtils.log(this, "Target: ${loaded.name} (FirstPackage=${loaded.first})")
        XposedUtils.log(this, "Framework: $frameworkName $frameworkVersion (API ${apiVersion})")
        if (loaded.name == TARGET_PACKAGE) {
            XposedUtils.log(
                this,
                "Target compatibility profile: " +
                    TargetCompatibility.detect(classLoader).name
            )
            initHostSymbols(loaded)
        }
        XposedUtils.log(this, "================================================")

        if (loaded.name == SYSTEM_UI_PACKAGE) {
            try {
                ScreenshotClipboardHook.install(this, application)
            } catch (t: Throwable) {
                XposedUtils.logError(this, "Error installing ScreenshotClipboardHook", t)
            }
            try {
                SystemUiNavigationGuardHook.install(this, classLoader)
            } catch (t: Throwable) {
                XposedUtils.logError(this, "Error installing SystemUiNavigationGuardHook", t)
            }
            return
        }

        try {
            if (loaded.name == PHRASE_PACKAGE) {
                ClipboardPermanentHook.install(
                    this,
                    classLoader,
                    loaded.apk,
                    processCacheDir
                )
            } else {
                ClipboardPermanentHook.install(this, classLoader, cacheDir = processCacheDir)
            }
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing ClipboardPermanentHook", t)
        }

        // com.miui.phrase owns persistence. The remaining hooks target only the
        // Xiaomi input method process.
        if (loaded.name == PHRASE_PACKAGE) return

        try {
            ClipboardSplitHook.install(this)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing ClipboardSplitHook", t)
        }

        try {
            KeyboardSelectionHook.install(this, classLoader)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_PICKER, t)
            XposedUtils.logError(this, "Independent keyboard picker unavailable for this host ABI", t)
        }

        try {
            ShapeInputHook.install(this, classLoader)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_ENGINE, t)
            XposedUtils.logError(this, "Shape input unavailable for this host ABI", t)
        }

        try {
            Keyboard18Hook.install(this, classLoader, application)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_LAYOUT, t)
            XposedUtils.logError(this, "18-key keyboard unavailable for this host ABI", t)
        }

        try {
            KeyboardPositionsHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Custom key positions unavailable for this host ABI", t)
        }

        try {
            KeyboardShortcutsHook.install(this, classLoader)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_SWIPE, t)
            XposedUtils.logError(this, "Letter shortcuts unavailable for this host ABI", t)
        }

        try {
            val gate = checkNotNull(HostSymbols.method(HostSymbols.ANTI_MISTOUCH_GATE))
            val callers = HostSymbols.methods(HostSymbols.ANTI_MISTOUCH_CALLERS)
            check(callers.isNotEmpty())
            callers.forEach { deoptimize(it) }
            hook(gate).intercept { chain ->
                if (ConfigManager.isAntiMistouchDisabled()) false else chain.proceed()
            }
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.ANTI_MISTOUCH_GATE, t)
            XposedUtils.logError(this, "Anti-mistouch gate unavailable for this host", t)
        }

        try {
            KeyboardHapticsHook.install(this, classLoader)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_HAPTICS, t)
            XposedUtils.logError(this, "Custom keyboard haptics unavailable for this host", t)
        }

        try {
            T9ShuangpinHook.install(this, classLoader)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.T9_SHUANGPIN_INIT, t)
            XposedUtils.logError(this, "Nine-key ShuangPin unavailable for this host ABI", t)
        }

        try {
            EmojiCandidateHook.install(this, classLoader)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_EMOJI, t)
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_PERSONAL_DICTIONARY, t)
            HostSymbols.markUnavailable(HostSymbols.KEYBOARD_PREDICTION, t)
            XposedUtils.logError(this, "Native candidate enhancements unavailable for this host ABI", t)
        }

        try {
            OnlineDictImportHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Online word list unavailable for this host", t)
        }

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
            AiAbilityUnlockHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing AiAbilityUnlockHook", t)
        }

        try {
            TelemetryBlockHook.install(this, classLoader)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing TelemetryBlockHook", t)
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
            OneHandHook.install(this)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.ONE_HAND_DIMENSIONS, t)
            XposedUtils.logError(this, "Native one-hand layout unavailable", t)
        }

        try {
            KeyboardToolbarMenuHook.install(this, classLoader)
        } catch (t: Throwable) {
            HostSymbols.markUnavailable(HostSymbols.ONE_HAND_MENU_GRID, t)
            XposedUtils.logError(this, "Keyboard toolbar menu entries unavailable for this host", t)
        }

        try {
            KeyboardBottomSpacingHook.install(this)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing KeyboardBottomSpacingHook", t)
        }

        try {
            KeyboardToolbarHeightHook.install(this)
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error installing KeyboardToolbarHeightHook", t)
        }

        AdaptationReporter.report(this, TARGET_PACKAGE, HostSymbols)
        XposedUtils.log(this, "XiaoAiTypeUnblock hooks installation complete.")
    }

    private fun initHostSymbols(loaded: LoadedPackage) {
        try {
            // Device-protected storage stays readable before the first unlock,
            // when the input method already runs.
            val cache = File(loaded.dataDir, "cache/xatype-symbols.json")
            val summary = HostSymbols.init(
                loaded.loader,
                loaded.apk,
                cache,
                XposedUtils.moduleStamp(this)
            )
            XposedUtils.log(this, "Host symbols: $summary")
        } catch (t: Throwable) {
            XposedUtils.logError(this, "Error resolving host symbols", t)
        }
    }
}
