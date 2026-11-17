package io.mo.xatype.config

import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream

/** A single portable scheme, independent of device IDs and unrelated module settings. */
object AppearanceSchemeFile {
    const val MAX_FILE_BYTES = 64 * 1024
    private const val FORMAT = "io.mo.xatype.appearance-scheme"
    private const val VERSION = 1
    private val color = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    private val switches = mapOf(
        "enabled" to false, "scrimEnabled" to true,
        "followSystem" to false, "manualDark" to false
    )

    data class Document(val name: String, val snapshot: JSONObject)

    fun encode(scheme: AppearancePresetStore.Scheme): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("name", scheme.name)
        .put("snapshot", validateSnapshot(scheme.snapshot))
        .toString(2)

    /** Bounds the read even when a document provider reports no size or an incorrect size. */
    fun read(input: InputStream): Document {
        val bytes = ByteArray(MAX_FILE_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        require(size <= MAX_FILE_BYTES) { "配置文件过大，请选择单条外观配置（不超过 64 KB）" }
        val text = String(bytes, 0, size, Charsets.UTF_8).removePrefix("\uFEFF")
        val json = try {
            val tokens = JSONTokener(text)
            val value = tokens.nextValue()
            require(value is JSONObject && tokens.nextClean() == '\u0000')
            value
        } catch (e: Exception) {
            throw IllegalArgumentException("文件不是有效的外观配置 JSON", e)
        }
        require(json.opt("format") == FORMAT) { "请选择本应用导出的单条外观配置" }
        require(json.opt("version") == VERSION) { "暂不支持此配置文件版本，请更新应用后重试" }
        val name = (json.opt("name") as? String)?.trim().orEmpty()
        require(name.isNotBlank() && name.length <= AppearancePresetStore.MAX_NAME_LENGTH) {
            "配置名称须为 1–30 个字符"
        }
        val snapshot = json.optJSONObject("snapshot")
            ?: throw IllegalArgumentException("配置文件缺少外观数据")
        return Document(name, validateSnapshot(snapshot))
    }

    /** Validate all known values before saving; discard unknown and device-local keys. */
    internal fun validateSnapshot(snapshot: JSONObject): JSONObject {
        require(snapshot.opt("version") == AppearancePresetStore.SNAPSHOT_VERSION) {
            "暂不支持此外观数据版本，请更新应用后重试"
        }
        val result = JSONObject().put("version", AppearancePresetStore.SNAPSHOT_VERSION)
        for (profile in listOf("light", "dark")) {
            val values = snapshot.optJSONObject(profile)
                ?: throw IllegalArgumentException("配置文件缺少浅色或深色外观")
            val clean = JSONObject()
            for (key in AppearanceProfiles.portableKeys) {
                val default = AppearanceProfiles.defaultValue(key)
                val value = if (values.has(key)) values.get(key) else default
                when (default) {
                    is Int -> {
                        require(value is Int) { "外观配置中的数值格式有误" }
                        val range = when (key) {
                            ConfigManager.KEY_CORNER_RADIUS -> 0..40
                            ConfigManager.KEY_OPACITY -> 0..100
                            ConfigManager.KEY_BLUR_RADIUS -> 20..100
                            ConfigManager.KEY_BG_TYPE -> 0..1
                            else -> (ConfigSchema[key] as? ConfigSchema.Int32)?.range
                        }
                        require(range == null || value in range) { "外观配置中的数值超出范围" }
                    }
                    is Long -> require(value is Int || value is Long) { "外观配置中的数值格式有误" }
                    is String -> {
                        require(value is String) { "外观配置中的颜色或模式格式有误" }
                        if (key == ConfigManager.KEY_APPEARANCE_MODE) {
                            require(value == "" || value == AppearancePresetStore.OFFICIAL) {
                                "外观配置包含不支持的外观模式"
                            }
                        } else {
                            require(value.isEmpty() || color.matches(value)) { "外观配置中的颜色格式有误" }
                        }
                    }
                }
                clean.put(key, value)
            }
            result.put(profile, clean)
        }
        for ((key, default) in switches) {
            val value = if (snapshot.has(key)) snapshot.get(key) else default
            require(value is Boolean) { "外观配置中的开关格式有误" }
            result.put(key, value)
        }
        return result
    }

    fun fileName(name: String): String = name.map {
        if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it
    }.joinToString("").trim().trim('.').ifBlank { "外观配置" } + ".json"
}
