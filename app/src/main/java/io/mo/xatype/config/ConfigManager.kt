package io.mo.xatype.config

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import io.github.libxposed.api.XposedInterface
import io.mo.xatype.keyboard.PredictionMode
import io.mo.xatype.keyboard.ShortcutKeyboard
import io.mo.xatype.util.XposedUtils

/**
 * Settings as seen by the hooked processes.
 *
 * Values come from the settings app through [io.mo.xatype.provider.LogContentProvider]
 * and are cached here. Types, defaults and ranges are declared in [ConfigSchema].
 */
object ConfigManager {
    const val PREFS_NAME = "settings"

    const val KEY_AI_SAFETY = "pref_ai_safety"
    const val KEY_VOICE_MODERATION = "pref_voice_moderation"
    const val KEY_CLOUD_BLACKLIST = "pref_cloud_blacklist"
    const val KEY_CLIPBOARD_SENSITIVE = "pref_clipboard_sensitive"
    const val KEY_CLIPBOARD_PERMANENT = "pref_clipboard_permanent"
    const val KEY_SCREENSHOT_CLIPBOARD = "pref_screenshot_clipboard"
    val SCREENSHOT_CONFIG_URI: Uri = Uri.parse("content://io.mo.xatype.logprovider/screenshot_config")
    const val KEY_OS_VERSION_UNBLOCK = "pref_os_version_unblock"
    const val KEY_AI_ABILITY_UNLOCK = "pref_ai_ability_unlock"
    const val KEY_TELEMETRY_BLOCK = "pref_telemetry_block"
    const val KEY_KEYBOARD_HEIGHT_UNBLOCK = "pref_keyboard_height_unblock"
    const val KEY_KEYBOARD_SHORTCUTS = "pref_keyboard_shortcuts"
    const val KEY_KEYBOARD_HANDWRITING = "pref_keyboard_handwriting"
    /** dp an upward first stroke travels before handwriting claims it; shorter swipes stay the host's symbols. */
    const val KEY_HANDWRITING_UP_DISTANCE = "pref_handwriting_up_distance"
    const val HANDWRITING_UP_DISTANCE_DEFAULT = 150
    val HANDWRITING_UP_DISTANCE_RANGE get() = ConfigSchema.HANDWRITING_UP_DISTANCE_RANGE
    /** dp a leftward or rightward first stroke travels before handwriting claims it. */
    const val KEY_HANDWRITING_SIDE_DISTANCE = "pref_handwriting_side_distance"
    const val HANDWRITING_SIDE_DISTANCE_DEFAULT = 100
    val HANDWRITING_SIDE_DISTANCE_RANGE get() = ConfigSchema.HANDWRITING_SIDE_DISTANCE_RANGE
    const val KEY_T9_SHUANGPIN = "pref_t9_shuangpin"
    const val KEY_EMOJI_CANDIDATES = "pref_emoji_candidates"
    const val KEY_PREDICTION_MODE = "pref_prediction_mode"
    const val KEY_T9_SHUANGPIN_SCHEME = "pref_t9_shuangpin_scheme"
    const val KEY_BOTTOM_SPACING_ENABLED = "pref_bottom_spacing_enabled"
    const val KEY_BOTTOM_SPACING = "pref_bottom_spacing" // 0 to 100 dp; global geometry
    // Action text drawn on keys that carry a downward-swipe shortcut.
    const val KEY_SHORTCUT_LABELS = "pref_shortcut_labels"
    const val KEY_SHORTCUT_LABEL_SIZE = "pref_shortcut_label_size" // sp, 8 to 20
    const val KEY_SHORTCUT_LABEL_X = "pref_shortcut_label_x" // dp offset, -40 to 40
    const val KEY_SHORTCUT_LABEL_Y = "pref_shortcut_label_y" // dp offset, -40 to 40
    const val KEY_VERBOSE_LOG = "pref_verbose_log"
    const val KEY_SPLASH_ANIMATION = "pref_splash_animation" // Settings app only; defaults to on

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
    const val KEY_KEY_POPUP_BACKGROUND = "pref_key_popup_background"
    const val KEY_KEY_POPUP_SELECTED = "pref_key_popup_selected"
    const val KEY_KEY_POPUP_TEXT = "pref_key_popup_text"
    const val KEY_FUNCTION_KEYCAP_OPACITY = "pref_function_keycap_opacity" // 0 to 100
    const val KEY_MENU_CARD_OPACITY = "pref_menu_card_opacity" // 0 to 100
    const val KEY_CLIPBOARD_CARD_OPACITY = "pref_clipboard_card_opacity" // 0 to 100
    const val KEY_LETTER_KEYCAP_OPACITY = "pref_letter_keycap_opacity" // 0 to 100
    const val KEY_BG_IMAGE_VERSION = "pref_bg_image_version"
    // Readability scrim: one switch for both profiles, color and opacity per profile.
    const val KEY_SCRIM_ENABLED = "pref_scrim_enabled"
    const val KEY_SCRIM_COLOR = "pref_scrim_color" // Empty: contrast with the label color
    const val KEY_SCRIM_OPACITY = "pref_scrim_opacity" // 0 to 100
    const val DEFAULT_SCRIM_OPACITY = 35
    // Specular rim along the keyboard card edge, per profile. 0 turns it off.
    const val KEY_RIM_HIGHLIGHT = "pref_rim_highlight" // 0 to 100
    const val KEY_RIM_LIGHT = "pref_rim_light" // Light direction: 0 upper left, 1 top, 2 upper right

    /** The settings app notifies this URI after every write to the settings file. */
    val CONFIG_URI: Uri = Uri.parse("content://io.mo.xatype.logprovider/config")
    private val PROVIDER_URI: Uri = Uri.parse("content://io.mo.xatype.logprovider")
    private const val METHOD_GET_CONFIG = "get_config"
    /** Slider drags write many times in a row; refresh once they settle. */
    private const val CHANGE_DEBOUNCE_MS = 120L

    @Volatile private var module: XposedInterface? = null
    /** Latest values from the provider; null until the first sync succeeds. */
    @Volatile private var values: Map<String, Any?>? = null
    /** Dark-mode state [values] were resolved for. */
    @Volatile private var syncedDark: Boolean? = null
    /** Bumped by every change notification; a sync records the generation it started at. */
    @Volatile private var changeGeneration = 0L
    @Volatile private var syncedGeneration = -1L
    @Volatile private var observing = false
    @Volatile private var observerAttempted = false
    @Volatile private var failureLogged = false
    @Volatile private var decodedShortcutMaps: Map<ShortcutKeyboard, Pair<String?, Map<String, String>>> = emptyMap()

    fun init(module: XposedInterface) {
        this.module = module
    }

    /**
     * Makes sure the cached settings are current. Cheap after the first call: it only
     * calls into the settings app when nothing is cached yet, the dark/light state
     * changed, or a change notification arrived that the background refresh has not
     * handled yet. Without a working change observer it falls back to syncing every time.
     *
     * [force] reads regardless; for callers reacting to their own change signal, which can
     * arrive before this object's observer has counted the change.
     */
    fun syncFromProvider(context: Context, force: Boolean = false) {
        val dark = isSystemDark(context)
        if (!observerAttempted) registerObserver(context)
        if (!force && values != null && syncedDark == dark && observing &&
            syncedGeneration == changeGeneration) return
        fetch(context, dark)
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    @Synchronized
    private fun fetch(context: Context, dark: Boolean) {
        val generation = changeGeneration
        try {
            val extras = Bundle().apply { putBoolean(AppearanceProfiles.EXTRA_DARK, dark) }
            val bundle = context.contentResolver.call(PROVIDER_URI, METHOD_GET_CONFIG, null, extras)
                ?: error("provider returned no settings")
            values = ConfigSchema.fromBundle(bundle)
            syncedDark = dark
            syncedGeneration = generation
            if (failureLogged) {
                failureLogged = false
                module?.let { XposedUtils.log(it, "[Config] Settings sync recovered") }
            }
        } catch (t: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                val using = if (values == null) "defaults (all features off)" else "last synced values"
                module?.let { XposedUtils.logWarn(it, "[Config] Settings sync failed, using $using: $t") }
            }
        }
    }

    /** One observer per process. Changes are fetched on its own thread, after a short debounce. */
    @Synchronized
    private fun registerObserver(context: Context) {
        if (observerAttempted) return
        observerAttempted = true
        val app = context.applicationContext ?: context
        val thread = HandlerThread("xatype-config").apply { start() }
        val handler = Handler(thread.looper)
        val refresh = Runnable { fetch(app, isSystemDark(app)) }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                changeGeneration++
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, CHANGE_DEBOUNCE_MS)
            }
        }
        try {
            app.contentResolver.registerContentObserver(CONFIG_URI, false, observer)
            observing = true
        } catch (t: Throwable) {
            thread.quitSafely()
            module?.let { XposedUtils.logWarn(it, "[Config] Change observer unavailable, syncing on every show: $t") }
        }
    }

    private fun current(): Map<String, Any?> = values ?: ConfigSchema.defaults
    private fun bool(key: String): Boolean = current()[key] as? Boolean ?: false
    private fun int(key: String): Int = current()[key] as? Int ?: ConfigSchema.defaults[key] as Int
    private fun long(key: String): Long = current()[key] as? Long ?: 0L
    private fun string(key: String): String =
        current()[key] as? String ?: (ConfigSchema.defaults[key] as? String).orEmpty()

    fun isAiSafetyEnabled() = bool(KEY_AI_SAFETY)
    fun isVoiceModerationEnabled() = bool(KEY_VOICE_MODERATION)
    fun isCloudBlacklistEnabled() = bool(KEY_CLOUD_BLACKLIST)
    fun isClipboardSensitiveEnabled() = bool(KEY_CLIPBOARD_SENSITIVE)
    fun isClipboardPermanentEnabled() = bool(KEY_CLIPBOARD_PERMANENT)
    fun isScreenshotClipboardEnabled() = bool(KEY_SCREENSHOT_CLIPBOARD)
    fun isOsVersionUnblockEnabled() = bool(KEY_OS_VERSION_UNBLOCK)
    fun isAiAbilityUnlockEnabled() = bool(KEY_AI_ABILITY_UNLOCK)
    fun isTelemetryBlockEnabled() = bool(KEY_TELEMETRY_BLOCK)
    fun isVerboseLogEnabled() = bool(KEY_VERBOSE_LOG)

    fun isKeyboardHeightUnblockEnabled() = bool(KEY_KEYBOARD_HEIGHT_UNBLOCK)
    fun isKeyboardShortcutsEnabled() = bool(KEY_KEYBOARD_SHORTCUTS)
    fun isKeyboardHandwritingEnabled() = bool(KEY_KEYBOARD_HANDWRITING)
    fun getHandwritingUpDistance() = int(KEY_HANDWRITING_UP_DISTANCE)
    fun getHandwritingSideDistance() = int(KEY_HANDWRITING_SIDE_DISTANCE)
    fun isT9ShuangpinEnabled() = bool(KEY_T9_SHUANGPIN)
    fun getT9ShuangpinScheme() = string(KEY_T9_SHUANGPIN_SCHEME)
    fun isEmojiCandidatesEnabled() = bool(KEY_EMOJI_CANDIDATES)
    fun getPredictionMode(): PredictionMode = PredictionMode.resolve(string(KEY_PREDICTION_MODE))
    fun isBottomSpacingEnabled() = bool(KEY_BOTTOM_SPACING_ENABLED)
    fun getBottomSpacing() = int(KEY_BOTTOM_SPACING)
    fun isShortcutLabelsEnabled() = bool(KEY_SHORTCUT_LABELS)
    fun getShortcutLabelSize() = int(KEY_SHORTCUT_LABEL_SIZE)
    fun getShortcutLabelX() = int(KEY_SHORTCUT_LABEL_X)
    fun getShortcutLabelY() = int(KEY_SHORTCUT_LABEL_Y)

    /** Key-to-action mapping of [keyboard]'s downward-swipe shortcuts. Called for every rendered key. */
    fun getShortcutMapping(keyboard: ShortcutKeyboard): Map<String, String> {
        val stored = current()[keyboard.prefKey] as? String
        decodedShortcutMaps[keyboard]?.let { (raw, mapping) -> if (raw == stored) return mapping }
        val mapping = keyboard.decode(stored)
        decodedShortcutMaps = decodedShortcutMaps + (keyboard to (stored to mapping))
        return mapping
    }

    fun isStyleEnabled() = bool(KEY_STYLE_ENABLED)
    fun getCornerRadius() = int(KEY_CORNER_RADIUS)
    fun getOpacity() = int(KEY_OPACITY)
    fun getBlurRadius() = int(KEY_BLUR_RADIUS)
    fun getBgType() = int(KEY_BG_TYPE)
    fun getBgColor() = string(KEY_BG_COLOR)
    fun getTextColor() = string(KEY_TEXT_COLOR)
    fun getFunctionKeycapColor() = string(KEY_FUNCTION_KEYCAP_COLOR)
    fun getMenuCardColor() = string(KEY_MENU_CARD_COLOR)
    fun getClipboardCardColor() = string(KEY_CLIPBOARD_CARD_COLOR)
    fun getLetterKeycapColor() = string(KEY_LETTER_KEYCAP_COLOR)
    fun getKeyPopupBackground() = string(KEY_KEY_POPUP_BACKGROUND)
    fun getKeyPopupSelected() = string(KEY_KEY_POPUP_SELECTED)
    fun getKeyPopupText() = string(KEY_KEY_POPUP_TEXT)
    fun getFunctionKeycapOpacity() = int(KEY_FUNCTION_KEYCAP_OPACITY)
    fun getMenuCardOpacity() = int(KEY_MENU_CARD_OPACITY)
    fun getClipboardCardOpacity() = int(KEY_CLIPBOARD_CARD_OPACITY)
    fun getLetterKeycapOpacity() = int(KEY_LETTER_KEYCAP_OPACITY)
    fun getBgImageVersion() = long(KEY_BG_IMAGE_VERSION)
    fun isScrimEnabled() = bool(KEY_SCRIM_ENABLED)
    fun getScrimColor() = string(KEY_SCRIM_COLOR)
    fun getScrimOpacity() = int(KEY_SCRIM_OPACITY)
    fun getRimHighlight() = int(KEY_RIM_HIGHLIGHT)
    fun getRimLight() = int(KEY_RIM_LIGHT)

    fun getLocalPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
