package io.mo.xatype.compat

import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.zip.CRC32

/** Descriptors per symbol key, plus the reason each unresolved key missed. */
class FingerprintResult(val resolved: Map<String, List<String>>, val missing: Map<String, String>)

/** A fingerprint that matched no member, or more than one. */
internal class FingerprintMiss(message: String) : Exception(message)

internal fun <T> single(candidates: Collection<T>, what: String): T =
    candidates.singleOrNull() ?: throw FingerprintMiss("$what: ${candidates.size} candidates")

/** Runs fingerprints independently so one miss never hides the others. */
internal class FingerprintCollector {
    private val resolved = LinkedHashMap<String, List<String>>()
    private val missing = LinkedHashMap<String, String>()

    /** [block] returns one descriptor list per key, in [keys] order. */
    fun put(vararg keys: String, block: () -> List<List<String>>) {
        try {
            block().forEachIndexed { index, values -> resolved[keys[index]] = values }
        } catch (t: Throwable) {
            keys.forEach { missing[it] = (t as? FingerprintMiss)?.message ?: t.toString() }
        }
    }

    fun result() = FingerprintResult(resolved, missing)
}

/**
 * Obfuscated members located by fingerprint instead of by name.
 *
 * The scan runs once per installed APK; results (including misses) are cached
 * so later launches only resolve descriptors by reflection. A missing symbol
 * disables only the hook that needs it, which then falls back to the names
 * verified for earlier builds.
 */
open class SymbolTable(
    /** Bump whenever a fingerprint changes so stale caches are rescanned. */
    private val schema: Int,
    private val scan: (DexKitBridge, ClassLoader) -> FingerprintResult
) {
    private var classLoader: ClassLoader? = null
    private var symbols: Map<String, List<String>> = emptyMap()

    /**
     * @param apkPath the APK [loader] was created from; without it the dex is
     * read through the class loader and the result is not cached.
     * @param loadNative loads libdexkit; only called on a cache miss.
     * @return a one-line summary for the module log.
     */
    @Synchronized
    fun init(
        loader: ClassLoader,
        apkPath: String?,
        cacheFile: File?,
        moduleVersion: Long,
        loadNative: () -> Unit = { System.loadLibrary("dexkit") }
    ): String {
        classLoader = loader
        val cacheKey = apkPath?.let {
            val apk = File(it)
            "$moduleVersion/$schema/${apk.length()}/${apk.lastModified()}/${centralDirectoryCrc(apk)}"
        }
        if (cacheKey != null) {
            readCache(cacheFile, cacheKey)?.let {
                symbols = it
                return "fingerprints from cache: ${it.size} resolved"
            }
        }

        val started = System.nanoTime()
        val result = try {
            loadNative()
            val bridge = apkPath?.let { DexKitBridge.create(it) }
                ?: DexKitBridge.create(loader, false)
            bridge.use { scan(it, loader) }
        } catch (t: Throwable) {
            // Not cached: a transient failure (e.g. native load) retries next launch.
            symbols = emptyMap()
            return "fingerprint scan unavailable: $t"
        }
        symbols = result.resolved
        if (cacheKey != null) writeCache(cacheFile, cacheKey, result.resolved)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val missing = result.missing.entries.joinToString { "${it.key} (${it.value})" }
        return "fingerprint scan ${elapsed}ms: ${result.resolved.size} resolved" +
            if (missing.isEmpty()) "" else "; missing: $missing"
    }

    fun has(key: String): Boolean = symbols.containsKey(key)

    fun describe(): Map<String, List<String>> = symbols

    /** Raw entries of a key that stores values other than descriptors. */
    fun strings(key: String): List<String>? = symbols[key]

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

    /**
     * System apps keep a fixed mtime across OTA updates, so also hash the APK
     * tail: the zip central directory there lists every entry's CRC.
     */
    private fun centralDirectoryCrc(apk: File): Long = runCatching {
        RandomAccessFile(apk, "r").use { file ->
            val size = minOf(file.length(), 64L * 1024).toInt()
            val tail = ByteArray(size)
            file.seek(file.length() - size)
            file.readFully(tail)
            CRC32().apply { update(tail) }.value
        }
    }.getOrDefault(0L)

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
            // Several processes may scan concurrently; rename publishes atomically.
            val temp = File(file.parentFile, "${file.name}.${android.os.Process.myPid()}.tmp")
            temp.writeText(JSONObject().put("key", key).put("symbols", entries).toString())
            if (!temp.renameTo(file)) temp.delete()
        }
    }
}
