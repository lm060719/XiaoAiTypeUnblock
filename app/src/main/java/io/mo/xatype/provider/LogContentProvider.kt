package io.mo.xatype.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.mo.xatype.data.LogEntry
import io.mo.xatype.data.LogType
import org.json.JSONArray
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

class LogContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.mo.xatype.logprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_RECORD = "record"
        const val METHOD_GET_LOGS = "get_logs"
        const val METHOD_CLEAR = "clear"
        const val METHOD_GET_CONFIG = "get_config"

        const val EXTRA_LOG_JSON = "log_json"
        const val EXTRA_LOGS_LIST = "logs_list"
        const val EXTRA_AI_COUNT = "ai_count"
        const val EXTRA_VOICE_COUNT = "voice_count"
        const val EXTRA_BLACKLIST_COUNT = "blacklist_count"
        const val EXTRA_CLIPBOARD_COUNT = "clipboard_count"
        const val EXTRA_OS_VERSION_COUNT = "os_version_count"
        const val EXTRA_STYLE_COUNT = "style_count"

        private const val MAX_LOGS = 200
        private const val PREFS_PERSISTENT_LOGS = "persistent_logs"
        private const val KEY_SAVED_LOGS = "saved_logs_json"
        const val BG_IMAGE_FILENAME = "bg_image.png"

        private val logsDeque = ConcurrentLinkedDeque<LogEntry>()
        private val aiSafetyCounter = AtomicInteger(0)
        private val voiceModerationCounter = AtomicInteger(0)
        private val cloudBlacklistCounter = AtomicInteger(0)
        private val clipboardCounter = AtomicInteger(0)
        private val osVersionCounter = AtomicInteger(0)
        private val styleCounter = AtomicInteger(0)
    }

    override fun onCreate(): Boolean {
        loadPersistedLogs()
        return true
    }

    private fun loadPersistedLogs() {
        val ctx = context ?: return
        try {
            val sp = ctx.getSharedPreferences(PREFS_PERSISTENT_LOGS, Context.MODE_PRIVATE)
            val jsonStr = sp.getString(KEY_SAVED_LOGS, null) ?: return
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val entry = LogEntry.fromJson(arr.getString(i))
                if (entry != null) {
                    logsDeque.add(entry)
                    when (entry.type) {
                        LogType.AI_SAFETY -> aiSafetyCounter.incrementAndGet()
                        LogType.VOICE_MODERATION -> voiceModerationCounter.incrementAndGet()
                        LogType.CLOUD_BLACKLIST -> cloudBlacklistCounter.incrementAndGet()
                        LogType.CLIPBOARD -> clipboardCounter.incrementAndGet()
                        LogType.OS_VERSION -> osVersionCounter.incrementAndGet()
                        LogType.STYLE -> styleCounter.incrementAndGet()
                        else -> {}
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun savePersistedLogs() {
        val ctx = context ?: return
        try {
            val sp = ctx.getSharedPreferences(PREFS_PERSISTENT_LOGS, Context.MODE_PRIVATE)
            val arr = JSONArray()
            val list = logsDeque.toList()
            val saveCount = Math.min(list.size, 50)
            for (i in (list.size - saveCount) until list.size) {
                arr.put(list[i].toJson())
            }
            sp.edit().putString(KEY_SAVED_LOGS, arr.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = Bundle()
        when (method) {
            METHOD_RECORD -> {
                val json = extras?.getString(EXTRA_LOG_JSON) ?: arg
                val typeStr = extras?.getString("type")
                val title = extras?.getString("title")
                val detail = extras?.getString("detail")

                val entry: LogEntry? = if (!json.isNullOrEmpty() && json.startsWith("{")) {
                    LogEntry.fromJson(json)
                } else if (!typeStr.isNullOrEmpty() && !title.isNullOrEmpty()) {
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        type = LogType.fromString(typeStr),
                        title = title,
                        detail = detail ?: ""
                    )
                } else {
                    null
                }

                if (entry != null) {
                    logsDeque.add(entry)
                    while (logsDeque.size > MAX_LOGS) {
                        logsDeque.poll()
                    }
                    when (entry.type) {
                        LogType.AI_SAFETY -> aiSafetyCounter.incrementAndGet()
                        LogType.VOICE_MODERATION -> voiceModerationCounter.incrementAndGet()
                        LogType.CLOUD_BLACKLIST -> cloudBlacklistCounter.incrementAndGet()
                        LogType.CLIPBOARD -> clipboardCounter.incrementAndGet()
                        LogType.OS_VERSION -> osVersionCounter.incrementAndGet()
                        LogType.STYLE -> styleCounter.incrementAndGet()
                        else -> {}
                    }
                    savePersistedLogs()
                }
                result.putBoolean("success", true)
            }
            METHOD_GET_LOGS -> {
                val list = ArrayList<String>()
                // Return newest first
                val currentLogs = logsDeque.toList().reversed()
                for (entry in currentLogs) {
                    list.add(entry.toJson())
                }
                result.putStringArrayList(EXTRA_LOGS_LIST, list)
                result.putInt(EXTRA_AI_COUNT, aiSafetyCounter.get())
                result.putInt(EXTRA_VOICE_COUNT, voiceModerationCounter.get())
                result.putInt(EXTRA_BLACKLIST_COUNT, cloudBlacklistCounter.get())
                result.putInt(EXTRA_CLIPBOARD_COUNT, clipboardCounter.get())
                result.putInt(EXTRA_OS_VERSION_COUNT, osVersionCounter.get())
                result.putInt(EXTRA_STYLE_COUNT, styleCounter.get())
            }
            METHOD_CLEAR -> {
                logsDeque.clear()
                aiSafetyCounter.set(0)
                voiceModerationCounter.set(0)
                cloudBlacklistCounter.set(0)
                clipboardCounter.set(0)
                osVersionCounter.set(0)
                styleCounter.set(0)
                savePersistedLogs()
                result.putBoolean("success", true)
            }
            METHOD_GET_CONFIG -> {
                val ctx = context
                if (ctx != null) {
                    val sp = ctx.getSharedPreferences(io.mo.xatype.config.ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_AI_SAFETY, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_AI_SAFETY, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_VOICE_MODERATION, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_VOICE_MODERATION, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_CLOUD_BLACKLIST, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_CLOUD_BLACKLIST, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_CLIPBOARD_SENSITIVE, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_CLIPBOARD_SENSITIVE, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_OS_VERSION_UNBLOCK, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_OS_VERSION_UNBLOCK, true))
                    result.putBoolean(io.mo.xatype.config.ConfigManager.KEY_STYLE_ENABLED, sp.getBoolean(io.mo.xatype.config.ConfigManager.KEY_STYLE_ENABLED, true))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_CORNER_RADIUS, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_CORNER_RADIUS, 16))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_OPACITY, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_OPACITY, 85))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_BLUR_RADIUS, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_BLUR_RADIUS, 50))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_BG_TYPE, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_BG_TYPE, 0))
                    result.putString(io.mo.xatype.config.ConfigManager.KEY_BG_COLOR, sp.getString(io.mo.xatype.config.ConfigManager.KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E")
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_MARGIN_TOP, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_MARGIN_TOP, 0))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_MARGIN_BOTTOM, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_MARGIN_BOTTOM, 0))
                    result.putInt(io.mo.xatype.config.ConfigManager.KEY_MARGIN_HORIZONTAL, sp.getInt(io.mo.xatype.config.ConfigManager.KEY_MARGIN_HORIZONTAL, 0))
                    result.putLong(io.mo.xatype.config.ConfigManager.KEY_BG_IMAGE_VERSION, sp.getLong(io.mo.xatype.config.ConfigManager.KEY_BG_IMAGE_VERSION, 0L))
                }
            }
        }
        return result
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val ctx = context ?: return null
        val file = java.io.File(ctx.filesDir, BG_IMAGE_FILENAME)
        if (file.exists() && file.canRead()) {
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
