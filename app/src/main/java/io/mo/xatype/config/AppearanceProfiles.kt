package io.mo.xatype.config

import android.content.SharedPreferences

/** Profiles share the settings file so libxposed and the provider see the same data. */
object AppearanceProfiles {
    const val FOLLOW_SYSTEM = "pref_style_follow_system"
    const val MANUAL_DARK = "pref_style_manual_dark"
    const val EXTRA_DARK = "appearance_dark"
    private const val MIGRATED = "pref_style_profiles_initialized"

    private val defaults: Map<String, Any> = linkedMapOf(
        ConfigManager.KEY_CORNER_RADIUS to 16,
        ConfigManager.KEY_OPACITY to 85,
        ConfigManager.KEY_BLUR_RADIUS to 50,
        ConfigManager.KEY_BG_TYPE to 0,
        ConfigManager.KEY_BG_COLOR to "#1E1E2E",
        ConfigManager.KEY_TEXT_COLOR to "",
        ConfigManager.KEY_FUNCTION_KEYCAP_COLOR to "",
        ConfigManager.KEY_MENU_CARD_COLOR to "",
        ConfigManager.KEY_CLIPBOARD_CARD_COLOR to "",
        ConfigManager.KEY_LETTER_KEYCAP_COLOR to "",
        ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY to 100,
        ConfigManager.KEY_MENU_CARD_OPACITY to 100,
        ConfigManager.KEY_CLIPBOARD_CARD_OPACITY to 100,
        ConfigManager.KEY_LETTER_KEYCAP_OPACITY to 100,
        ConfigManager.KEY_BG_IMAGE_VERSION to 0L
    )

    fun usesDark(followSystem: Boolean, manualDark: Boolean, systemDark: Boolean): Boolean =
        if (followSystem) systemDark else manualDark

    fun key(key: String, dark: Boolean): String =
        if (key in defaults) "${if (dark) "dark" else "light"}_$key" else key

    @Synchronized
    fun initialize(prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATED, false)) return
        val old = prefs.all
        val editor = prefs.edit()
        defaults.forEach { (name, default) ->
            val legacy = old[name] ?: when (name) {
                ConfigManager.KEY_CLIPBOARD_CARD_COLOR -> old[ConfigManager.KEY_MENU_CARD_COLOR]
                ConfigManager.KEY_CLIPBOARD_CARD_OPACITY -> old[ConfigManager.KEY_MENU_CARD_OPACITY]
                else -> null
            } ?: default
            // Preserve the existing appearance in light mode. Dark mode starts
            // with native colors/automatic contrast, retaining geometry only.
            val darkValue = if (name in setOf(ConfigManager.KEY_CORNER_RADIUS,
                    ConfigManager.KEY_OPACITY, ConfigManager.KEY_BLUR_RADIUS)) legacy else default
            for ((dark, value) in listOf(false to legacy, true to darkValue)) {
                val target = key(name, dark)
                if (prefs.contains(target)) continue
                when (value) {
                    is Int -> editor.putInt(target, value)
                    is Long -> editor.putLong(target, value)
                    is String -> editor.putString(target, value)
                }
            }
        }
        editor.putBoolean(MIGRATED, true).apply()
    }

    fun selected(prefs: SharedPreferences, systemDark: Boolean): SharedPreferences {
        // Remote preferences are read-only and may be read before first migration.
        if (!prefs.getBoolean(MIGRATED, false)) return prefs
        return forProfile(prefs, usesDark(prefs.getBoolean(FOLLOW_SYSTEM, false),
            prefs.getBoolean(MANUAL_DARK, false), systemDark))
    }

    fun forProfile(prefs: SharedPreferences, dark: Boolean): SharedPreferences =
        ProfilePreferences(prefs, dark)

    private class ProfilePreferences(
        private val source: SharedPreferences,
        private val dark: Boolean
    ) : SharedPreferences by source {
        override fun contains(key: String?) = source.contains(mapped(key))
        override fun getInt(key: String?, defValue: Int) = source.getInt(mapped(key), defValue)
        override fun getLong(key: String?, defValue: Long) = source.getLong(mapped(key), defValue)
        override fun getString(key: String?, defValue: String?) = source.getString(mapped(key), defValue)
        private fun mapped(name: String?): String? = name?.let { key(it, dark) }
        override fun edit(): SharedPreferences.Editor = ProfileEditor(source.edit(), dark)
    }

    private class ProfileEditor(
        private val source: SharedPreferences.Editor,
        private val dark: Boolean
    ) : SharedPreferences.Editor by source {
        override fun putInt(key: String?, value: Int) = apply { source.putInt(mapped(key), value) }
        override fun putLong(key: String?, value: Long) = apply { source.putLong(mapped(key), value) }
        override fun putString(key: String?, value: String?) = apply { source.putString(mapped(key), value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { source.putBoolean(mapped(key), value) }
        override fun remove(key: String?) = apply { source.remove(mapped(key)) }
        private fun mapped(name: String?): String? = name?.let { key(it, dark) }
    }
}
