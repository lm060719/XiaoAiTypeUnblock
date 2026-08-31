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
    const val KEY_CORNER_MODE = "pref_corner_mode" // 0: TOP_ONLY, 1: ALL_CORNERS
    const val KEY_OPACITY = "pref_opacity" // 10 to 100
    const val KEY_BG_TYPE = "pref_bg_type" // 0: DEFAULT, 1: COLOR, 2: IMAGE
    const val KEY_BG_COLOR = "pref_bg_color"
    const val KEY_MARGIN_TOP = "pref_margin_top"
    const val KEY_MARGIN_BOTTOM = "pref_margin_bottom"
    const val KEY_MARGIN_HORIZONTAL = "pref_margin_horizontal"
    const val KEY_BG_IMAGE_VERSION = "pref_bg_image_version"

    private var remotePrefs: SharedPreferences? = null

    // In-memory cached synced values
    @Volatile private var cachedStyleEnabled = true
    @Volatile private var cachedCornerRadius = 16
    @Volatile private var cachedCornerMode = 0
    @Volatile private var cachedOpacity = 100
    @Volatile private var cachedBgType = 0
    @Volatile private var cachedBgColor = "#1E1E2E"
    @Volatile private var cachedMarginTop = 0
    @Volatile private var cachedMarginBottom = 0
    @Volatile private var cachedMarginHorizontal = 0
    @Volatile private var cachedBgImageVersion = 0L
    @Volatile private var lastSyncTime = 0L

    fun initRemote(module: XposedInterface) {
        try {
            remotePrefs = module.getRemotePreferences(PREFS_NAME)
        } catch (_: Throwable) {
            remotePrefs = null
        }
    }

    fun syncFromProvider(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastSyncTime < 800) return // Throttle
        lastSyncTime = now
        try {
            val uri = Uri.parse("content://io.mo.xatype.logprovider")
            val bundle = context.contentResolver.call(uri, "get_config", null, null)
            if (bundle != null) {
                cachedStyleEnabled = bundle.getBoolean(KEY_STYLE_ENABLED, true)
                cachedCornerRadius = bundle.getInt(KEY_CORNER_RADIUS, 16)
                cachedCornerMode = bundle.getInt(KEY_CORNER_MODE, 0)
                cachedOpacity = bundle.getInt(KEY_OPACITY, 100)
                cachedBgType = bundle.getInt(KEY_BG_TYPE, 0)
                cachedBgColor = bundle.getString(KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E"
                cachedMarginTop = bundle.getInt(KEY_MARGIN_TOP, 0)
                cachedMarginBottom = bundle.getInt(KEY_MARGIN_BOTTOM, 0)
                cachedMarginHorizontal = bundle.getInt(KEY_MARGIN_HORIZONTAL, 0)
                cachedBgImageVersion = bundle.getLong(KEY_BG_IMAGE_VERSION, 0L)
            }
        } catch (_: Throwable) {
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

    fun isStyleEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_STYLE_ENABLED, cachedStyleEnabled) ?: cachedStyleEnabled
    }

    fun getCornerRadius(): Int {
        return remotePrefs?.getInt(KEY_CORNER_RADIUS, cachedCornerRadius) ?: cachedCornerRadius
    }

    fun getCornerMode(): Int {
        return remotePrefs?.getInt(KEY_CORNER_MODE, cachedCornerMode) ?: cachedCornerMode
    }

    fun getOpacity(): Int {
        return remotePrefs?.getInt(KEY_OPACITY, cachedOpacity) ?: cachedOpacity
    }

    fun getBgType(): Int {
        return remotePrefs?.getInt(KEY_BG_TYPE, cachedBgType) ?: cachedBgType
    }

    fun getBgColor(): String {
        return remotePrefs?.getString(KEY_BG_COLOR, cachedBgColor) ?: cachedBgColor
    }

    fun getMarginTop(): Int {
        return remotePrefs?.getInt(KEY_MARGIN_TOP, cachedMarginTop) ?: cachedMarginTop
    }

    fun getMarginBottom(): Int {
        return remotePrefs?.getInt(KEY_MARGIN_BOTTOM, cachedMarginBottom) ?: cachedMarginBottom
    }

    fun getMarginHorizontal(): Int {
        return remotePrefs?.getInt(KEY_MARGIN_HORIZONTAL, cachedMarginHorizontal) ?: cachedMarginHorizontal
    }

    fun getBgImageVersion(): Long {
        return remotePrefs?.getLong(KEY_BG_IMAGE_VERSION, cachedBgImageVersion) ?: cachedBgImageVersion
    }

    fun isVerboseLogEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_VERBOSE_LOG, true) ?: true
    }

    fun getLocalPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
