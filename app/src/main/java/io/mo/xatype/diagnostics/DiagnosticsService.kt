package io.mo.xatype.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.mo.xatype.BuildConfig
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticsService : Service() {
    private val cancelled = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    private var token = 0L
    private var started = false
    private val deadline = Runnable {
        finish("采集已满 10 分钟，已自动停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        started = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("input-diagnostic", "输入诊断", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, DiagnosticsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(1201, Notification.Builder(this, "input-diagnostic")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("小爱输入法诊断采集中")
            .setContentText("复现后点击返回，停止并导出日志；最长 10 分钟")
            .setContentIntent(open).setOngoing(true).build())
        token = DiagnosticLog.begin(this)
        handler.postDelayed(deadline, 10 * 60_000L)
        Thread({ collect() }, "input-diagnostic").start()
        return START_NOT_STICKY
    }

    private fun append(text: String) {
        if (cancelled.get()) return
        if (!DiagnosticLog.append(token, text)) {
            cancelled.set(true)
            process?.destroy()
            stopSelf()
        }
    }

    private fun launch(command: String): Process {
        check(!cancelled.get()) { "采集已停止" }
        val child = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process = child
        // Cover a stop racing with ProcessBuilder.start().
        if (cancelled.get()) {
            child.destroyForcibly()
            error("采集已停止")
        }
        return child
    }

    /** Drain concurrently so a child cannot deadlock on a full stdout pipe. */
    private fun rootCommand(command: String, timeoutSeconds: Long): String {
        val child = launch(command)
        val output = StringBuilder()
        val reader = Thread {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < 32_768) output.appendLine(line.take(4096))
                        }
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        try {
            check(child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Root 命令超时，请确认已授权" }
            reader.join(2000)
            val text = synchronized(output) { output.toString() }
            check(child.exitValue() == 0) { "Root 命令失败 (${child.exitValue()}): ${text.take(1000)}" }
            return text
        } finally {
            child.destroyForcibly()
            if (process === child) process = null
        }
    }

    private fun collect() {
        try {
            append("XiaoAiTypeUnblock input diagnostic / ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            append("Started: ${Date()}\nManufacturer=${Build.MANUFACTURER} Model=${Build.MODEL} Device=${Build.DEVICE}")
            append("Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} SecurityPatch=${Build.VERSION.SECURITY_PATCH}")
            append("Build=${Build.DISPLAY}\nFingerprint=${Build.FINGERPRINT}\nABIs=${Build.SUPPORTED_ABIS.joinToString()}")
            val ime = packageManager.getPackageInfo("com.xiaomi.type", 0)
            val imeUid = ime.applicationInfo!!.uid
            val moduleUid = android.os.Process.myUid()
            val user = moduleUid / 100_000
            @Suppress("DEPRECATION")
            val imeVersion = if (Build.VERSION.SDK_INT >= 28) ime.longVersionCode else ime.versionCode.toLong()
            append("IME=${ime.versionName} ($imeVersion) uid=$imeUid user=$user")
            append("IME nativeLibraryDir=${ime.applicationInfo!!.nativeLibraryDir}")
            val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, MODE_PRIVATE)
            val keys = listOf(ConfigManager.KEY_OS_VERSION_UNBLOCK, ConfigManager.KEY_AI_SAFETY,
                ConfigManager.KEY_VOICE_MODERATION, ConfigManager.KEY_CLOUD_BLACKLIST,
                ConfigManager.KEY_CLIPBOARD_SENSITIVE, ConfigManager.KEY_CLIPBOARD_PERMANENT,
                ConfigManager.KEY_VERBOSE_LOG, ConfigManager.KEY_STYLE_ENABLED)
            keys.forEach { append("Config $it=${prefs.getBoolean(it, false)}") }
            val identity = rootCommand("id -u", 90).trim()
            check(identity == "0") { "未获得 Root 权限：$identity" }
            append("Root granted")
            append(rootCommand("getprop ro.mi.os.version.code; getprop ro.mi.os.version.name; " +
                "getprop ro.build.version.incremental; getconf PAGESIZE; " +
                "settings get global device_provisioned; settings --user $user get secure user_setup_complete; " +
                "settings --user $user get secure default_input_method", 15).let {
                "[real OS code / OS name / incremental / page size / provisioned / setup / default IME]\n$it"
            })
            // Capture from before restart; Android log buffers retain startup messages while su starts.
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            append("[collector] Restarting com.xiaomi.type at $since")
            rootCommand("am force-stop --user $user com.xiaomi.type", 15)
            if (cancelled.get()) return
            // Also bound the privileged child if Android kills our app without onDestroy().
            val child = launch("exec timeout 600 logcat -b main -b system -b crash -v threadtime " +
                "--uid=$imeUid,$moduleUid -T '$since' '*:V'")
            DiagnosticLog.update(token, "正在采集，请切到聊天或记事本输入 nihao；完成后返回停止并导出")
            child.inputStream.bufferedReader().use { reader ->
                while (!cancelled.get()) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
            if (!cancelled.get()) {
                append("[collector] logcat exited")
                finish("日志采集进程已退出，请导出日志检查原因")
            }
        } catch (e: Exception) {
            if (!cancelled.get()) {
                append("[collector error] ${e.stackTraceToString()}")
                finish("采集失败：${e.message ?: e.javaClass.simpleName}；可导出错误详情")
            }
        } finally {
            process?.destroyForcibly()
        }
    }

    private fun finish(message: String) {
        cancelled.set(true)
        process?.destroyForcibly()
        DiagnosticLog.finish(token, message)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(deadline)
        cancelled.set(true)
        process?.destroyForcibly()
        DiagnosticLog.finish(token, "采集已停止，可导出日志")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
