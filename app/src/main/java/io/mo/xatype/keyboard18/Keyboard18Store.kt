package io.mo.xatype.keyboard18

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import io.mo.xatype.keyboard.ShuangpinScheme

object Keyboard18Store {
    const val ENABLED = "enabled" // Legacy migration only.
    const val SHOW_IN_PICKER = "show_in_picker"
    const val SHUANGPIN = "shuangpin"
    const val SCHEME = "shuangpin_scheme"
    const val REVISION = "revision"
    const val STATUS = "host_status"
    const val STATUS_TIME = "host_status_time"
    private fun device(context: Context) = context.createDeviceProtectedStorageContext()
    fun prefs(context: Context) = device(context).getSharedPreferences("keyboard18", Context.MODE_PRIVATE)
    fun setVisible(context: Context, visible: Boolean) {
        val sp = prefs(context)
        check(sp.edit().putBoolean(SHOW_IN_PICKER, visible).putLong(REVISION, sp.getLong(REVISION, 0) + 1)
            .remove(STATUS).remove(STATUS_TIME).commit()) { "无法保存18键设置" }
    }
    fun setShuangpin(context: Context, enabled: Boolean, scheme: ShuangpinScheme) {
        val sp = prefs(context)
        check(sp.edit().putBoolean(SHUANGPIN, enabled).putString(SCHEME, scheme.name)
            .putLong(REVISION, sp.getLong(REVISION, 0) + 1).remove(STATUS).remove(STATUS_TIME).commit()) {
            "无法保存18键双拼设置"
        }
    }
    fun scheme(settings: Bundle): ShuangpinScheme? = if (settings.getBoolean(SHUANGPIN))
        ShuangpinScheme.resolve(settings.getString(SCHEME)) else null
    fun snapshot(context: Context) = prefs(context).let { sp -> Bundle().apply {
        putBoolean(ENABLED, sp.getBoolean(ENABLED, false))
        putBoolean(SHUANGPIN, sp.getBoolean(SHUANGPIN, false))
        putString(SCHEME, ShuangpinScheme.resolve(sp.getString(SCHEME, null)).name)
        putBoolean(SHOW_IN_PICKER, sp.getBoolean(SHOW_IN_PICKER, sp.getBoolean(ENABLED, true)))
        putLong(REVISION, sp.getLong(REVISION, 0))
        putString(STATUS, sp.getString(STATUS, "尚未接入输入法"))
        putLong(STATUS_TIME, sp.getLong(STATUS_TIME, 0))
    } }
    private const val DICTIONARY_REVISION = 2

    @Synchronized
    fun dictionaryFile(context: Context): File {
        val baseFile = File(device(context).filesDir, "keyboard18-pinyin.table")
        val revisionFile = File(device(context).filesDir, "keyboard18-pinyin.revision")
        val revision = if (revisionFile.exists()) revisionFile.readText().trim().toIntOrNull() else null
        if (!baseFile.exists() || revision != DICTIONARY_REVISION) {
            val atomic = AtomicFile(baseFile)
            val stream = atomic.startWrite()
            try {
                // Merge the base corpus with the module supplement, keeping the higher weight.
                val merged = LinkedHashMap<String, Int>()
                for (asset in listOf("keyboard18/pinyin.table", "keyboard18/extra.table")) {
                    runCatching { context.assets.open(asset) }.getOrNull()?.use { input ->
                        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).forEachLine { line ->
                            val parts = line.split('\t')
                            if (parts.size == 3) {
                                val weight = parts[2].toIntOrNull() ?: 0
                                val key = parts[0] + "\t" + parts[1]
                                if (weight > (merged[key] ?: -1)) merged[key] = weight
                            }
                        }
                    }
                }
                val text = buildString {
                    merged.forEach { (key, weight) -> append(key).append('\t').append(weight).append('\n') }
                }.toByteArray(Charsets.UTF_8)
                val gzip = GZIPOutputStream(BufferedOutputStream(stream))
                gzip.write(text); gzip.finish(); gzip.flush()
                atomic.finishWrite(stream)
            } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
            revisionFile.writeText(DICTIONARY_REVISION.toString())
        }
        AtomicFile(baseFile).openRead().close()
        return baseFile
    }
    fun openDictionary(context: Context): ParcelFileDescriptor =
        ParcelFileDescriptor.open(dictionaryFile(context), ParcelFileDescriptor.MODE_READ_ONLY)
}
