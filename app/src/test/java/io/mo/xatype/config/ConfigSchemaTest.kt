package io.mo.xatype.config

import android.content.SharedPreferences
import io.mo.xatype.keyboard.ShortcutKeyboard
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ConfigSchemaTest {
    @Test fun emptySettingsReadAsDefaults() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        val values = ConfigSchema.read(prefs, AppearanceProfiles.selected(prefs, false))
        assertEquals(ConfigSchema.defaults, values)
        assertEquals(false, values[ConfigManager.KEY_AI_SAFETY])
        assertEquals(true, values[ConfigManager.KEY_SCRIM_ENABLED])
        assertEquals(16, values[ConfigManager.KEY_CORNER_RADIUS])
        assertEquals("#1E1E2E", values[ConfigManager.KEY_BG_COLOR])
        assertEquals(0L, values[ConfigManager.KEY_BG_IMAGE_VERSION])
        ShortcutKeyboard.entries.forEach { assertNull(values[it.prefKey]) }
    }

    @Test fun profileEntriesFollowTheSelectedProfileAndGlobalsDoNot() {
        val prefs = memoryPreferences()
        AppearanceProfiles.initialize(prefs)
        prefs.edit().putBoolean(ConfigManager.KEY_STYLE_ENABLED, true)
            .putBoolean(AppearanceProfiles.FOLLOW_SYSTEM, true).apply()
        AppearanceProfiles.forProfile(prefs, false).edit().putInt(ConfigManager.KEY_CORNER_RADIUS, 8).apply()
        AppearanceProfiles.forProfile(prefs, true).edit().putInt(ConfigManager.KEY_CORNER_RADIUS, 24).apply()

        val light = ConfigSchema.read(prefs, AppearanceProfiles.selected(prefs, false))
        val dark = ConfigSchema.read(prefs, AppearanceProfiles.selected(prefs, true))
        assertEquals(8, light[ConfigManager.KEY_CORNER_RADIUS])
        assertEquals(24, dark[ConfigManager.KEY_CORNER_RADIUS])
        assertEquals(true, light[ConfigManager.KEY_STYLE_ENABLED])
        assertEquals(true, dark[ConfigManager.KEY_STYLE_ENABLED])
    }

    @Test fun storedValuesAreClampedToTheirRange() {
        val prefs = memoryPreferences()
        prefs.edit().putInt(ConfigManager.KEY_BOTTOM_SPACING, 500)
            .putInt(ConfigManager.KEY_SHORTCUT_LABEL_SIZE, 2)
            .putInt(ConfigManager.KEY_HANDWRITING_UP_DISTANCE, 999).apply()
        val values = ConfigSchema.read(prefs, prefs)
        assertEquals(100, values[ConfigManager.KEY_BOTTOM_SPACING])
        assertEquals(8, values[ConfigManager.KEY_SHORTCUT_LABEL_SIZE])
        assertEquals(ConfigSchema.HANDWRITING_UP_DISTANCE_RANGE.last, values[ConfigManager.KEY_HANDWRITING_UP_DISTANCE])
    }

    @Test fun clipboardCardFallsBackToMenuCardOnlyWhenUnset() {
        val prefs = memoryPreferences()
        prefs.edit().putString(ConfigManager.KEY_MENU_CARD_COLOR, "#123456")
            .putInt(ConfigManager.KEY_MENU_CARD_OPACITY, 40).apply()
        val inherited = ConfigSchema.read(prefs, prefs)
        assertEquals("#123456", inherited[ConfigManager.KEY_CLIPBOARD_CARD_COLOR])
        assertEquals(40, inherited[ConfigManager.KEY_CLIPBOARD_CARD_OPACITY])

        prefs.edit().putString(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, "")
            .putInt(ConfigManager.KEY_CLIPBOARD_CARD_OPACITY, 100).apply()
        val own = ConfigSchema.read(prefs, prefs)
        assertEquals("", own[ConfigManager.KEY_CLIPBOARD_CARD_COLOR])
        assertEquals(100, own[ConfigManager.KEY_CLIPBOARD_CARD_OPACITY])
    }

    @Test fun normalizeRepairsMissingAndWronglyTypedValues() {
        val values = ConfigSchema.normalize(mapOf(
            ConfigManager.KEY_AI_SAFETY to "yes",
            ConfigManager.KEY_RIM_LIGHT to 7,
            ConfigManager.KEY_PREDICTION_MODE to null
        ))
        assertEquals(ConfigSchema.defaults.keys, values.keys)
        assertEquals(false, values[ConfigManager.KEY_AI_SAFETY])
        assertEquals(2, values[ConfigManager.KEY_RIM_LIGHT])
        assertEquals("KEEP", values[ConfigManager.KEY_PREDICTION_MODE])
    }

    @Test fun profileDefaultsCoverEveryPortableAppearanceKey() {
        assertTrue(ConfigSchema.profileDefaults.keys.containsAll(AppearanceProfiles.portableKeys))
        assertFalse(ConfigManager.KEY_BG_IMAGE_VERSION in AppearanceProfiles.portableKeys)
        ConfigSchema.entries.filter { it.scope == ConfigSchema.Scope.PROFILE }
            .forEach { assertTrue(it.key, it !is ConfigSchema.Bool) }
    }

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
