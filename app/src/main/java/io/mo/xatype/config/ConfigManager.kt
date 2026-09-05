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
    const val KEY_CLIPBOARD_PERMANENT = "pref_clipboard_permanent"
    const val KEY_OS_VERSION_UNBLOCK = "pref_os_version_unblock"
    const val KEY_VERBOSE_LOG = "pref_verbose_log"

    // Style & Appearance Configs
    const val KEY_STYLE_ENABLED = "pref_style_enabled"
    const val KEY_CORNER_RADIUS = "pref_corner_radius"
    const val KEY_OPACITY = "pref_opacity" // 0 to 100
    const val KEY_BLUR_RADIUS = "pref_blur_radius" // 20 to 100
    const val KEY_BG_TYPE = "pref_bg_type" // 0: DYNAMIC_GLASS, 1: COLOR, 2: IMAGE
    const val KEY_BG_COLOR = "pref_bg_color"
    const val KEY_TEXT_COLOR = "pref_text_color" // Empty: automatic contrast, otherwise HEX color
    // Keep the original preference key so existing users retain the color they
    // selected before this setting was correctly identified as function-key-only.
    const val KEY_FUNCTION_KEYCAP_COLOR = "pref_keycap_color" // Empty: system color
    const val KEY_MENU_CARD_COLOR = "pref_menu_card_color" // Empty: system color
    const val KEY_LETTER_KEYCAP_COLOR = "pref_letter_keycap_color" // Empty: system color
    const val KEY_BG_IMAGE_VERSION = "pref_bg_image_version"

    private var remotePrefs: SharedPreferences? = null

    // In-memory cached synced values (Live synced from Provider)
    @Volatile private var cachedAiSafety = false
    @Volatile private var cachedVoiceModeration = false
    @Volatile private var cachedCloudBlacklist = false
    @Volatile private var cachedClipboardSensitive = false
    @Volatile private var cachedClipboardPermanent = false
    @Volatile private var cachedOsVersionUnblock = false
    @Volatile private var cachedStyleEnabled = false
    @Volatile private var cachedCornerRadius = 16
    @Volatile private var cachedOpacity = 85
    @Volatile private var cachedBlurRadius = 50
    @Volatile private var cachedBgType = 0
    @Volatile private var cachedBgColor = "#1E1E2E"
    @Volatile private var cachedTextColor = ""
    @Volatile private var cachedFunctionKeycapColor = ""
    @Volatile private var cachedMenuCardColor = ""
    @Volatile private var cachedLetterKeycapColor = ""
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
                cachedAiSafety = bundle.getBoolean(KEY_AI_SAFETY, false)
                cachedVoiceModeration = bundle.getBoolean(KEY_VOICE_MODERATION, false)
                cachedCloudBlacklist = bundle.getBoolean(KEY_CLOUD_BLACKLIST, false)
                cachedClipboardSensitive = bundle.getBoolean(KEY_CLIPBOARD_SENSITIVE, false)
                cachedClipboardPermanent = bundle.getBoolean(KEY_CLIPBOARD_PERMANENT, false)
                cachedOsVersionUnblock = bundle.getBoolean(KEY_OS_VERSION_UNBLOCK, false)
                cachedStyleEnabled = bundle.getBoolean(KEY_STYLE_ENABLED, false)
                cachedCornerRadius = bundle.getInt(KEY_CORNER_RADIUS, 16)
                cachedOpacity = bundle.getInt(KEY_OPACITY, 85)
                cachedBlurRadius = bundle.getInt(KEY_BLUR_RADIUS, 50)
                cachedBgType = bundle.getInt(KEY_BG_TYPE, 0)
                cachedBgColor = bundle.getString(KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E"
                cachedTextColor = bundle.getString(KEY_TEXT_COLOR, "") ?: ""
                cachedFunctionKeycapColor = bundle.getString(KEY_FUNCTION_KEYCAP_COLOR, "") ?: ""
                cachedMenuCardColor = bundle.getString(KEY_MENU_CARD_COLOR, "") ?: ""
                cachedLetterKeycapColor = bundle.getString(KEY_LETTER_KEYCAP_COLOR, "") ?: ""
                cachedBgImageVersion = bundle.getLong(KEY_BG_IMAGE_VERSION, 0L)
                hasSyncedFromProvider = true
            }
        } catch (_: Throwable) {
        }
    }

    fun isAiSafetyEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedAiSafety
        return remotePrefs?.getBoolean(KEY_AI_SAFETY, false) ?: cachedAiSafety
    }

    fun isVoiceModerationEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedVoiceModeration
        return remotePrefs?.getBoolean(KEY_VOICE_MODERATION, false) ?: cachedVoiceModeration
    }

    fun isCloudBlacklistEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedCloudBlacklist
        return remotePrefs?.getBoolean(KEY_CLOUD_BLACKLIST, false) ?: cachedCloudBlacklist
    }

    fun isClipboardSensitiveEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedClipboardSensitive
        return remotePrefs?.getBoolean(KEY_CLIPBOARD_SENSITIVE, false) ?: cachedClipboardSensitive
    }

    fun isClipboardPermanentEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedClipboardPermanent
        return remotePrefs?.getBoolean(KEY_CLIPBOARD_PERMANENT, false) ?: cachedClipboardPermanent
    }

    fun isOsVersionUnblockEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedOsVersionUnblock
        return remotePrefs?.getBoolean(KEY_OS_VERSION_UNBLOCK, false) ?: cachedOsVersionUnblock
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

    fun getTextColor(): String {
        if (hasSyncedFromProvider) return cachedTextColor
        return remotePrefs?.getString(KEY_TEXT_COLOR, cachedTextColor) ?: cachedTextColor
    }

    fun getFunctionKeycapColor(): String {
        if (hasSyncedFromProvider) return cachedFunctionKeycapColor
        return remotePrefs?.getString(KEY_FUNCTION_KEYCAP_COLOR, cachedFunctionKeycapColor)
            ?: cachedFunctionKeycapColor
    }

    fun getMenuCardColor(): String {
        if (hasSyncedFromProvider) return cachedMenuCardColor
        return remotePrefs?.getString(KEY_MENU_CARD_COLOR, cachedMenuCardColor)
            ?: cachedMenuCardColor
    }

    fun getLetterKeycapColor(): String {
        if (hasSyncedFromProvider) return cachedLetterKeycapColor
        return remotePrefs?.getString(KEY_LETTER_KEYCAP_COLOR, cachedLetterKeycapColor)
            ?: cachedLetterKeycapColor
    }

    fun getBgImageVersion(): Long {
        if (hasSyncedFromProvider) return cachedBgImageVersion
        return remotePrefs?.getLong(KEY_BG_IMAGE_VERSION, cachedBgImageVersion) ?: cachedBgImageVersion
    }

    fun isVerboseLogEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_VERBOSE_LOG, false) ?: false
    }

    fun getLocalPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
