package io.mo.xatype.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogType(val displayName: String, val colorHex: String) {
    INIT("模块初始化", "#3B82F6"),
    AI_SAFETY("AI安全拦截", "#10B981"),
    VOICE_MODERATION("语音风控拦截", "#F59E0B"),
    CLOUD_BLACKLIST("黑名单清除", "#8B5CF6"),
    CLIPBOARD("剪贴板绕过", "#EC4899"),
    OS_VERSION("OS4限制解除", "#06B6D4"),
    STYLE("样式个性化", "#F43F5E");

    companion object {
        fun fromString(name: String): LogType {
            return try {
                valueOf(name)
            } catch (_: Throwable) {
                INIT
            }
        }
    }
}

data class LogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType,
    val title: String,
    val detail: String
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("timestamp", timestamp)
        obj.put("type", type.name)
        obj.put("title", title)
        obj.put("detail", detail)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): LogEntry? {
            return try {
                val obj = JSONObject(jsonStr)
                LogEntry(
                    id = obj.optLong("id", System.currentTimeMillis()),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    type = LogType.fromString(obj.optString("type", "INIT")),
                    title = obj.optString("title", ""),
                    detail = obj.optString("detail", "")
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
