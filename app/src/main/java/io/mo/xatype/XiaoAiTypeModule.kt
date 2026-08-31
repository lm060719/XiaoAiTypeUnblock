package io.mo.xatype

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.data.LogType
import io.mo.xatype.hooks.AiSafetyHook
import io.mo.xatype.hooks.ClipboardSensitiveHook
import io.mo.xatype.hooks.CloudBlacklistHook
import io.mo.xatype.hooks.HyperOsVersionHook
import io.mo.xatype.hooks.VoiceModerationHook
import io.mo.xatype.util.LogBridge
import io.mo.xatype.util.XposedUtils

class XiaoAiTypeModule : XposedModule() {

    companion object {
        const val TARGET_PACKAGE = "com.xiaomi.type"
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (param.packageName != TARGET_PACKAGE) return

        // Initialize remote preferences
        ConfigManager.initRemote(this)

        val classLoader = param.defaultClassLoader
        XposedUtils.log(this, "================================================")
        XposedUtils.log(this, "XiaoAiTypeUnblock initialized on libxposed API 102")
        XposedUtils.log(this, "Target: ${param.packageName} (FirstPackage=${param.isFirstPackage})")
        XposedUtils.log(this, "Framework: $frameworkName $frameworkVersion (API ${apiVersion})")
        XposedUtils.log(this, "================================================")

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

        XposedUtils.log(this, "XiaoAiTypeUnblock hooks installation complete.")
        LogBridge.record(
            LogType.INIT,
            "模块初始化成功",
            "已注入超级小爱输入法进程 (PID: ${android.os.Process.myPid()})"
        )
    }
}
