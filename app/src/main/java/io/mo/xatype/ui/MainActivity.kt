package io.mo.xatype.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.data.LogEntry
import io.mo.xatype.provider.LogContentProvider
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView

    private lateinit var tvCountAi: TextView
    private lateinit var tvCountVoice: TextView
    private lateinit var tvCountBlacklist: TextView
    private lateinit var tvCountClipboard: TextView
    private lateinit var tvCountOsVersion: TextView

    private lateinit var btnRefreshLogs: TextView
    private lateinit var btnClearLogs: TextView
    private lateinit var tvEmptyLogs: TextView
    private lateinit var rvLogs: RecyclerView
    private lateinit var logAdapter: LogAdapter

    private lateinit var switchAiSafety: SwitchCompat
    private lateinit var switchVoiceModeration: SwitchCompat
    private lateinit var switchCloudBlacklist: SwitchCompat
    private lateinit var switchClipboardSensitive: SwitchCompat
    private lateinit var switchOsVersionUnblock: SwitchCompat
    private lateinit var switchVerboseLog: SwitchCompat
    private lateinit var btnRestartIme: Button
    private lateinit var btnAbout: Button

    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchLiveLogs()
            if (isPolling) {
                handler.postDelayed(this, 1500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initStatus()
        initSwitches()
        initButtons()
        initLogList()
    }

    override fun onResume() {
        super.onResume()
        isPolling = true
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        isPolling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDesc = findViewById(R.id.tvStatusDesc)

        tvCountAi = findViewById(R.id.tvCountAi)
        tvCountVoice = findViewById(R.id.tvCountVoice)
        tvCountBlacklist = findViewById(R.id.tvCountBlacklist)
        tvCountClipboard = findViewById(R.id.tvCountClipboard)
        tvCountOsVersion = findViewById(R.id.tvCountOsVersion)

        btnRefreshLogs = findViewById(R.id.btnRefreshLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)
        rvLogs = findViewById(R.id.rvLogs)

        switchAiSafety = findViewById(R.id.switchAiSafety)
        switchVoiceModeration = findViewById(R.id.switchVoiceModeration)
        switchCloudBlacklist = findViewById(R.id.switchCloudBlacklist)
        switchClipboardSensitive = findViewById(R.id.switchClipboardSensitive)
        switchOsVersionUnblock = findViewById(R.id.switchOsVersionUnblock)
        switchVerboseLog = findViewById(R.id.switchVerboseLog)
        btnRestartIme = findViewById(R.id.btnRestartIme)
        btnAbout = findViewById(R.id.btnAbout)
    }

    private fun initStatus() {
        val targetPkg = "com.xiaomi.type"
        try {
            val pkgInfo = packageManager.getPackageInfo(targetPkg, 0)
            val versionName = pkgInfo.versionName ?: "Unknown"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            tvStatusDesc.text = "目标应用：$targetPkg (已安装 v$versionName, build $versionCode)\n内核引擎：iFlyTek SmartEngine 定制版\n架构规范：libxposed API 102"
            viewStatusDot.setBackgroundResource(R.drawable.dot_active)
        } catch (_: PackageManager.NameNotFoundException) {
            tvStatusTitle.text = "未检测到超级小爱输入法"
            tvStatusDesc.text = "未安装目标包名：$targetPkg\n请确认设备上是否已安装超级小爱输入法"
            viewStatusDot.setBackgroundResource(R.drawable.dot_inactive)
        }
    }

    private fun initLogList() {
        logAdapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = logAdapter

        btnRefreshLogs.setOnClickListener {
            fetchLiveLogs()
            Toast.makeText(this, "日志已刷新", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            try {
                contentResolver.call(
                    LogContentProvider.CONTENT_URI,
                    LogContentProvider.METHOD_CLEAR,
                    null,
                    null
                )
                fetchLiveLogs()
                Toast.makeText(this, "拦截日志与统计已清空", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun fetchLiveLogs() {
        try {
            val result = contentResolver.call(
                LogContentProvider.CONTENT_URI,
                LogContentProvider.METHOD_GET_LOGS,
                null,
                null
            )
            if (result != null) {
                val list = result.getStringArrayList(LogContentProvider.EXTRA_LOGS_LIST) ?: arrayListOf()
                val aiCount = result.getInt(LogContentProvider.EXTRA_AI_COUNT, 0)
                val voiceCount = result.getInt(LogContentProvider.EXTRA_VOICE_COUNT, 0)
                val blacklistCount = result.getInt(LogContentProvider.EXTRA_BLACKLIST_COUNT, 0)
                val clipboardCount = result.getInt(LogContentProvider.EXTRA_CLIPBOARD_COUNT, 0)
                val osVersionCount = result.getInt(LogContentProvider.EXTRA_OS_VERSION_COUNT, 0)

                tvCountAi.text = aiCount.toString()
                tvCountVoice.text = voiceCount.toString()
                tvCountBlacklist.text = blacklistCount.toString()
                tvCountClipboard.text = clipboardCount.toString()
                tvCountOsVersion.text = osVersionCount.toString()

                val logEntries = ArrayList<LogEntry>()
                for (json in list) {
                    val entry = LogEntry.fromJson(json)
                    if (entry != null) {
                        logEntries.add(entry)
                    }
                }

                if (logEntries.isEmpty()) {
                    tvEmptyLogs.visibility = View.VISIBLE
                    rvLogs.visibility = View.GONE
                } else {
                    tvEmptyLogs.visibility = View.GONE
                    rvLogs.visibility = View.VISIBLE
                    logAdapter.updateData(logEntries)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun initSwitches() {
        val prefs = ConfigManager.getLocalPrefs(this)

        switchAiSafety.isChecked = prefs.getBoolean(ConfigManager.KEY_AI_SAFETY, true)
        switchAiSafety.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_AI_SAFETY, isChecked).apply()
            showRestartHint()
        }

        switchVoiceModeration.isChecked = prefs.getBoolean(ConfigManager.KEY_VOICE_MODERATION, true)
        switchVoiceModeration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_VOICE_MODERATION, isChecked).apply()
            showRestartHint()
        }

        switchCloudBlacklist.isChecked = prefs.getBoolean(ConfigManager.KEY_CLOUD_BLACKLIST, true)
        switchCloudBlacklist.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_CLOUD_BLACKLIST, isChecked).apply()
            showRestartHint()
        }

        switchClipboardSensitive.isChecked = prefs.getBoolean(ConfigManager.KEY_CLIPBOARD_SENSITIVE, true)
        switchClipboardSensitive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_CLIPBOARD_SENSITIVE, isChecked).apply()
            showRestartHint()
        }

        switchOsVersionUnblock.isChecked = prefs.getBoolean(ConfigManager.KEY_OS_VERSION_UNBLOCK, true)
        switchOsVersionUnblock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_OS_VERSION_UNBLOCK, isChecked).apply()
            showRestartHint()
        }

        switchVerboseLog.isChecked = prefs.getBoolean(ConfigManager.KEY_VERBOSE_LOG, true)
        switchVerboseLog.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_VERBOSE_LOG, isChecked).apply()
        }
    }

    private fun initButtons() {
        btnRestartIme.setOnClickListener {
            restartInputMethod()
        }

        btnAbout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("关于模块")
                .setMessage(
                    "【模块功能】\n" +
                            "1. 澎湃 OS4+ 版本限制解除：伪装系统版本并清除 z7.s0 阻断标记，在旧系统与第三方 ROM 上正常使用。\n" +
                            "2. AI 表达安全拦截解除：去除 AI 润色与智能回复时的 '已屏蔽敏感内容' 阻断。\n" +
                            "3. 语音转写合规审查解除：拦截 CONTENT_MODERATION 风控弹窗与 30002 错误中断。\n" +
                            "4. 云端黑名单词库下发拦截：阻断 key_blackliststr 下发，保留所有云端候选与热词。\n" +
                            "5. 剪贴板敏感标记忽略绕过：忽略 IS_SENSITIVE 标记，允许快捷记录与联想。\n\n" +
                            "【实时日志】\n" +
                            "模块通过跨进程日志桥接，将输入法内部的每次净化/拦截事件实时汇报到此界面。\n\n" +
                            "【技术架构】\n" +
                            "基于 libxposed API 102 现代架构开发"
                )
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun restartInputMethod() {
        btnRestartIme.isEnabled = false
        Thread {
            var isSuccess = false
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("am force-stop com.xiaomi.type\n")
                os.writeBytes("exit\n")
                os.flush()
                os.close()

                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    isSuccess = true
                }
            } catch (_: Throwable) {
                isSuccess = false
            }

            runOnUiThread {
                btnRestartIme.isEnabled = true
                if (isSuccess) {
                    Toast.makeText(this, "超级小爱输入法已成功通过 Root 权限重启！", Toast.LENGTH_SHORT).show()
                    fetchLiveLogs()
                } else {
                    Toast.makeText(this, "请授权 Root 权限后操作！", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private var lastToastTime = 0L
    private fun showRestartHint() {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 3000) {
            lastToastTime = now
            Toast.makeText(this, "配置已更新，请点击'重启超级小爱输入法'生效", Toast.LENGTH_SHORT).show()
        }
    }
}
