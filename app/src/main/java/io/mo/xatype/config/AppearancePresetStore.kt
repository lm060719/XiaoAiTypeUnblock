package io.mo.xatype.config

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Built-in themes and named snapshots of both light/dark appearance profiles.
 *
 * Snapshots are stored separately from active style keys: saving or renaming a
 * scheme never changes the live keyboard, and applying does not touch purify or
 * keyboard enhancement settings.
 */
object AppearancePresetStore
{
    const val STORAGE_KEY = "pref_appearance_saved_schemes_v1"
    private const val SNAPSHOT_VERSION = 1
    private const val MAX_NAME_LENGTH = 30

    data class Preset(
        val id: String,
        val name: String,
        val description: String,
        val values: Map<String, Any>
    )

    data class Scheme(val id: String, val name: String, val snapshot: JSONObject)

    val presets = listOf(
        Preset("native", "清爽原生",  mapOf(
            ConfigManager.KEY_CORNER_RADIUS to 16,
            ConfigManager.KEY_OPACITY to 95,
            ConfigManager.KEY_BLUR_RADIUS to 45
        )),
        Preset("glass", "冰透玻璃", mapOf(
            ConfigManager.KEY_CORNER_RADIUS to 24,
            ConfigManager.KEY_OPACITY to 58,
            ConfigManager.KEY_BLUR_RADIUS to 70,
            ConfigManager.KEY_BG_TYPE to 0,
            ConfigManager.KEY_SCRIM_OPACITY to 40,
            ConfigManager.KEY_FUNCTION_KEYCAP_COLOR to "#DCEAF7",
            ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY to 46,
            ConfigManager.KEY_MENU_CARD_COLOR to "#E7F1FF",
            ConfigManager.KEY_MENU_CARD_OPACITY to 66,
            ConfigManager.KEY_CLIPBOARD_CARD_COLOR to "#DDEEFF",
            ConfigManager.KEY_CLIPBOARD_CARD_OPACITY to 70
        )),
        Preset("midnight", "极夜黑金",  mapOf(
            ConfigManager.KEY_CORNER_RADIUS to 22,
            ConfigManager.KEY_OPACITY to 98,
            ConfigManager.KEY_BG_TYPE to 1,
            ConfigManager.KEY_BG_COLOR to "#080A0F",
            ConfigManager.KEY_TEXT_COLOR to "#F5E8BE",
            ConfigManager.KEY_LETTER_KEYCAP_COLOR to "#171A25",
            ConfigManager.KEY_FUNCTION_KEYCAP_COLOR to "#313447",
            ConfigManager.KEY_MENU_CARD_COLOR to "#292A37",
            ConfigManager.KEY_CLIPBOARD_CARD_COLOR to "#242634",
            ConfigManager.KEY_SCRIM_COLOR to "#080A0F",
            ConfigManager.KEY_SCRIM_OPACITY to 32
        )),
        Preset("cream", "暖杏奶油",  mapOf(
            ConfigManager.KEY_CORNER_RADIUS to 26,
            ConfigManager.KEY_OPACITY to 98,
            ConfigManager.KEY_BG_TYPE to 1,
            ConfigManager.KEY_BG_COLOR to "#FFF3E4",
            ConfigManager.KEY_TEXT_COLOR to "#503A31",
            ConfigManager.KEY_LETTER_KEYCAP_COLOR to "#FFE2C7",
            ConfigManager.KEY_FUNCTION_KEYCAP_COLOR to "#E9C6AB",
            ConfigManager.KEY_MENU_CARD_COLOR to "#F6D9BF",
            ConfigManager.KEY_CLIPBOARD_CARD_COLOR to "#F4D4B6",
            ConfigManager.KEY_SCRIM_COLOR to "#FFF3E4",
            ConfigManager.KEY_SCRIM_OPACITY to 20
        )),
        Preset("neon", "赛博霓虹",  mapOf(
            ConfigManager.KEY_CORNER_RADIUS to 18,
            ConfigManager.KEY_OPACITY to 96,
            ConfigManager.KEY_BG_TYPE to 1,
            ConfigManager.KEY_BG_COLOR to "#11172D",
            ConfigManager.KEY_TEXT_COLOR to "#E5F7FF",
            ConfigManager.KEY_LETTER_KEYCAP_COLOR to "#253357",
            ConfigManager.KEY_FUNCTION_KEYCAP_COLOR to "#4C3376",
            ConfigManager.KEY_MENU_CARD_COLOR to "#294968",
            ConfigManager.KEY_CLIPBOARD_CARD_COLOR to "#382C65",
            ConfigManager.KEY_SCRIM_COLOR to "#11172D",
            ConfigManager.KEY_SCRIM_OPACITY to 35
        ))
    )

    /** Applying a built-in theme edits only the current light or dark profile. */
    fun applyPreset(prefs: SharedPreferences, dark: Boolean, id: String): Boolean
    {
        val preset = presets.firstOrNull { it.id == id } ?: return false
        val editor = prefs.edit()
        for (key in AppearanceProfiles.portableKeys)
        {
            val value = preset.values[key] ?: AppearanceProfiles.defaultValue(key)
            putValue(editor, AppearanceProfiles.key(key, dark), value)
        }
        editor.putBoolean(ConfigManager.KEY_STYLE_ENABLED, true)
        editor.putBoolean(ConfigManager.KEY_SCRIM_ENABLED, true)
        return editor.commit()
    }

    /** A saved scheme captures both appearance profiles and shared style switches. */
    fun capture(prefs: SharedPreferences): JSONObject
    {
        val result = JSONObject()
        result.put("version", SNAPSHOT_VERSION)
        for (dark in listOf(false, true))
        {
            val profile = AppearanceProfiles.forProfile(prefs, dark)
            val values = JSONObject()
            for (key in AppearanceProfiles.portableKeys)
            {
                val value = AppearanceProfiles.defaultValue(key)
                when (value)
                {
                    is Int -> values.put(key, profile.getInt(key, value))
                    is Long -> values.put(key, profile.getLong(key, value))
                    is String -> values.put(key, profile.getString(key, value) ?: value)
                }
            }
            result.put(if (dark) "dark" else "light", values)
        }
        result.put("enabled", prefs.getBoolean(ConfigManager.KEY_STYLE_ENABLED, false))
        result.put("scrimEnabled", prefs.getBoolean(ConfigManager.KEY_SCRIM_ENABLED, true))
        result.put("followSystem", prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false))
        result.put("manualDark", prefs.getBoolean(AppearanceProfiles.MANUAL_DARK, false))
        return result
    }

    fun applySnapshot(prefs: SharedPreferences, snapshot: JSONObject): Boolean
    {
        if (snapshot.optInt("version", -1) != SNAPSHOT_VERSION ||
            snapshot.optJSONObject("light") == null || snapshot.optJSONObject("dark") == null)
        {
            return false
        }
        val editor = prefs.edit()
        for (dark in listOf(false, true))
        {
            val values = snapshot.getJSONObject(if (dark) "dark" else "light")
            for (key in AppearanceProfiles.portableKeys)
            {
                val default = AppearanceProfiles.defaultValue(key)
                val value = when (default)
                {
                    is Int -> values.optInt(key, default)
                    is Long -> values.optLong(key, default)
                    is String -> values.optString(key, default)
                    else -> continue
                }
                putValue(editor, AppearanceProfiles.key(key, dark), value)
            }
        }
        editor.putBoolean(ConfigManager.KEY_STYLE_ENABLED, snapshot.optBoolean("enabled", false))
        editor.putBoolean(ConfigManager.KEY_SCRIM_ENABLED, snapshot.optBoolean("scrimEnabled", true))
        editor.putBoolean(AppearanceProfiles.FOLLOW_SYSTEM, snapshot.optBoolean("followSystem", false))
        editor.putBoolean(AppearanceProfiles.MANUAL_DARK, snapshot.optBoolean("manualDark", false))
        return editor.commit()
    }

    fun saved(prefs: SharedPreferences): List<Scheme>
    {
        val array = runCatching { JSONArray(prefs.getString(STORAGE_KEY, "[]") ?: "[]") }
            .getOrElse { return emptyList() }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val snapshot = item.optJSONObject("snapshot") ?: return@mapNotNull null
            if (snapshot.optInt("version", -1) != SNAPSHOT_VERSION) return@mapNotNull null
            Scheme(id, name, snapshot)
        }
    }

    fun save(prefs: SharedPreferences, name: String): Boolean
    {
        val trimmed = name.trim()
        val existing = saved(prefs)
        if (!validName(trimmed) || existing.any { it.name.equals(trimmed, ignoreCase = true) }) return false
        return persist(prefs, existing + Scheme(UUID.randomUUID().toString(), trimmed, capture(prefs)))
    }

    fun overwrite(prefs: SharedPreferences, id: String): Boolean
    {
        val items = saved(prefs)
        if (items.none { it.id == id }) return false
        return persist(prefs, items.map { if (it.id == id) it.copy(snapshot = capture(prefs)) else it })
    }

    fun rename(prefs: SharedPreferences, id: String, name: String): Boolean
    {
        val trimmed = name.trim()
        val items = saved(prefs)
        if (!validName(trimmed) || items.none { it.id == id } ||
            items.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) return false
        return persist(prefs, items.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun delete(prefs: SharedPreferences, id: String): Boolean
    {
        val items = saved(prefs)
        if (items.none { it.id == id }) return false
        return persist(prefs, items.filterNot { it.id == id })
    }

    fun applySaved(prefs: SharedPreferences, id: String): Boolean
    {
        val snapshot = saved(prefs).firstOrNull { it.id == id }?.snapshot ?: return false
        return applySnapshot(prefs, snapshot)
    }

    private fun validName(name: String) = name.isNotBlank() && name.length <= MAX_NAME_LENGTH

    private fun persist(prefs: SharedPreferences, schemes: List<Scheme>): Boolean
    {
        val array = JSONArray()
        schemes.forEach { scheme ->
            array.put(JSONObject().put("id", scheme.id).put("name", scheme.name)
                .put("snapshot", scheme.snapshot))
        }
        return prefs.edit().putString(STORAGE_KEY, array.toString()).commit()
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any)
    {
        when (value)
        {
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
        }
    }
}
