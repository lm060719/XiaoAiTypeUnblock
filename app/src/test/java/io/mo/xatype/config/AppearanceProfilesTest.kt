package io.mo.xatype.config

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AppearanceProfilesTest {
    @Test fun officialPresetRestoresNativeValuesOnlyInTheSelectedProfile() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        val light = AppearanceProfiles.forProfile(prefs, false)
        val dark = AppearanceProfiles.forProfile(prefs, true)
        light.edit().putString(ConfigManager.KEY_TEXT_COLOR, "#112233")
            .putString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, "#445566")
            .putInt(ConfigManager.KEY_OPACITY, 20)
            .putInt(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, 30).apply()
        dark.edit().putString(ConfigManager.KEY_TEXT_COLOR, "#ddeeff").apply()

        assertTrue(AppearancePresetStore.applyPreset(prefs, false, AppearancePresetStore.OFFICIAL))
        assertTrue(AppearancePresetStore.isOfficial(light))
        assertFalse(AppearancePresetStore.isOfficial(dark))
        assertEquals("", light.getString(ConfigManager.KEY_TEXT_COLOR, null))
        assertEquals("", light.getString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, null))
        assertEquals(100, light.getInt(ConfigManager.KEY_OPACITY, -1))
        assertEquals(100, light.getInt(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, -1))
        assertEquals(0, light.getInt(ConfigManager.KEY_SCRIM_OPACITY, -1))
        assertEquals("#ddeeff", dark.getString(ConfigManager.KEY_TEXT_COLOR, null))
        val hostValues = ConfigSchema.read(prefs, light)
        assertEquals(AppearancePresetStore.OFFICIAL, hostValues[ConfigManager.KEY_APPEARANCE_MODE])
        assertEquals(true, hostValues[ConfigManager.KEY_STYLE_ENABLED])
    }

    @Test fun officialGeometryStaysEditableAndAnotherPresetUnlocksColors() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        val light = AppearanceProfiles.forProfile(prefs, false)
        AppearancePresetStore.applyPreset(prefs, false, AppearancePresetStore.OFFICIAL)
        light.edit().putInt(ConfigManager.KEY_CORNER_RADIUS, 30)
            .putInt(ConfigManager.KEY_RIM_HIGHLIGHT, 70)
            .putInt(ConfigManager.KEY_RIM_LIGHT, 2).apply()
        assertTrue(AppearancePresetStore.isOfficial(light))
        assertEquals(30, light.getInt(ConfigManager.KEY_CORNER_RADIUS, -1))
        assertEquals(70, light.getInt(ConfigManager.KEY_RIM_HIGHLIGHT, -1))
        assertEquals(2, light.getInt(ConfigManager.KEY_RIM_LIGHT, -1))

        assertTrue(AppearancePresetStore.applyPreset(prefs, false, "cream"))
        assertFalse(AppearancePresetStore.isOfficial(light))
        assertEquals("#503A31", light.getString(ConfigManager.KEY_TEXT_COLOR, null))
    }

    @Test fun savedSchemesRestoreOfficialModeAndGeometryForEachProfile() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        val light = AppearanceProfiles.forProfile(prefs, false)
        val dark = AppearanceProfiles.forProfile(prefs, true)
        AppearancePresetStore.applyPreset(prefs, false, AppearancePresetStore.OFFICIAL)
        AppearancePresetStore.applyPreset(prefs, true, "midnight")
        light.edit().putInt(ConfigManager.KEY_CORNER_RADIUS, 32)
            .putInt(ConfigManager.KEY_RIM_HIGHLIGHT, 60).apply()
        assertTrue(AppearancePresetStore.save(prefs, "官方圆角"))
        val scheme = AppearancePresetStore.saved(prefs).single()
        AppearancePresetStore.applyPreset(prefs, false, "glass")
        AppearancePresetStore.applyPreset(prefs, true, AppearancePresetStore.OFFICIAL)

        assertTrue(AppearancePresetStore.applySaved(prefs, scheme.id))
        assertTrue(AppearancePresetStore.isOfficial(light))
        assertFalse(AppearancePresetStore.isOfficial(dark))
        assertEquals(32, light.getInt(ConfigManager.KEY_CORNER_RADIUS, -1))
        assertEquals(60, light.getInt(ConfigManager.KEY_RIM_HIGHLIGHT, -1))
        assertEquals("#F5E8BE", dark.getString(ConfigManager.KEY_TEXT_COLOR, null))
    }

    @Test fun oldSnapshotsWithoutAnAppearanceModeRemainEditable() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        val old = AppearancePresetStore.capture(prefs)
        old.getJSONObject("light").remove(ConfigManager.KEY_APPEARANCE_MODE)
        old.getJSONObject("dark").remove(ConfigManager.KEY_APPEARANCE_MODE)
        AppearancePresetStore.applyPreset(prefs, false, AppearancePresetStore.OFFICIAL)
        AppearancePresetStore.applyPreset(prefs, true, AppearancePresetStore.OFFICIAL)
        assertTrue(AppearancePresetStore.applySnapshot(prefs, old))
        assertFalse(AppearancePresetStore.isOfficial(AppearanceProfiles.forProfile(prefs, false)))
        assertFalse(AppearancePresetStore.isOfficial(AppearanceProfiles.forProfile(prefs, true)))
    }

    @Test fun popupColorsStayIndependentAcrossProfilesAndSavedSchemes() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        val keys = listOf(ConfigManager.KEY_KEY_POPUP_BACKGROUND,
            ConfigManager.KEY_KEY_POPUP_SELECTED, ConfigManager.KEY_KEY_POPUP_TEXT)
        keys.forEachIndexed { index, key ->
            AppearanceProfiles.forProfile(prefs, false).edit().putString(key, "#11223$index").apply()
            AppearanceProfiles.forProfile(prefs, true).edit().putString(key, "#44556$index").apply()
        }
        val saved = AppearancePresetStore.capture(prefs)
        AppearancePresetStore.applyPreset(prefs, false, "native")
        keys.forEach { assertEquals("", AppearanceProfiles.forProfile(prefs, false).getString(it, null)) }
        assertTrue(AppearancePresetStore.applySnapshot(prefs, saved))
        keys.forEachIndexed { index, key ->
            assertEquals("#11223$index", AppearanceProfiles.forProfile(prefs, false).getString(key, null))
            assertEquals("#44556$index", AppearanceProfiles.forProfile(prefs, true).getString(key, null))
        }
    }

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
