package io.mo.xatype.config

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.github.libxposed.api.XposedInterface

object ConfigManager {
    const val PREFS_NAME = "settings"

    const val KEY_AI_SAFETY = "pref_ai_safety"
    const val KEY_VOICE_MODERATION = "pref_voice_moderation"
    const val KEY_CLOUD_BLACKLIST = "pref_cloud_blacklist"
    const val KEY_CLIPBOARD_SENSITIVE = "pref_clipboard_sensitive"
    const val KEY_OS_VERSION_UNBLOCK = "pref_os_version_unblock"
    const val KEY_VERBOSE_LOG = "pref_verbose_log"

    // Style & Appearance Configs
    const val KEY_STYLE_ENABLED = "pref_style_enabled"
    const val KEY_CORNER_RADIUS = "pref_corner_radius"
    const val KEY_OPACITY = "pref_opacity" // 10 to 100
    const val KEY_BLUR_RADIUS = "pref_blur_radius" // 20 to 100
    const val KEY_BG_TYPE = "pref_bg_type" // 0: DYNAMIC_GLASS, 1: COLOR, 2: IMAGE
    const val KEY_BG_COLOR = "pref_bg_color"
    const val KEY_MARGIN_TOP = "pref_margin_top"
    const val KEY_MARGIN_BOTTOM = "pref_margin_bottom"
    const val KEY_MARGIN_HORIZONTAL = "pref_margin_horizontal"
    const val KEY_BG_IMAGE_VERSION = "pref_bg_image_version"

    private var remotePrefs: SharedPreferences? = null

    // In-memory cached synced values (Live synced from Provider)
    @Volatile private var cachedAiSafety = true
    @Volatile private var cachedVoiceModeration = true
    @Volatile private var cachedCloudBlacklist = true
    @Volatile private var cachedClipboardSensitive = true
    @Volatile private var cachedOsVersionUnblock = true
    @Volatile private var cachedStyleEnabled = true
    @Volatile private var cachedCornerRadius = 16
    @Volatile private var cachedOpacity = 85
    @Volatile private var cachedBlurRadius = 50
    @Volatile private var cachedBgType = 0
    @Volatile private var cachedBgColor = "#1E1E2E"
    @Volatile private var cachedMarginTop = 0
    @Volatile private var cachedMarginBottom = 0
    @Volatile private var cachedMarginHorizontal = 0
    @Volatile private var cachedBgImageVersion = 0L
    @Volatile private var hasSyncedFromProvider = false

    fun initRemote(module: XposedInterface) {
        try {
            remotePrefs = module.getRemotePreferences(PREFS_NAME)
        } catch (_: Throwable) {
            remotePrefs = null
        }
    }

    fun syncFromProvider(context: Context) {
        try {
            val uri = Uri.parse("content://io.mo.xatype.logprovider")
            val bundle = context.contentResolver.call(uri, "get_config", null, null)
            if (bundle != null) {
                cachedAiSafety = bundle.getBoolean(KEY_AI_SAFETY, true)
                cachedVoiceModeration = bundle.getBoolean(KEY_VOICE_MODERATION, true)
                cachedCloudBlacklist = bundle.getBoolean(KEY_CLOUD_BLACKLIST, true)
                cachedClipboardSensitive = bundle.getBoolean(KEY_CLIPBOARD_SENSITIVE, true)
                cachedOsVersionUnblock = bundle.getBoolean(KEY_OS_VERSION_UNBLOCK, true)
                cachedStyleEnabled = bundle.getBoolean(KEY_STYLE_ENABLED, true)
                cachedCornerRadius = bundle.getInt(KEY_CORNER_RADIUS, 16)
                cachedOpacity = bundle.getInt(KEY_OPACITY, 85)
                cachedBlurRadius = bundle.getInt(KEY_BLUR_RADIUS, 50)
                cachedBgType = bundle.getInt(KEY_BG_TYPE, 0)
                cachedBgColor = bundle.getString(KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E"
                cachedMarginTop = bundle.getInt(KEY_MARGIN_TOP, 0)
                cachedMarginBottom = bundle.getInt(KEY_MARGIN_BOTTOM, 0)
                cachedMarginHorizontal = bundle.getInt(KEY_MARGIN_HORIZONTAL, 0)
                cachedBgImageVersion = bundle.getLong(KEY_BG_IMAGE_VERSION, 0L)
                hasSyncedFromProvider = true
            }
        } catch (_: Throwable) {
        }
    }

    fun isAiSafetyEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedAiSafety
        return remotePrefs?.getBoolean(KEY_AI_SAFETY, true) ?: cachedAiSafety
    }

    fun isVoiceModerationEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedVoiceModeration
        return remotePrefs?.getBoolean(KEY_VOICE_MODERATION, true) ?: cachedVoiceModeration
    }

    fun isCloudBlacklistEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedCloudBlacklist
        return remotePrefs?.getBoolean(KEY_CLOUD_BLACKLIST, true) ?: cachedCloudBlacklist
    }

    fun isClipboardSensitiveEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedClipboardSensitive
        return remotePrefs?.getBoolean(KEY_CLIPBOARD_SENSITIVE, true) ?: cachedClipboardSensitive
    }

    fun isOsVersionUnblockEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedOsVersionUnblock
        return remotePrefs?.getBoolean(KEY_OS_VERSION_UNBLOCK, true) ?: cachedOsVersionUnblock
    }

    fun isStyleEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedStyleEnabled
        return remotePrefs?.getBoolean(KEY_STYLE_ENABLED, cachedStyleEnabled) ?: cachedStyleEnabled
    }

    fun getCornerRadius(): Int {
        if (hasSyncedFromProvider) return cachedCornerRadius
        return remotePrefs?.getInt(KEY_CORNER_RADIUS, cachedCornerRadius) ?: cachedCornerRadius
    }

    fun getOpacity(): Int {
        if (hasSyncedFromProvider) return cachedOpacity
        return remotePrefs?.getInt(KEY_OPACITY, cachedOpacity) ?: cachedOpacity
    }

    fun getBlurRadius(): Int {
        if (hasSyncedFromProvider) return cachedBlurRadius
        return remotePrefs?.getInt(KEY_BLUR_RADIUS, cachedBlurRadius) ?: cachedBlurRadius
    }

    fun getBgType(): Int {
        if (hasSyncedFromProvider) return cachedBgType
        return remotePrefs?.getInt(KEY_BG_TYPE, cachedBgType) ?: cachedBgType
    }

    fun getBgColor(): String {
        if (hasSyncedFromProvider) return cachedBgColor
        return remotePrefs?.getString(KEY_BG_COLOR, cachedBgColor) ?: cachedBgColor
    }

    fun getMarginTop(): Int {
        if (hasSyncedFromProvider) return cachedMarginTop
        return remotePrefs?.getInt(KEY_MARGIN_TOP, cachedMarginTop) ?: cachedMarginTop
    }

    fun getMarginBottom(): Int {
        if (hasSyncedFromProvider) return cachedMarginBottom
        return remotePrefs?.getInt(KEY_MARGIN_BOTTOM, cachedMarginBottom) ?: cachedMarginBottom
    }

    fun getMarginHorizontal(): Int {
        if (hasSyncedFromProvider) return cachedMarginHorizontal
        return remotePrefs?.getInt(KEY_MARGIN_HORIZONTAL, cachedMarginHorizontal) ?: cachedMarginHorizontal
    }

    fun getBgImageVersion(): Long {
        if (hasSyncedFromProvider) return cachedBgImageVersion
        return remotePrefs?.getLong(KEY_BG_IMAGE_VERSION, cachedBgImageVersion) ?: cachedBgImageVersion
    }

    fun isVerboseLogEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_VERBOSE_LOG, true) ?: true
    }

    fun getLocalPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
