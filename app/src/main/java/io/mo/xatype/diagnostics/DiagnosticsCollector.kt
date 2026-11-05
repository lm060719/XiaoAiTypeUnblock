package io.mo.xatype.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.mo.xatype.BuildConfig
import io.mo.xatype.config.AppearanceProfiles
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.util.ImeRestartScript
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The app writes the device and config header, then hands the log file to a detached root logcat.
 * The capture keeps running while the user reproduces in the IME, even if this process is killed;
 * its process group is persisted so a later process can still stop it.
 */
object DiagnosticsCollector {
    const val MAX_MILLIS = 10 * 60_000L
    private const val PREFS_NAME = "input_diagnostic"
    private const val KEY_GROUP = "capture_group"
    private const val KEY_STARTED = "capture_started"
    private const val RUNNING_STATUS = "正在采集，请呼出键盘复现外观或输入问题；完成后回到这里停止并导出"

    @Volatile private var token = 0L
    @Volatile private var setupProcess: Process? = null
    private val cancelled = AtomicLong(0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(context: Context) {
        val app = context.applicationContext
        if (DiagnosticLog.running) return
        val previous = prefs(app).getInt(KEY_GROUP, 0)
        prefs(app).edit().clear().commit()
        val session = DiagnosticLog.begin(app)
        token = session
        Thread({ collect(app, session, previous) }, "input-diagnostic").start()
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        val session = token
        // Set before reading the group: collect() saves the group first, then checks this.
        cancelled.set(session)
        setupProcess?.destroyForcibly()
        Thread({
            val group = prefs(app).getInt(KEY_GROUP, 0)
            if (group > 0) runCatching { rootCommand(RootLogcatScript.stop(group), 15, track = false) }
            prefs(app).edit().clear().commit()
            DiagnosticLog.finish(session, "采集已停止，可导出日志")
        }, "input-diagnostic-stop").start()
    }

    /** Wall-clock start of the running root capture, or 0 while none is running yet. */
    fun startedAt(context: Context): Long = prefs(context.applicationContext).let {
        if (it.getInt(KEY_GROUP, 0) == 0) 0L else it.getLong(KEY_STARTED, 0L)
    }

    /** Polled by the screen: adopts a capture from an earlier process and notices when it ended. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val group = prefs.getInt(KEY_GROUP, 0)
        if (group == 0) return
        val file = DiagnosticLog.logFile(app)
        if (!DiagnosticLog.running) token = DiagnosticLog.adopt(file, RUNNING_STATUS)
        val started = prefs.getLong(KEY_STARTED, 0L)
        val now = System.currentTimeMillis()
        val marked = endMarked(file)
        val message = when {
            marked -> "采集已结束（达到 10 分钟或 8 MB 上限），可导出日志"
            started < now - SystemClock.elapsedRealtime() -> "设备已重启，采集已结束，可导出日志"
            now - started > MAX_MILLIS + 10_000L -> "采集已结束，可导出日志"
            else -> return
        }
        prefs.edit().clear().apply()
        DiagnosticLog.finish(token, message, note = !marked)
    }

    private fun endMarked(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val tail = ByteArray(minOf(raf.length(), 256L).toInt())
            raf.seek(raf.length() - tail.size)
            raf.readFully(tail)
            String(tail, Charsets.UTF_8).contains(RootLogcatScript.END_MARKER)
        }
    }.getOrDefault(false)

    private fun collect(app: Context, session: Long, previousGroup: Int) {
        fun append(text: String) = check(DiagnosticLog.append(session, text)) { "采集已停止" }
        try {
            append("XiaoAiTypeUnblock input diagnostic / ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            append("Started: ${Date()}\nManufacturer=${Build.MANUFACTURER} Model=${Build.MODEL} Device=${Build.DEVICE}")
            append("Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} SecurityPatch=${Build.VERSION.SECURITY_PATCH}")
            append("Build=${Build.DISPLAY}\nFingerprint=${Build.FINGERPRINT}\nABIs=${Build.SUPPORTED_ABIS.joinToString()}")
            val ime = app.packageManager.getPackageInfo(ImeRestartScript.IME_PACKAGE, 0)
            val imeUid = ime.applicationInfo!!.uid
            val moduleUid = android.os.Process.myUid()
            val user = moduleUid / 100_000
            @Suppress("DEPRECATION")
            val imeVersion = if (Build.VERSION.SDK_INT >= 28) ime.longVersionCode else ime.versionCode.toLong()
            append("IME=${ime.versionName} ($imeVersion) uid=$imeUid user=$user")
            append("IME nativeLibraryDir=${ime.applicationInfo!!.nativeLibraryDir}")
            val prefs = app.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            val keys = listOf(ConfigManager.KEY_OS_VERSION_UNBLOCK, ConfigManager.KEY_AI_SAFETY,
                ConfigManager.KEY_VOICE_MODERATION, ConfigManager.KEY_CLOUD_BLACKLIST,
                ConfigManager.KEY_CLIPBOARD_SENSITIVE, ConfigManager.KEY_CLIPBOARD_PERMANENT,
                ConfigManager.KEY_SCREENSHOT_CLIPBOARD,
                ConfigManager.KEY_VERBOSE_LOG, ConfigManager.KEY_STYLE_ENABLED)
            keys.forEach { append("Config $it=${prefs.getBoolean(it, false)}") }
            val systemDark = (app.resources.configuration.uiMode and 48) == 32
            val style = AppearanceProfiles.selected(prefs, systemDark)
            append("Appearance followSystem=${prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false)} " +
                "manualDark=${prefs.getBoolean(AppearanceProfiles.MANUAL_DARK, false)} systemDark=$systemDark")
            listOf(ConfigManager.KEY_CORNER_RADIUS, ConfigManager.KEY_OPACITY,
                ConfigManager.KEY_BLUR_RADIUS, ConfigManager.KEY_BG_TYPE,
                ConfigManager.KEY_RIM_HIGHLIGHT).forEach {
                append("Appearance $it=${style.getInt(it, -1)}")
            }
            listOf(ConfigManager.KEY_BG_COLOR, ConfigManager.KEY_TEXT_COLOR,
                ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, ConfigManager.KEY_LETTER_KEYCAP_COLOR).forEach {
                append("Appearance $it=${style.getString(it, "")}")
            }
            val identity = rootCommand("id -u", 90).trim()
            check(identity == "0") { "未获得 Root 权限：$identity" }
            append("Root granted")
            append(rootCommand("getprop ro.mi.os.version.code; getprop ro.mi.os.version.name; " +
                "getprop ro.build.version.incremental; getconf PAGESIZE; " +
                "settings get global device_provisioned; settings --user $user get secure user_setup_complete; " +
                "settings --user $user get secure default_input_method", 15).let {
                "[real OS code / OS name / incremental / page size / provisioned / setup / default IME]\n$it"
            })
            append(rootCommand("getprop persist.sys.background_blur_supported; " +
                "getprop persist.sys.background_blur_version; getprop persist.sys.advanced_visual_release; " +
                "getprop persist.sys.bionic_material_supported; " +
                "settings --user $user get secure background_blur_enable", 15).let {
                "[blur supported / blur version / advanced visual / bionic material / blur enabled]\n$it"
            })
            // Capture from before restart; Android log buffers retain startup messages while su starts.
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            append("[collector] Restarting com.xiaomi.type at $since")
            rootCommand(ImeRestartScript.build(user), 15)
            val budget = DiagnosticLog.handOff(session)
            check(budget > 0) { "采集已停止" }
            val script = RootLogcatScript.start(DiagnosticLog.logFile(app).absolutePath,
                "$imeUid,$moduleUid", since, budget, TimeUnit.MILLISECONDS.toSeconds(MAX_MILLIS), previousGroup)
            val group = rootCommand(script, 15).trim().toIntOrNull() ?: error("无法启动 logcat 采集")
            prefs(app).edit().putInt(KEY_GROUP, group).putLong(KEY_STARTED, System.currentTimeMillis()).commit()
            // stop() may have read an empty group while the capture was starting.
            if (cancelled.get() == session) {
                runCatching { rootCommand(RootLogcatScript.stop(group), 15, track = false) }
                prefs(app).edit().clear().commit()
                return
            }
            DiagnosticLog.update(session, RUNNING_STATUS)
        } catch (e: Exception) {
            if (cancelled.get() != session && DiagnosticLog.isActive(session)) {
                DiagnosticLog.append(session, "[collector error] ${e.stackTraceToString()}")
                DiagnosticLog.finish(session, "采集失败：${e.message ?: e.javaClass.simpleName}；可导出错误详情", error = true)
            }
        }
    }

    private fun launch(command: String, track: Boolean): Process {
        val child = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        if (track) setupProcess = child
        return child
    }

    /** Drain concurrently so a child cannot deadlock on a full stdout pipe. */
    private fun rootCommand(command: String, timeoutSeconds: Long, track: Boolean = true): String {
        val child = launch(command, track)
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
            if (setupProcess === child) setupProcess = null
        }
    }
}
