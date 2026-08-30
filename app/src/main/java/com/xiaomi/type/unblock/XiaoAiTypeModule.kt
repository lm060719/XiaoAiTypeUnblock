package com.xiaomi.type.unblock

import com.xiaomi.type.unblock.config.ConfigManager
import com.xiaomi.type.unblock.data.LogType
import com.xiaomi.type.unblock.hooks.AiSafetyHook
import com.xiaomi.type.unblock.hooks.ClipboardSensitiveHook
import com.xiaomi.type.unblock.hooks.CloudBlacklistHook
import com.xiaomi.type.unblock.hooks.VoiceModerationHook
import com.xiaomi.type.unblock.util.LogBridge
import com.xiaomi.type.unblock.util.XposedUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

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
