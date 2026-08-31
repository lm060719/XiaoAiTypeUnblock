package io.mo.xatype.util

import android.content.Context
import android.os.Bundle
import io.mo.xatype.data.LogEntry
import io.mo.xatype.data.LogType
import io.mo.xatype.provider.LogContentProvider
import java.util.concurrent.Executors

object LogBridge {
    private val executor = Executors.newSingleThreadExecutor()

    fun record(type: LogType, title: String, detail: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            type = type,
            title = title,
            detail = detail
        )

        executor.execute {
            try {
                val context = getAppContext()
                if (context != null) {
                    val bundle = Bundle().apply {
                        putString(LogContentProvider.EXTRA_LOG_JSON, entry.toJson())
                        putString("type", type.name)
                        putString("title", title)
                        putString("detail", detail)
                    }
                    context.contentResolver.call(
                        LogContentProvider.CONTENT_URI,
                        LogContentProvider.METHOD_RECORD,
                        null,
                        bundle
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun getAppContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }
}
