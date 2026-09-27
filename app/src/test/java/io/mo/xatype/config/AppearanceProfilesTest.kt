package io.mo.xatype.config

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AppearanceProfilesTest {
    @Test fun migrationPreservesOldAppearanceAndGivesDarkModeAutomaticColors() {
        val prefs = memoryPreferences()
        prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, "#000000")
            .putInt(ConfigManager.KEY_CORNER_RADIUS, 28)
            .putString(ConfigManager.KEY_MENU_CARD_COLOR, "#eeeeee")
            .putInt(ConfigManager.KEY_MENU_CARD_OPACITY, 65).apply()
        AppearanceProfiles.initialize(prefs)
        val light = AppearanceProfiles.forProfile(prefs, false)
        val dark = AppearanceProfiles.forProfile(prefs, true)
        assertEquals("#000000", light.getString(ConfigManager.KEY_TEXT_COLOR, null))
        assertEquals("#eeeeee", light.getString(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, null))
        assertEquals(65, light.getInt(ConfigManager.KEY_CLIPBOARD_CARD_OPACITY, -1))
        assertEquals("", dark.getString(ConfigManager.KEY_TEXT_COLOR, null))
        assertEquals("", dark.getString(ConfigManager.KEY_MENU_CARD_COLOR, null))
        assertEquals(28, dark.getInt(ConfigManager.KEY_CORNER_RADIUS, -1))
        assertEquals("#000000", prefs.getString(ConfigManager.KEY_TEXT_COLOR, null))
    }

    @Test fun profilesKeepEditsSeparateAndMigrationDoesNotResetThem() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        val light = AppearanceProfiles.forProfile(prefs, false)
        val dark = AppearanceProfiles.forProfile(prefs, true)
        light.edit().putString(ConfigManager.KEY_TEXT_COLOR, "#112233")
            .putInt(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, 30).apply()
        dark.edit().putString(ConfigManager.KEY_TEXT_COLOR, "#ddeeff")
            .putInt(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, 75).apply()
        AppearanceProfiles.initialize(prefs)
        assertEquals("#112233", light.getString(ConfigManager.KEY_TEXT_COLOR, null))
        assertEquals("#ddeeff", dark.getString(ConfigManager.KEY_TEXT_COLOR, null))
        assertEquals(30, light.getInt(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, -1))
        assertEquals(75, dark.getInt(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, -1))
        dark.edit().putBoolean(ConfigManager.KEY_STYLE_ENABLED, true).apply()
        assertTrue(light.getBoolean(ConfigManager.KEY_STYLE_ENABLED, false))
    }

    @Test fun automaticAndManualSelectionWorkInBothSystemModes() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        AppearanceProfiles.forProfile(prefs, false).edit()
            .putString(ConfigManager.KEY_TEXT_COLOR, "light").apply()
        AppearanceProfiles.forProfile(prefs, true).edit()
            .putString(ConfigManager.KEY_TEXT_COLOR, "dark").apply()
        for (follow in listOf(false, true)) {
            for (manual in listOf(false, true)) {
                prefs.edit().putBoolean(AppearanceProfiles.FOLLOW_SYSTEM, follow)
                    .putBoolean(AppearanceProfiles.MANUAL_DARK, manual).apply()
                for (system in listOf(false, true)) {
                    val expected = if (if (follow) system else manual) "dark" else "light"
                    assertEquals(expected, AppearanceProfiles.selected(prefs, system)
                        .getString(ConfigManager.KEY_TEXT_COLOR, null))
                }
            }
        }
    }

    @Test fun unmigratedRemotePreferencesStillUseLegacySettings() {
        val prefs = memoryPreferences()
        prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, "#123456").apply()
        assertEquals("#123456", AppearanceProfiles.selected(prefs, true)
            .getString(ConfigManager.KEY_TEXT_COLOR, null))
    }

    /** Implements only the Android preference storage boundary, without a device. */
    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0])
                "edit" -> {
                    val pending = mutableMapOf<String, Any?>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { editor, action, params ->
                        when (action.name) {
                            "apply", "commit" -> { values.putAll(pending); true }
                            else -> {
                                check(action.name.startsWith("put"))
                                pending[params!![0] as String] = params[1]
                                editor
                            }
                        }
                    }
                }
                else -> {
                    check(method.name.startsWith("get"))
                    values[args!![0]] ?: args[1]
                }
            }
        } as SharedPreferences
    }
}
