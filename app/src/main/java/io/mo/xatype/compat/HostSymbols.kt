package io.mo.xatype.compat

import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Obfuscated host members located by [HostFingerprints] instead of by name.
 *
 * The scan runs once per installed host APK; results (including misses) are
 * cached so later launches only resolve descriptors by reflection. A missing
 * symbol disables only the hook that needs it, which then falls back to the
 * verified names in [TargetCompatibility].
 */
object HostSymbols {
    const val AI_SAFETY_PARSERS = "aiSafety.parsers"
    const val VOICE_MODERATION = "voice.moderation"
    const val ASR_ERROR_MAPPER = "asr.errorMapper"
    const val ASR_ERROR_CALLBACK = "asr.errorCallback"
    const val OS_VERSION_GATE = "os.versionGate"
    const val HEIGHT_RECT = "height.rect"
    const val HEIGHT_CLAMP = "height.clamp"
    const val HEIGHT_RECT_GETTER = "height.rectGetter"
    const val HEIGHT_LAYOUT = "height.layout"
    const val HEIGHT_DRAG = "height.drag"
    const val HEIGHT_DRAG_KIND = "height.dragKind"

    /** Bump whenever a fingerprint changes so stale caches are rescanned. */
    private const val SCHEMA = 1

    private var classLoader: ClassLoader? = null
    private var symbols: Map<String, List<String>> = emptyMap()

    /**
     * @param loadNative loads libdexkit; only called on a cache miss.
     * @return a one-line summary for the module log.
     */
    @Synchronized
    fun init(
        loader: ClassLoader,
        apkPath: String,
        cacheFile: File?,
        moduleVersion: Long,
        loadNative: () -> Unit = { System.loadLibrary("dexkit") }
    ): String {
        classLoader = loader
        val apk = File(apkPath)
        val cacheKey = "$moduleVersion/$SCHEMA/${apk.length()}/${apk.lastModified()}"
        readCache(cacheFile, cacheKey)?.let {
            symbols = it
            return "fingerprints from cache: ${it.size} resolved"
        }

        val started = System.nanoTime()
        val result = try {
            loadNative()
            DexKitBridge.create(apkPath).use { HostFingerprints.resolve(it, loader) }
        } catch (t: Throwable) {
            // Not cached: a transient failure (e.g. native load) retries next launch.
            symbols = emptyMap()
            return "fingerprint scan unavailable: $t"
        }
        symbols = result.resolved
        writeCache(cacheFile, cacheKey, result.resolved)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val missing = result.missing.entries.joinToString { "${it.key} (${it.value})" }
        return "fingerprint scan ${elapsed}ms: ${result.resolved.size} resolved" +
            if (missing.isEmpty()) "" else "; missing: $missing"
    }

    fun has(key: String): Boolean = symbols.containsKey(key)

    fun describe(): Map<String, List<String>> = symbols

    fun clazz(key: String): Class<*>? = symbols[key]?.singleOrNull()?.let {
        runCatching { DexClass(it).getInstance(loader()) }.getOrNull()
    }

    fun method(key: String): Method? = first(key) { DexMethod(it).getMethodInstance(loader()) }

    fun methods(key: String): List<Method> =
        symbols[key].orEmpty().mapNotNull { resolve { DexMethod(it).getMethodInstance(loader()) } }

    fun field(key: String): Field? = first(key) { DexField(it).getFieldInstance(loader()) }

    private fun loader() = checkNotNull(classLoader)

    private fun <T : AccessibleObject> first(key: String, block: (String) -> T): T? =
        symbols[key]?.singleOrNull()?.let { resolve { block(it) } }

    private fun <T : AccessibleObject> resolve(block: () -> T): T? =
        runCatching { block().apply { isAccessible = true } }.getOrNull()

    private fun readCache(file: File?, key: String): Map<String, List<String>>? = runCatching {
        if (file == null || !file.isFile) return null
        val json = JSONObject(file.readText())
        if (json.getString("key") != key) return null
        val entries = json.getJSONObject("symbols")
        entries.keys().asSequence().associateWith { name ->
            val values = entries.getJSONArray(name)
            List(values.length()) { values.getString(it) }
        }
    }.getOrNull()

    private fun writeCache(file: File?, key: String, resolved: Map<String, List<String>>) {
        if (file == null) return
        runCatching {
            val entries = JSONObject()
            resolved.forEach { (name, values) -> entries.put(name, JSONArray(values)) }
            file.parentFile?.mkdirs()
            // Several host processes may scan concurrently; rename publishes atomically.
            val temp = File(file.parentFile, "${file.name}.${android.os.Process.myPid()}.tmp")
            temp.writeText(JSONObject().put("key", key).put("symbols", entries).toString())
            if (!temp.renameTo(file)) temp.delete()
        }
    }
}
