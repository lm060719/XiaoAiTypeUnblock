package io.mo.xatype.config

import android.content.SharedPreferences
import android.os.Bundle
import io.mo.xatype.keyboard.PredictionMode
import io.mo.xatype.keyboard.ShortcutKeyboard

/**
 * Every setting the hooked processes read, declared once: key, type, default,
 * valid range and whether it lives in the light/dark appearance profile.
 *
 * The settings app packs values from this table in [LogContentProvider] and
 * the hooks unpack them in [ConfigManager], so adding a setting is one entry here
 * plus its getter.
 */
object ConfigSchema {
    enum class Scope {
        /** One value shared by both appearance profiles. */
        GLOBAL,
        /** Stored separately for the light and dark profile; see [AppearanceProfiles]. */
        PROFILE
    }

    sealed class Entry(val key: String, val scope: Scope, val fallback: String?) {
        abstract val default: Any?
        /** Clamps or replaces a raw value so it is always of this entry's type and range. */
        abstract fun normalize(raw: Any?): Any?
        internal abstract fun readPrefs(prefs: SharedPreferences, key: String): Any?
        internal abstract fun put(bundle: Bundle, value: Any?)
        internal abstract fun get(bundle: Bundle): Any?
    }

    class Bool(key: String, override val default: Boolean, scope: Scope = Scope.GLOBAL) :
        Entry(key, scope, null) {
        override fun normalize(raw: Any?): Boolean = raw as? Boolean ?: default
        override fun readPrefs(prefs: SharedPreferences, key: String) = prefs.getBoolean(key, default)
        override fun put(bundle: Bundle, value: Any?) = bundle.putBoolean(key, normalize(value))
        override fun get(bundle: Bundle) = normalize(bundle.getBoolean(key, default))
    }

    class Int32(
        key: String,
        override val default: Int,
        val range: IntRange? = null,
        scope: Scope = Scope.GLOBAL,
        fallback: String? = null
    ) : Entry(key, scope, fallback) {
        override fun normalize(raw: Any?): Int {
            val value = raw as? Int ?: default
            return if (range != null) value.coerceIn(range) else value
        }
        override fun readPrefs(prefs: SharedPreferences, key: String) = prefs.getInt(key, default)
        override fun put(bundle: Bundle, value: Any?) = bundle.putInt(key, normalize(value))
        override fun get(bundle: Bundle) = normalize(bundle.getInt(key, default))
    }

    class Int64(key: String, override val default: Long, scope: Scope = Scope.GLOBAL) :
        Entry(key, scope, null) {
        override fun normalize(raw: Any?): Long = raw as? Long ?: default
        override fun readPrefs(prefs: SharedPreferences, key: String) = prefs.getLong(key, default)
        override fun put(bundle: Bundle, value: Any?) = bundle.putLong(key, normalize(value))
        override fun get(bundle: Bundle) = normalize(bundle.getLong(key, default))
    }

    /** A null default means "absent": the key is only sent when the user stored a value. */
    class Text(
        key: String,
        override val default: String?,
        scope: Scope = Scope.GLOBAL,
        fallback: String? = null
    ) : Entry(key, scope, fallback) {
        override fun normalize(raw: Any?): String? = raw as? String ?: default
        override fun readPrefs(prefs: SharedPreferences, key: String): String? = prefs.getString(key, default)
        override fun put(bundle: Bundle, value: Any?) {
            normalize(value)?.let { bundle.putString(key, it) }
        }
        override fun get(bundle: Bundle) = normalize(bundle.getString(key))
    }

    // Ranges live here, not in ConfigManager, so the table loads without Android framework classes.
    val HANDWRITING_UP_DISTANCE_RANGE = 16..200
    val HANDWRITING_SIDE_DISTANCE_RANGE = 12..200

    private val P = Scope.PROFILE
    private val PERCENT = 0..100

    val entries: List<Entry> = buildList {
        // Purification
        add(Bool(ConfigManager.KEY_AI_SAFETY, false))
        add(Bool(ConfigManager.KEY_VOICE_MODERATION, false))
        add(Bool(ConfigManager.KEY_CLOUD_BLACKLIST, false))
        add(Bool(ConfigManager.KEY_CLIPBOARD_SENSITIVE, false))
        add(Bool(ConfigManager.KEY_CLIPBOARD_PERMANENT, false))
        add(Bool(ConfigManager.KEY_SCREENSHOT_CLIPBOARD, false))
        add(Bool(ConfigManager.KEY_OS_VERSION_UNBLOCK, false))
        add(Bool(ConfigManager.KEY_AI_ABILITY_UNLOCK, false))
        add(Bool(ConfigManager.KEY_TELEMETRY_BLOCK, false))
        add(Bool(ConfigManager.KEY_VERBOSE_LOG, false))

        // Keyboard
        add(Bool(ConfigManager.KEY_KEYBOARD_HEIGHT_UNBLOCK, false))
        add(Bool(ConfigManager.KEY_KEYBOARD_SHORTCUTS, false))
        add(Bool(ConfigManager.KEY_KEYBOARD_HANDWRITING, false))
        add(Int32(ConfigManager.KEY_HANDWRITING_UP_DISTANCE, ConfigManager.HANDWRITING_UP_DISTANCE_DEFAULT,
            HANDWRITING_UP_DISTANCE_RANGE))
        add(Int32(ConfigManager.KEY_HANDWRITING_SIDE_DISTANCE, ConfigManager.HANDWRITING_SIDE_DISTANCE_DEFAULT,
            HANDWRITING_SIDE_DISTANCE_RANGE))
        add(Bool(ConfigManager.KEY_T9_SHUANGPIN, false))
        add(Text(ConfigManager.KEY_T9_SHUANGPIN_SCHEME, "XIAOHE"))
        add(Bool(ConfigManager.KEY_EMOJI_CANDIDATES, false))
        add(Text(ConfigManager.KEY_PREDICTION_MODE, PredictionMode.KEEP.name))
        add(Bool(ConfigManager.KEY_BOTTOM_SPACING_ENABLED, false))
        add(Int32(ConfigManager.KEY_BOTTOM_SPACING, 0, PERCENT))
        add(Bool(ConfigManager.KEY_SHORTCUT_LABELS, false))
        add(Int32(ConfigManager.KEY_SHORTCUT_LABEL_SIZE, 11, 8..20))
        add(Int32(ConfigManager.KEY_SHORTCUT_LABEL_X, 0, -40..40))
        add(Int32(ConfigManager.KEY_SHORTCUT_LABEL_Y, -2, -40..40))
        ShortcutKeyboard.entries.forEach { add(Text(it.prefKey, null)) }

        // Appearance
        add(Bool(ConfigManager.KEY_STYLE_ENABLED, false))
        add(Bool(ConfigManager.KEY_SCRIM_ENABLED, true))
        add(Int32(ConfigManager.KEY_CORNER_RADIUS, 16, scope = P))
        add(Int32(ConfigManager.KEY_OPACITY, 85, scope = P))
        add(Int32(ConfigManager.KEY_BLUR_RADIUS, 50, scope = P))
        add(Int32(ConfigManager.KEY_BG_TYPE, 0, scope = P))
        add(Text(ConfigManager.KEY_BG_COLOR, "#1E1E2E", P))
        add(Text(ConfigManager.KEY_TEXT_COLOR, "", P))
        add(Text(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, "", P))
        add(Text(ConfigManager.KEY_MENU_CARD_COLOR, "", P))
        add(Text(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, "", P, fallback = ConfigManager.KEY_MENU_CARD_COLOR))
        add(Text(ConfigManager.KEY_LETTER_KEYCAP_COLOR, "", P))
        add(Text(ConfigManager.KEY_KEY_POPUP_BACKGROUND, "", P))
        add(Text(ConfigManager.KEY_KEY_POPUP_SELECTED, "", P))
        add(Text(ConfigManager.KEY_KEY_POPUP_TEXT, "", P))
        add(Int32(ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY, 100, PERCENT, P))
        add(Int32(ConfigManager.KEY_MENU_CARD_OPACITY, 100, PERCENT, P))
        add(Int32(ConfigManager.KEY_CLIPBOARD_CARD_OPACITY, 100, PERCENT, P,
            fallback = ConfigManager.KEY_MENU_CARD_OPACITY))
        add(Int32(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, 100, PERCENT, P))
        add(Text(ConfigManager.KEY_SCRIM_COLOR, "", P))
        add(Int32(ConfigManager.KEY_SCRIM_OPACITY, ConfigManager.DEFAULT_SCRIM_OPACITY, PERCENT, P))
        add(Int32(ConfigManager.KEY_RIM_HIGHLIGHT, 0, PERCENT, P))
        add(Int32(ConfigManager.KEY_RIM_LIGHT, 0, 0..2, P))
        add(Int64(ConfigManager.KEY_BG_IMAGE_VERSION, 0L, P))
    }

    private val byKey: Map<String, Entry> = entries.associateBy { it.key }

    init {
        check(byKey.size == entries.size) { "Duplicate config key" }
        entries.forEach { e -> e.fallback?.let { check(byKey[it]?.scope == e.scope) { "Bad fallback for ${e.key}" } } }
    }

    /** Values used before the first successful sync. */
    val defaults: Map<String, Any?> = entries.associate { it.key to it.default }

    /** Default of every per-profile entry, in declaration order. */
    val profileDefaults: Map<String, Any> = entries
        .filter { it.scope == Scope.PROFILE }
        .associateTo(LinkedHashMap()) { it.key to checkNotNull(it.default) }

    operator fun get(key: String): Entry? = byKey[key]

    /**
     * Reads every entry: global ones from [local], per-profile ones from [profile]
     * (the light or dark view of the same file returned by [AppearanceProfiles.selected]).
     */
    fun read(local: SharedPreferences, profile: SharedPreferences): Map<String, Any?> {
        val values = HashMap<String, Any?>(entries.size * 2)
        for (entry in entries) {
            val source = if (entry.scope == Scope.PROFILE) profile else local
            val stored = entry.fallback
                ?.takeUnless { source.contains(entry.key) }
                ?.let { fallbackKey -> byKey.getValue(fallbackKey).readPrefs(source, fallbackKey) }
                ?: entry.readPrefs(source, entry.key)
            values[entry.key] = entry.normalize(stored)
        }
        return values
    }

    /** Fills in missing keys and clamps out-of-range values. */
    fun normalize(raw: Map<String, Any?>): Map<String, Any?> =
        entries.associate { it.key to it.normalize(raw[it.key]) }

    fun toBundle(values: Map<String, Any?>): Bundle = Bundle().apply {
        entries.forEach { it.put(this, values[it.key]) }
    }

    fun fromBundle(bundle: Bundle): Map<String, Any?> = entries.associate { it.key to it.get(bundle) }
}
