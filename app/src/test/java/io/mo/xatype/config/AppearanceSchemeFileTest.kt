package io.mo.xatype.config

import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppearanceSchemeFileTest {
    @Test fun savedSchemeRoundTripPreservesBothProfilesWithoutChangingLiveSettingsOnImport() {
        val source = preferences()
        AppearanceProfiles.initialize(source)
        AppearancePresetStore.applyPreset(source, false, "cream")
        AppearancePresetStore.applyPreset(source, true, AppearancePresetStore.OFFICIAL)
        AppearanceProfiles.forProfile(source, true).edit()
            .putInt(ConfigManager.KEY_CORNER_RADIUS, 32)
            .putLong(ConfigManager.KEY_BG_IMAGE_VERSION, 123L).apply()
        source.edit().putBoolean(AppearanceProfiles.FOLLOW_SYSTEM, true)
            .putBoolean(AppearanceProfiles.MANUAL_DARK, true)
            .putBoolean(ConfigManager.KEY_SCRIM_ENABLED, false).apply()
        assertTrue(AppearancePresetStore.save(source, "日常 / 深夜"))
        val original = AppearancePresetStore.saved(source).single()
        AppearancePresetStore.applyPreset(source, false, "neon")
        val encoded = AppearanceSchemeFile.encode(original)
        val document = read(encoded)
        assertEquals(original.name, document.name)
        assertTrue(original.snapshot.similar(document.snapshot))
        assertFalse(encoded.contains(original.id))
        assertFalse(encoded.contains(ConfigManager.KEY_BG_IMAGE_VERSION))

        val target = preferences()
        AppearanceProfiles.initialize(target)
        target.edit().putBoolean(ConfigManager.KEY_AI_SAFETY, true).apply()
        AppearanceProfiles.forProfile(target, true).edit()
            .putLong(ConfigManager.KEY_BG_IMAGE_VERSION, 456L).apply()
        val before = target.all
        val imported = AppearancePresetStore.importScheme(target, document)!!
        assertNotEquals(original.id, imported.id)
        assertEquals(before, target.all.filterKeys { it != AppearancePresetStore.STORAGE_KEY })
        assertTrue(AppearancePresetStore.applySaved(target, imported.id))
        assertTrue(original.snapshot.similar(AppearancePresetStore.capture(target)))
        assertTrue(target.getBoolean(ConfigManager.KEY_AI_SAFETY, false))
        assertEquals(456L, AppearanceProfiles.forProfile(target, true)
            .getLong(ConfigManager.KEY_BG_IMAGE_VERSION, 0))
    }

    @Test fun duplicateImportsKeepExistingArchivesAndGenerateUniqueNamesWithinTheLimit() {
        val prefs = preferences()
        AppearanceProfiles.initialize(prefs)
        val name = "A".repeat(AppearancePresetStore.MAX_NAME_LENGTH)
        assertTrue(AppearancePresetStore.save(prefs, name))
        val original = AppearancePresetStore.saved(prefs).single()
        val document = read(AppearanceSchemeFile.encode(original))
        val second = AppearancePresetStore.importScheme(prefs, document)!!
        val third = AppearancePresetStore.importScheme(prefs, document.copy(name = name.lowercase()))!!
        assertEquals("A".repeat(26) + " (2)", second.name)
        assertEquals("a".repeat(26) + " (3)", third.name)
        val saved = AppearancePresetStore.saved(prefs)
        assertEquals(3, saved.map { it.id }.toSet().size)
        assertEquals(original.name, saved.first().name)
        assertTrue(original.snapshot.similar(saved.first().snapshot))
    }

    @Test fun invalidFilesAndUnsupportedVersionsAreRejected() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("format", "another-app") },
            { it.put("version", 2) },
            { it.put("version", "1") },
            { it.put("name", " ") },
            { it.put("name", "名".repeat(31)) },
            { it.remove("snapshot") },
            { it.getJSONObject("snapshot").put("version", 2) },
            { it.getJSONObject("snapshot").remove("dark") },
            { it.getJSONObject("snapshot").put("enabled", "true") }
        )
        for (mutation in mutations) {
            val json = validFile()
            mutation(json)
            expectRejected { read(json.toString()) }
        }
        for (text in listOf("", "not json", "[]", "{", validFile().toString() + "garbage")) {
            expectRejected { read(text) }
        }
    }

    @Test fun invalidAppearanceValuesAreRejectedBeforeTheyCanBeSaved() {
        val invalid = listOf(
            ConfigManager.KEY_OPACITY to "85",
            ConfigManager.KEY_OPACITY to true,
            ConfigManager.KEY_OPACITY to 100.5,
            ConfigManager.KEY_OPACITY to Long.MAX_VALUE,
            ConfigManager.KEY_OPACITY to -1,
            ConfigManager.KEY_OPACITY to 101,
            ConfigManager.KEY_CORNER_RADIUS to 41,
            ConfigManager.KEY_BLUR_RADIUS to 19,
            ConfigManager.KEY_BLUR_RADIUS to 101,
            ConfigManager.KEY_BG_TYPE to 2,
            ConfigManager.KEY_RIM_LIGHT to 3,
            ConfigManager.KEY_TEXT_COLOR to "#xyzxyz",
            ConfigManager.KEY_TEXT_COLOR to JSONObject.NULL,
            ConfigManager.KEY_APPEARANCE_MODE to "unknown"
        )
        for ((key, value) in invalid) {
            val json = validFile()
            json.getJSONObject("snapshot").getJSONObject("light").put(key, value)
            expectRejected { read(json.toString()) }
        }
        val prefs = preferences()
        val before = prefs.all
        val malformed = validFile().getJSONObject("snapshot").apply { remove("dark") }
        expectRejected {
            AppearancePresetStore.importScheme(prefs, AppearanceSchemeFile.Document("无效配置", malformed))
        }
        assertEquals(before, prefs.all)
    }

    @Test fun olderOptionalValuesUseDefaultsAndUnknownOrDeviceLocalValuesAreDiscarded() {
        val json = validFile()
        val snapshot = json.getJSONObject("snapshot")
        for (profile in listOf("light", "dark")) {
            snapshot.getJSONObject(profile).apply {
                remove(ConfigManager.KEY_APPEARANCE_MODE)
                remove(ConfigManager.KEY_KEY_POPUP_TEXT)
                put(ConfigManager.KEY_BG_IMAGE_VERSION, 999L)
                put(ConfigManager.KEY_AI_SAFETY, true)
                put("future_setting", "unused")
            }
        }
        snapshot.put("future_switch", true)
        val document = read("\uFEFF" + json.toString())
        val light = document.snapshot.getJSONObject("light")
        assertEquals("", light.getString(ConfigManager.KEY_APPEARANCE_MODE))
        assertEquals("", light.getString(ConfigManager.KEY_KEY_POPUP_TEXT))
        assertFalse(light.has(ConfigManager.KEY_BG_IMAGE_VERSION))
        assertFalse(light.has(ConfigManager.KEY_AI_SAFETY))
        assertFalse(light.has("future_setting"))
        assertFalse(document.snapshot.has("future_switch"))
    }

    @Test fun oversizedStreamsStopAfterTheLimitEvenWithoutAReportedFileSize() {
        var readCount = 0
        val endless = object : InputStream() {
            override fun read(): Int { readCount++; return ' '.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(' '.code.toByte(), offset, offset + length)
                readCount += length
                return length
            }
        }
        expectRejected { AppearanceSchemeFile.read(endless) }
        assertEquals(AppearanceSchemeFile.MAX_FILE_BYTES + 1, readCount)
        val valid = validFile().toString().toByteArray(Charsets.UTF_8)
        val padded = valid.copyOf(AppearanceSchemeFile.MAX_FILE_BYTES)
        padded.fill(' '.code.toByte(), valid.size)
        assertEquals("通透", AppearanceSchemeFile.read(ByteArrayInputStream(padded)).name)
    }

    @Test fun aFailedStorageCommitDoesNotReportASuccessfulImport() {
        val prefs = preferences(commitSucceeds = false)
        val document = read(validFile().toString())
        assertNull(AppearancePresetStore.importScheme(prefs, document))
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun suggestedFileNamesCannotContainPathsOrControlCharacters() {
        assertEquals("日常 _ 深夜.json", AppearanceSchemeFile.fileName("日常 / 深夜"))
        assertEquals("a_b_c_d_e_f_g_h_i_j_k.json", AppearanceSchemeFile.fileName("a\\b/c:d*e?f\"g<h>i|j\nk"))
        assertEquals("外观配置.json", AppearanceSchemeFile.fileName("..."))
    }

    private fun read(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        .use { AppearanceSchemeFile.read(it) }

    private fun validFile(): JSONObject {
        val prefs = preferences()
        AppearanceProfiles.initialize(prefs)
        return JSONObject(AppearanceSchemeFile.encode(
            AppearancePresetStore.Scheme("local-id", "通透", AppearancePresetStore.capture(prefs))))
    }

    private fun expectRejected(action: () -> Any?) {
        try {
            action()
            fail("Expected an invalid appearance file to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }

    private fun preferences(commitSucceeds: Boolean = true): SharedPreferences {
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
                            "apply", "commit" -> {
                                if (action.name == "apply" || commitSucceeds) values.putAll(pending)
                                commitSucceeds
                            }
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
