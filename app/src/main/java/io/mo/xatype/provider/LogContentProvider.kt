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

        const val EXTRA_LOG_JSON = "log_json"
        const val EXTRA_LOGS_LIST = "logs_list"
        const val EXTRA_AI_COUNT = "ai_count"
        const val EXTRA_VOICE_COUNT = "voice_count"
        const val EXTRA_BLACKLIST_COUNT = "blacklist_count"
        const val EXTRA_CLIPBOARD_COUNT = "clipboard_count"

        private const val MAX_LOGS = 200
        private const val PREFS_PERSISTENT_LOGS = "persistent_logs"
        private const val KEY_SAVED_LOGS = "saved_logs_json"

        private val logsDeque = ConcurrentLinkedDeque<LogEntry>()
        private val aiSafetyCounter = AtomicInteger(0)
        private val voiceModerationCounter = AtomicInteger(0)
        private val cloudBlacklistCounter = AtomicInteger(0)
        private val clipboardCounter = AtomicInteger(0)
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
            }
            METHOD_CLEAR -> {
                logsDeque.clear()
                aiSafetyCounter.set(0)
                voiceModerationCounter.set(0)
                cloudBlacklistCounter.set(0)
                clipboardCounter.set(0)
                savePersistedLogs()
                result.putBoolean("success", true)
            }
        }
        return result
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
