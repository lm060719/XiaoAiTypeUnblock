package io.mo.xatype.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.mo.xatype.BuildConfig
import io.mo.xatype.R
import io.mo.xatype.compat.AdaptationStatus
import io.mo.xatype.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView

    // Function Switches
    private lateinit var switchAiSafety: SwitchCompat
    private lateinit var switchVoiceModeration: SwitchCompat
    private lateinit var switchCloudBlacklist: SwitchCompat
    private lateinit var switchClipboardSensitive: SwitchCompat
    private lateinit var switchClipboardPermanent: SwitchCompat
    private lateinit var switchOsVersionUnblock: SwitchCompat
    private lateinit var switchKeyboardHeightUnblock: SwitchCompat
    private lateinit var switchBottomSpacing: SwitchCompat
    private lateinit var sbBottomSpacing: SeekBar
    private lateinit var tvBottomSpacingValue: TextView
    private lateinit var switchVerboseLog: SwitchCompat

    private lateinit var tvAppearanceSummary: TextView
    private lateinit var btnRestartIme: Button
    private lateinit var btnAbout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initStatus()
        initSwitches()
        initButtons()
        findViewById<Button>(R.id.btnInputDiagnostics).apply {
            visibility = if (io.mo.xatype.BuildConfig.INPUT_DIAGNOSTICS) View.VISIBLE else View.GONE
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, io.mo.xatype.diagnostics.DiagnosticsActivity::class.java))
            }
        }
    }

    private fun initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDesc = findViewById(R.id.tvStatusDesc)

        switchAiSafety = findViewById(R.id.switchAiSafety)
        switchVoiceModeration = findViewById(R.id.switchVoiceModeration)
        switchCloudBlacklist = findViewById(R.id.switchCloudBlacklist)
        switchClipboardSensitive = findViewById(R.id.switchClipboardSensitive)
        switchClipboardPermanent = findViewById(R.id.switchClipboardPermanent)
        switchOsVersionUnblock = findViewById(R.id.switchOsVersionUnblock)
        switchKeyboardHeightUnblock = findViewById(R.id.switchKeyboardHeightUnblock)
        switchBottomSpacing = findViewById(R.id.switchBottomSpacing)
        sbBottomSpacing = findViewById(R.id.sbBottomSpacing)
        tvBottomSpacingValue = findViewById(R.id.tvBottomSpacingValue)
        switchVerboseLog = findViewById(R.id.switchVerboseLog)

        tvAppearanceSummary = findViewById(R.id.tvAppearanceSummary)
        findViewById<View>(R.id.rowAppearance).setOnClickListener {
            startActivity(android.content.Intent(this, AppearanceActivity::class.java))
        }
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
            tvStatusTitle.text = "小爱输入法已安装"
            val adaptation = adaptationSummary(versionCode)
            tvStatusDesc.text = "版本: $versionName ($versionCode) | ${adaptation.first}"
            viewStatusDot.setBackgroundResource(
                if (adaptation.second) R.drawable.dot_active else R.drawable.dot_inactive
            )
        } catch (_: PackageManager.NameNotFoundException) {
            tvStatusTitle.text = "未检测到超级小爱输入法"
            tvStatusDesc.text = "请确认已安装 com.xiaomi.type 并启用模块"
            viewStatusDot.setBackgroundResource(R.drawable.dot_inactive)
        }
    }

    override fun onResume() {
        super.onResume()
        // Reports arrive whenever the input method restarts.
        initStatus()
        tvAppearanceSummary.text = if (AppearanceActivity.isEnabled(this)) {
            "已启用 · 圆角、背景、透明度、字体与键帽颜色"
        } else {
            "未启用 · 点击进入设置，底部带实时预览"
        }
    }

    /**
     * Reads what the hooked processes reported about locating their targets.
     * @return the status text, and whether every feature was located.
     */
    private fun adaptationSummary(imeVersionCode: Long): Pair<String, Boolean> {
        val prefs = getSharedPreferences(AdaptationStatus.PREFS_NAME, Context.MODE_PRIVATE)
        fun report(pkg: String) = prefs.getString(pkg, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

        val ime = report(AdaptationStatus.IME_PACKAGE)
            ?: return "模块未运行：请在 LSPosed 中启用模块并重启输入法" to false
        val stamp = AdaptationStatus.moduleStamp(BuildConfig.VERSION_CODE, applicationInfo.sourceDir)
        if (ime.optLong(AdaptationStatus.EXTRA_VERSION_CODE) != imeVersionCode ||
            ime.optString(AdaptationStatus.EXTRA_MODULE_STAMP) != stamp
        ) {
            return "输入法或模块已更新，重启输入法后重新适配" to false
        }

        val reports = listOfNotNull(ime, report(AdaptationStatus.PHRASE_PACKAGE))
        if (reports.any { it.optString(AdaptationStatus.EXTRA_SOURCE) == "UNAVAILABLE" }) {
            return "自适配未能运行，已回退到内置适配" to false
        }
        val missing = reports.flatMap { json ->
            val keys = json.optJSONArray(AdaptationStatus.EXTRA_MISSING) ?: JSONArray()
            List(keys.length()) { keys.getString(it) }
        }
        return if (missing.isEmpty()) {
            "模块已生效" to true
        } else {
            "未生效：" + AdaptationStatus.featureNames(missing).joinToString("、") to false
        }
    }

    private fun initSwitches() {
        val prefs = ConfigManager.getLocalPrefs(this)

        fun updateBottomSpacingLabel() {
            tvBottomSpacingValue.text = if (switchBottomSpacing.isChecked) "${sbBottomSpacing.progress} dp" else "原生间距"
        }
        switchBottomSpacing.isChecked = prefs.getBoolean(ConfigManager.KEY_BOTTOM_SPACING_ENABLED, false)
        sbBottomSpacing.progress = prefs.getInt(ConfigManager.KEY_BOTTOM_SPACING, 0).coerceIn(0, 100)
        sbBottomSpacing.isEnabled = switchBottomSpacing.isChecked
        updateBottomSpacingLabel()
        switchBottomSpacing.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ConfigManager.KEY_BOTTOM_SPACING_ENABLED, checked).apply()
            sbBottomSpacing.isEnabled = checked
            updateBottomSpacingLabel()
            showRestartHint()
        }
        sbBottomSpacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateBottomSpacingLabel()
                if (fromUser) prefs.edit().putInt(ConfigManager.KEY_BOTTOM_SPACING, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { showRestartHint() }
        })

        switchKeyboardHeightUnblock.isChecked = prefs.getBoolean(ConfigManager.KEY_KEYBOARD_HEIGHT_UNBLOCK, false)
        switchKeyboardHeightUnblock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_KEYBOARD_HEIGHT_UNBLOCK, isChecked).apply()
            showRestartHint()
        }

        switchAiSafety.isChecked = prefs.getBoolean(ConfigManager.KEY_AI_SAFETY, false)
        switchAiSafety.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_AI_SAFETY, isChecked).apply()
            showRestartHint()
        }

        switchVoiceModeration.isChecked = prefs.getBoolean(ConfigManager.KEY_VOICE_MODERATION, false)
        switchVoiceModeration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_VOICE_MODERATION, isChecked).apply()
            showRestartHint()
        }

        switchCloudBlacklist.isChecked = prefs.getBoolean(ConfigManager.KEY_CLOUD_BLACKLIST, false)
        switchCloudBlacklist.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_CLOUD_BLACKLIST, isChecked).apply()
            showRestartHint()
        }

        switchClipboardSensitive.isChecked = prefs.getBoolean(ConfigManager.KEY_CLIPBOARD_SENSITIVE, false)
        switchClipboardSensitive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_CLIPBOARD_SENSITIVE, isChecked).apply()
            showRestartHint()
        }

        switchClipboardPermanent.isChecked = prefs.getBoolean(ConfigManager.KEY_CLIPBOARD_PERMANENT, false)
        switchClipboardPermanent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_CLIPBOARD_PERMANENT, isChecked).apply()
            showRestartHint()
        }

        switchOsVersionUnblock.isChecked = prefs.getBoolean(ConfigManager.KEY_OS_VERSION_UNBLOCK, false)
        switchOsVersionUnblock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_OS_VERSION_UNBLOCK, isChecked).apply()
            showRestartHint()
        }

        switchVerboseLog.isChecked = prefs.getBoolean(ConfigManager.KEY_VERBOSE_LOG, false)
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
                            "1. 键盘外观个性化定制：支持圆角、透明度、动态液态玻璃、纯色背景、字体及键帽颜色。\n" +
                            "2. 澎湃 OS4+ 版本限制解除：伪装系统版本并清除 z7.s0 阻断标记，在旧系统与第三方 ROM 上正常使用。\n" +
                            "3. AI 表达安全拦截解除：去除 AI 润色与智能回复时的 '已屏蔽敏感内容' 阻断。\n" +
                            "4. 语音转写合规审查解除：拦截 CONTENT_MODERATION 风控弹窗与 30002 错误中断。\n" +
                            "5. 云端黑名单词库下发拦截：阻断 key_blackliststr 下发，保留所有云端候选与热词。\n" +
                            "6. 剪贴板敏感标记忽略绕过：忽略 IS_SENSITIVE 标记，允许快捷记录与联想。\n" +
                            "7. 剪贴板永久保存：解除 20 条、72 小时和单条文字长度限制。\n\n" +
                            "【实时日志】\n" +
                            "模块通过跨进程日志桥接，将输入法内部的每次净化/样式应用事件实时汇报到此界面。\n\n" +
                            "【技术架构】\n" +
                            "基于 libxposed API 102 现代架构开发"
                )
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun restartInputMethod() {
        btnRestartIme.isEnabled = false
        ImeRestarter.restart(this) { btnRestartIme.isEnabled = true }
    }

    private var lastToastTime = 0L
    private fun showRestartHint() {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 3000) {
            lastToastTime = now
            Toast.makeText(this, "配置已更新，重启超级小爱输入法生效", Toast.LENGTH_SHORT).show()
        }
    }
}
