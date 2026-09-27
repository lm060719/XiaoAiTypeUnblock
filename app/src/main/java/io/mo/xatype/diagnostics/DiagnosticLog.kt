package io.mo.xatype.diagnostics

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One bounded session; snapshots are flushed and copied under the same lock as writes. */
object DiagnosticLog {
    const val MAX_BYTES = 8 * 1024 * 1024
    @Volatile var status = "尚未开始采集；上次日志可直接导出"
        private set
    @Volatile var running = false
        private set
    private var writer: BufferedWriter? = null
    private var bytes = 0
    private var generation = 0L

    private fun logFile(context: Context) = File(context.filesDir, "input-diagnostic.txt")

    fun begin(context: Context): Long = begin(logFile(context))

    @Synchronized internal fun begin(file: File): Long {
        writer?.close()
        generation++
        writer = file.bufferedWriter()
        bytes = 0
        running = true
        status = "正在请求 Root 权限…"
        return generation
    }

    @Synchronized fun append(token: Long, text: String): Boolean {
        if (token != generation || writer == null) return false
        val line = text.take(16_384) + "\n"
        val size = line.toByteArray(Charsets.UTF_8).size
        if (bytes + size > MAX_BYTES) {
            finish(token, "日志已达到 8 MB，已自动停止")
            return false
        }
        writer!!.write(line)
        writer!!.flush()
        bytes += size
        return true
    }

    @Synchronized fun update(token: Long, message: String) {
        if (token == generation && running) status = message
    }

    @Synchronized fun finish(token: Long, message: String) {
        if (token != generation || !running) return
        writer?.apply {
            write("\n[collector] $message\n")
            close()
        }
        writer = null
        running = false
        status = message
    }

    fun snapshot(context: Context): File = snapshot(logFile(context), File(context.cacheDir, "diagnostics"))

    @Synchronized internal fun snapshot(source: File, directory: File): File {
        writer?.flush()
        check(source.exists()) { "请先开始采集并复现问题" }
        directory.mkdirs()
        // Only files owned by this exporter, kept for 24 hours for delayed share targets.
        directory.listFiles()?.filter {
            it.isFile && it.name.startsWith("xiaotype-") &&
                System.currentTimeMillis() - it.lastModified() > 86_400_000L
        }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return source.copyTo(File(directory, "xiaotype-$stamp.txt"))
    }
}
