package io.mo.xatype.dict

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.File

/**
 * Module-owned state for the online word list. The keyboard process never reads the network: it
 * asks the module's provider for the current [REVISION] and applies the word file when it changed.
 */
object OnlineDictStore {
    const val PREFS = "online_dict"
    const val SOURCE = "source"
    const val VERSION = "version"
    const val LABEL = "version_label"
    const val COUNT = "word_count"
    const val MAX_WORDS = "max_words"
    const val ACTION = "action"
    const val REVISION = "revision"
    const val AUTO_UPDATE = "auto_update"
    const val HOST_REVISION = "host_revision"
    const val HOST_STATUS = "host_status"
    const val HOST_TIME = "host_time"
    const val HOST_IMPORTED = "host_imported"
    const val LATEST_VERSION = "latest_version"
    const val LATEST_LABEL = "latest_label"
    const val CHECK_TIME = "check_time"

    const val ACTION_IMPORT = "import"
    const val ACTION_CLEAR = "clear"

    /** Host user-word type of ordinary words; imported words use it, so they behave like typed ones. */
    const val WORD_TYPE = 0

    /** The host user dictionary holds about 16 000 words in total, shared with the user's own words. */
    val CAPS = intArrayOf(3_000, 5_000, 8_000)
    const val DEFAULT_CAP = 5_000

    /** Older builds offered 30 000+; anything not offered now falls back to the default. */
    fun maxWords(sp: SharedPreferences): Int = sp.getInt(MAX_WORDS, DEFAULT_CAP).takeIf { it in CAPS } ?: DEFAULT_CAP

    private fun deviceContext(context: Context): Context = context.createDeviceProtectedStorageContext()
    fun prefs(context: Context): SharedPreferences =
        deviceContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wordsFile(context: Context) = AtomicFile(File(deviceContext(context).filesDir, "online-userwords.txt"))
    fun archiveFile(context: Context) = File(deviceContext(context).cacheDir, "online-dict.zip")

    @Synchronized
    fun snapshot(context: Context): Bundle {
        val sp = prefs(context)
        return Bundle().apply {
            putString(SOURCE, sp.getString(SOURCE, "").orEmpty())
            putString(VERSION, sp.getString(VERSION, "").orEmpty())
            putString(LABEL, sp.getString(LABEL, "").orEmpty())
            putInt(COUNT, sp.getInt(COUNT, 0))
            putInt(MAX_WORDS, maxWords(sp))
            putString(ACTION, sp.getString(ACTION, "").orEmpty())
            putLong(REVISION, sp.getLong(REVISION, 0))
            putBoolean(AUTO_UPDATE, sp.getBoolean(AUTO_UPDATE, false))
            putLong(HOST_REVISION, sp.getLong(HOST_REVISION, 0))
            putString(HOST_STATUS, sp.getString(HOST_STATUS, "").orEmpty())
            putLong(HOST_TIME, sp.getLong(HOST_TIME, 0))
            putInt(HOST_IMPORTED, sp.getInt(HOST_IMPORTED, 0))
            putString(LATEST_VERSION, sp.getString(LATEST_VERSION, "").orEmpty())
            putString(LATEST_LABEL, sp.getString(LATEST_LABEL, "").orEmpty())
            putLong(CHECK_TIME, sp.getLong(CHECK_TIME, 0))
        }
    }

    fun setMaxWords(context: Context, value: Int) {
        check(prefs(context).edit().putInt(MAX_WORDS, value).commit()) { "无法保存设置" }
    }

    fun setAutoUpdate(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean(AUTO_UPDATE, value).commit()) { "无法保存设置" }
    }

    fun saveCheck(context: Context, release: OnlineDictClient.Release) {
        prefs(context).edit().putString(LATEST_VERSION, release.version).putString(LATEST_LABEL, release.label)
            .putLong(CHECK_TIME, System.currentTimeMillis()).apply()
    }

    /** Queues [words] from [source]; the host replaces whatever the previous import added. */
    @Synchronized
    fun install(context: Context, source: OnlineDictSource, release: OnlineDictClient.Release,
                words: List<RimeWordExtractor.Entry>) {
        require(words.isNotEmpty()) { "没有可导入的词条" }
        val file = wordsFile(context)
        val stream = file.startWrite()
        try {
            // One line per word, " " separated, exactly what the host's user-word parser reads.
            stream.bufferedWriter(Charsets.UTF_8).apply {
                words.forEach { write(it.word); write(" $WORD_TYPE\n") }
                flush()
            }
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            throw t
        }
        val sp = prefs(context)
        check(sp.edit().putString(SOURCE, source.id).putString(VERSION, release.version)
            .putString(LABEL, release.label).putInt(COUNT, words.size)
            .putString(ACTION, ACTION_IMPORT).putLong(REVISION, sp.getLong(REVISION, 0) + 1)
            .putString(LATEST_VERSION, release.version).putString(LATEST_LABEL, release.label)
            .remove(HOST_STATUS).remove(HOST_TIME).commit()) { "无法保存导入请求" }
    }

    /** Queues the already downloaded word file again, e.g. after a failed or abandoned import. */
    @Synchronized
    fun requeue(context: Context) {
        val sp = prefs(context)
        check(hasWords(context) && sp.getString(SOURCE, "").orEmpty().isNotEmpty()) { "没有已下载的词库，请重新下载" }
        check(sp.edit().putString(ACTION, ACTION_IMPORT).putLong(REVISION, sp.getLong(REVISION, 0) + 1)
            .remove(HOST_STATUS).remove(HOST_TIME).commit()) { "无法保存导入请求" }
    }

    /** Queues removal of everything this feature imported. User-typed words are not touched. */
    @Synchronized
    fun requestClear(context: Context) {
        wordsFile(context).delete()
        val sp = prefs(context)
        check(sp.edit().putString(SOURCE, "").putString(VERSION, "").putString(LABEL, "").putInt(COUNT, 0)
            .putString(ACTION, ACTION_CLEAR).putLong(REVISION, sp.getLong(REVISION, 0) + 1)
            .remove(HOST_STATUS).remove(HOST_TIME).commit()) { "无法保存清除请求" }
    }

    /** Streams the queued word file to the keyboard process, avoiding the Binder size limit. */
    @Synchronized
    fun openWords(context: Context): ParcelFileDescriptor {
        val file = wordsFile(context)
        file.openRead().close()
        return ParcelFileDescriptor.open(file.baseFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun hasWords(context: Context) = wordsFile(context).baseFile.isFile

    /** Ignores results for an older request, so a slow import cannot overwrite a newer status. */
    fun report(context: Context, extras: Bundle?) {
        extras ?: return
        val sp = prefs(context)
        if (extras.getLong(REVISION, -1) != sp.getLong(REVISION, 0)) return
        sp.edit().putLong(HOST_REVISION, extras.getLong(REVISION))
            .putString(HOST_STATUS, extras.getString(HOST_STATUS).orEmpty().take(300))
            .putInt(HOST_IMPORTED, extras.getInt(HOST_IMPORTED, 0))
            .putLong(HOST_TIME, System.currentTimeMillis()).apply()
    }
}
