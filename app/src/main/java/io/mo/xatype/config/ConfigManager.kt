package io.mo.xatype.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

object ConfigManager {
    const val PREFS_NAME = "settings"

    const val KEY_AI_SAFETY = "pref_ai_safety"
    const val KEY_VOICE_MODERATION = "pref_voice_moderation"
    const val KEY_CLOUD_BLACKLIST = "pref_cloud_blacklist"
    const val KEY_CLIPBOARD_SENSITIVE = "pref_clipboard_sensitive"
    const val KEY_OS_VERSION_UNBLOCK = "pref_os_version_unblock"
    const val KEY_VERBOSE_LOG = "pref_verbose_log"

    private var remotePrefs: SharedPreferences? = null

    fun initRemote(module: XposedInterface) {
        try {
            remotePrefs = module.getRemotePreferences(PREFS_NAME)
        } catch (_: Throwable) {
            remotePrefs = null
        }
    }

    fun isAiSafetyEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_AI_SAFETY, true) ?: true
    }

    fun isVoiceModerationEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_VOICE_MODERATION, true) ?: true
    }

    fun isCloudBlacklistEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_CLOUD_BLACKLIST, true) ?: true
    }

    fun isClipboardSensitiveEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_CLIPBOARD_SENSITIVE, true) ?: true
    }

    fun isOsVersionUnblockEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_OS_VERSION_UNBLOCK, true) ?: true
    }

    fun isVerboseLogEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_VERBOSE_LOG, true) ?: true
    }

    fun getLocalPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
