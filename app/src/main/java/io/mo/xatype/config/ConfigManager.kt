package io.mo.xatype.config

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
import io.github.libxposed.api.XposedInterface

object ConfigManager {
    const val PREFS_NAME = "settings"

    const val KEY_AI_SAFETY = "pref_ai_safety"
    const val KEY_VOICE_MODERATION = "pref_voice_moderation"
    const val KEY_CLOUD_BLACKLIST = "pref_cloud_blacklist"
    const val KEY_CLIPBOARD_SENSITIVE = "pref_clipboard_sensitive"
    const val KEY_CLIPBOARD_PERMANENT = "pref_clipboard_permanent"
    const val KEY_OS_VERSION_UNBLOCK = "pref_os_version_unblock"
    const val KEY_KEYBOARD_HEIGHT_UNBLOCK = "pref_keyboard_height_unblock"
    const val KEY_BOTTOM_SPACING_ENABLED = "pref_bottom_spacing_enabled"
    const val KEY_BOTTOM_SPACING = "pref_bottom_spacing" // 0 to 100 dp; global geometry
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
    const val KEY_CLIPBOARD_CARD_COLOR = "pref_clipboard_card_color" // Empty: system color
    const val KEY_LETTER_KEYCAP_COLOR = "pref_letter_keycap_color" // Empty: system color
    const val KEY_FUNCTION_KEYCAP_OPACITY = "pref_function_keycap_opacity" // 0 to 100
    const val KEY_MENU_CARD_OPACITY = "pref_menu_card_opacity" // 0 to 100
    const val KEY_CLIPBOARD_CARD_OPACITY = "pref_clipboard_card_opacity" // 0 to 100
    const val KEY_LETTER_KEYCAP_OPACITY = "pref_letter_keycap_opacity" // 0 to 100
    const val KEY_BG_IMAGE_VERSION = "pref_bg_image_version"

    private var remotePrefs: SharedPreferences? = null
    @Volatile private var remoteStylePrefs: SharedPreferences? = null

    // In-memory cached synced values (Live synced from Provider)
    @Volatile private var cachedAiSafety = false
    @Volatile private var cachedVoiceModeration = false
    @Volatile private var cachedCloudBlacklist = false
    @Volatile private var cachedClipboardSensitive = false
    @Volatile private var cachedClipboardPermanent = false
    @Volatile private var cachedOsVersionUnblock = false
    @Volatile private var cachedKeyboardHeightUnblock = false
    @Volatile private var cachedBottomSpacingEnabled = false
    @Volatile private var cachedBottomSpacing = 0
    @Volatile private var cachedVerboseLog = false
    @Volatile private var cachedStyleEnabled = false
    @Volatile private var cachedCornerRadius = 16
    @Volatile private var cachedOpacity = 85
    @Volatile private var cachedBlurRadius = 50
    @Volatile private var cachedBgType = 0
    @Volatile private var cachedBgColor = "#1E1E2E"
    @Volatile private var cachedTextColor = ""
    @Volatile private var cachedFunctionKeycapColor = ""
    @Volatile private var cachedMenuCardColor = ""
    @Volatile private var cachedClipboardCardColor = ""
    @Volatile private var cachedLetterKeycapColor = ""
    @Volatile private var cachedFunctionKeycapOpacity = 100
    @Volatile private var cachedMenuCardOpacity = 100
    @Volatile private var cachedClipboardCardOpacity = 100
    @Volatile private var cachedLetterKeycapOpacity = 100
    @Volatile private var cachedBgImageVersion = 0L
    @Volatile private var hasSyncedFromProvider = false

    fun initRemote(module: XposedInterface) {
        try {
            remotePrefs = module.getRemotePreferences(PREFS_NAME)
            remoteStylePrefs = remotePrefs?.let { AppearanceProfiles.selected(it, false) }
        } catch (_: Throwable) {
            remotePrefs = null
            remoteStylePrefs = null
        }
    }

    fun syncFromProvider(context: Context) {
        try {
            val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            remoteStylePrefs = remotePrefs?.let { AppearanceProfiles.selected(it, systemDark) }
            val uri = Uri.parse("content://io.mo.xatype.logprovider")
            val extras = Bundle().apply {
                putBoolean(AppearanceProfiles.EXTRA_DARK, systemDark)
            }
            val bundle = context.contentResolver.call(uri, "get_config", null, extras)
            if (bundle != null) {
                cachedAiSafety = bundle.getBoolean(KEY_AI_SAFETY, false)
                cachedVoiceModeration = bundle.getBoolean(KEY_VOICE_MODERATION, false)
                cachedCloudBlacklist = bundle.getBoolean(KEY_CLOUD_BLACKLIST, false)
                cachedClipboardSensitive = bundle.getBoolean(KEY_CLIPBOARD_SENSITIVE, false)
                cachedClipboardPermanent = bundle.getBoolean(KEY_CLIPBOARD_PERMANENT, false)
                cachedOsVersionUnblock = bundle.getBoolean(KEY_OS_VERSION_UNBLOCK, false)
                cachedKeyboardHeightUnblock = bundle.getBoolean(KEY_KEYBOARD_HEIGHT_UNBLOCK, false)
                cachedBottomSpacingEnabled = bundle.getBoolean(KEY_BOTTOM_SPACING_ENABLED, false)
                cachedBottomSpacing = bundle.getInt(KEY_BOTTOM_SPACING, 0).coerceIn(0, 100)
                cachedVerboseLog = bundle.getBoolean(KEY_VERBOSE_LOG, false)
                cachedStyleEnabled = bundle.getBoolean(KEY_STYLE_ENABLED, false)
                cachedCornerRadius = bundle.getInt(KEY_CORNER_RADIUS, 16)
                cachedOpacity = bundle.getInt(KEY_OPACITY, 85)
                cachedBlurRadius = bundle.getInt(KEY_BLUR_RADIUS, 50)
                cachedBgType = bundle.getInt(KEY_BG_TYPE, 0)
                cachedBgColor = bundle.getString(KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E"
                cachedTextColor = bundle.getString(KEY_TEXT_COLOR, "") ?: ""
                cachedFunctionKeycapColor = bundle.getString(KEY_FUNCTION_KEYCAP_COLOR, "") ?: ""
                cachedMenuCardColor = bundle.getString(KEY_MENU_CARD_COLOR, "") ?: ""
                cachedClipboardCardColor = bundle.getString(
                    KEY_CLIPBOARD_CARD_COLOR,
                    cachedMenuCardColor
                ) ?: cachedMenuCardColor
                cachedLetterKeycapColor = bundle.getString(KEY_LETTER_KEYCAP_COLOR, "") ?: ""
                cachedFunctionKeycapOpacity = bundle.getInt(KEY_FUNCTION_KEYCAP_OPACITY, 100).coerceIn(0, 100)
                cachedMenuCardOpacity = bundle.getInt(KEY_MENU_CARD_OPACITY, 100).coerceIn(0, 100)
                cachedClipboardCardOpacity = bundle.getInt(
                    KEY_CLIPBOARD_CARD_OPACITY,
                    cachedMenuCardOpacity
                ).coerceIn(0, 100)
                cachedLetterKeycapOpacity = bundle.getInt(KEY_LETTER_KEYCAP_OPACITY, 100).coerceIn(0, 100)
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
        return remoteStylePrefs?.getBoolean(KEY_STYLE_ENABLED, cachedStyleEnabled) ?: cachedStyleEnabled
    }

    fun isKeyboardHeightUnblockEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedKeyboardHeightUnblock
        return remotePrefs?.getBoolean(KEY_KEYBOARD_HEIGHT_UNBLOCK, false) ?: cachedKeyboardHeightUnblock
    }

    fun getCornerRadius(): Int {
        if (hasSyncedFromProvider) return cachedCornerRadius
        return remoteStylePrefs?.getInt(KEY_CORNER_RADIUS, cachedCornerRadius) ?: cachedCornerRadius
    }

    fun isBottomSpacingEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedBottomSpacingEnabled
        return remotePrefs?.getBoolean(KEY_BOTTOM_SPACING_ENABLED, false) ?: cachedBottomSpacingEnabled
    }

    fun getBottomSpacing(): Int {
        if (hasSyncedFromProvider) return cachedBottomSpacing
        return (remotePrefs?.getInt(KEY_BOTTOM_SPACING, 0) ?: cachedBottomSpacing).coerceIn(0, 100)
    }

    fun getOpacity(): Int {
        if (hasSyncedFromProvider) return cachedOpacity
        return remoteStylePrefs?.getInt(KEY_OPACITY, cachedOpacity) ?: cachedOpacity
    }

    fun getBlurRadius(): Int {
        if (hasSyncedFromProvider) return cachedBlurRadius
        return remoteStylePrefs?.getInt(KEY_BLUR_RADIUS, cachedBlurRadius) ?: cachedBlurRadius
    }

    fun getBgType(): Int {
        if (hasSyncedFromProvider) return cachedBgType
        return remoteStylePrefs?.getInt(KEY_BG_TYPE, cachedBgType) ?: cachedBgType
    }

    fun getBgColor(): String {
        if (hasSyncedFromProvider) return cachedBgColor
        return remoteStylePrefs?.getString(KEY_BG_COLOR, cachedBgColor) ?: cachedBgColor
    }

    fun getTextColor(): String {
        if (hasSyncedFromProvider) return cachedTextColor
        return remoteStylePrefs?.getString(KEY_TEXT_COLOR, cachedTextColor) ?: cachedTextColor
    }

    fun getFunctionKeycapColor(): String {
        if (hasSyncedFromProvider) return cachedFunctionKeycapColor
        return remoteStylePrefs?.getString(KEY_FUNCTION_KEYCAP_COLOR, cachedFunctionKeycapColor)
            ?: cachedFunctionKeycapColor
    }

    fun getMenuCardColor(): String {
        if (hasSyncedFromProvider) return cachedMenuCardColor
        return remoteStylePrefs?.getString(KEY_MENU_CARD_COLOR, cachedMenuCardColor)
            ?: cachedMenuCardColor
    }

    fun getClipboardCardColor(): String {
        if (hasSyncedFromProvider) return cachedClipboardCardColor

        val prefs = remoteStylePrefs
        return if (prefs != null && prefs.contains(KEY_CLIPBOARD_CARD_COLOR)) {
            prefs.getString(KEY_CLIPBOARD_CARD_COLOR, cachedClipboardCardColor)
                ?: cachedClipboardCardColor
        } else {
            prefs?.getString(KEY_MENU_CARD_COLOR, cachedMenuCardColor)
                ?: cachedClipboardCardColor
        }
    }

    fun getLetterKeycapColor(): String {
        if (hasSyncedFromProvider) return cachedLetterKeycapColor
        return remoteStylePrefs?.getString(KEY_LETTER_KEYCAP_COLOR, cachedLetterKeycapColor)
            ?: cachedLetterKeycapColor
    }

    fun getFunctionKeycapOpacity(): Int {
        if (hasSyncedFromProvider) return cachedFunctionKeycapOpacity
        return (remoteStylePrefs?.getInt(KEY_FUNCTION_KEYCAP_OPACITY, cachedFunctionKeycapOpacity)
            ?: cachedFunctionKeycapOpacity).coerceIn(0, 100)
    }

    fun getMenuCardOpacity(): Int {
        if (hasSyncedFromProvider) return cachedMenuCardOpacity
        return (remoteStylePrefs?.getInt(KEY_MENU_CARD_OPACITY, cachedMenuCardOpacity)
            ?: cachedMenuCardOpacity).coerceIn(0, 100)
    }

    fun getClipboardCardOpacity(): Int {
        if (hasSyncedFromProvider) return cachedClipboardCardOpacity

        val prefs = remoteStylePrefs
        val value = if (prefs != null && prefs.contains(KEY_CLIPBOARD_CARD_OPACITY)) {
            prefs.getInt(KEY_CLIPBOARD_CARD_OPACITY, cachedClipboardCardOpacity)
        } else {
            prefs?.getInt(KEY_MENU_CARD_OPACITY, cachedMenuCardOpacity)
                ?: cachedClipboardCardOpacity
        }
        return value.coerceIn(0, 100)
    }

    fun getLetterKeycapOpacity(): Int {
        if (hasSyncedFromProvider) return cachedLetterKeycapOpacity
        return (remoteStylePrefs?.getInt(KEY_LETTER_KEYCAP_OPACITY, cachedLetterKeycapOpacity)
            ?: cachedLetterKeycapOpacity).coerceIn(0, 100)
    }

    fun getBgImageVersion(): Long {
        if (hasSyncedFromProvider) return cachedBgImageVersion
        return remoteStylePrefs?.getLong(KEY_BG_IMAGE_VERSION, cachedBgImageVersion) ?: cachedBgImageVersion
    }

    fun isVerboseLogEnabled(): Boolean {
        if (hasSyncedFromProvider) return cachedVerboseLog
        return remotePrefs?.getBoolean(KEY_VERBOSE_LOG, cachedVerboseLog) ?: cachedVerboseLog
    }

    fun getLocalPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
