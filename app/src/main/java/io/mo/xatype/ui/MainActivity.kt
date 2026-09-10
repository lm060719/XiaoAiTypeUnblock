package io.mo.xatype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private data class RgbControls(
        val preview: View,
        val hexValue: TextView,
        val red: SeekBar,
        val redValue: TextView,
        val green: SeekBar,
        val greenValue: TextView,
        val blue: SeekBar,
        val blueValue: TextView
    )

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
    private lateinit var switchVerboseLog: SwitchCompat

    // Style Customization Views
    private lateinit var switchStyleEnabled: SwitchCompat
    private lateinit var layoutStyleControls: LinearLayout
    private lateinit var tvCornerRadiusValue: TextView
    private lateinit var sbCornerRadius: SeekBar
    private lateinit var tvOpacityValue: TextView
    private lateinit var sbOpacity: SeekBar
    private lateinit var tvBlurRadiusValue: TextView
    private lateinit var sbBlurRadius: SeekBar
    private lateinit var rgBgType: RadioGroup
    private lateinit var rbBgDefault: RadioButton
    private lateinit var rbBgColor: RadioButton
    private lateinit var layoutBgColorConfig: LinearLayout
    private lateinit var btnColorCatppuccin: Button
    private lateinit var btnColorAmoled: Button
    private lateinit var btnColorSlate: Button
    private lateinit var btnColorPurple: Button
    private lateinit var btnColorWhite: Button
    private lateinit var backgroundRgb: RgbControls
    private lateinit var switchCustomTextColor: SwitchCompat
    private lateinit var layoutTextColorConfig: LinearLayout
    private lateinit var textRgb: RgbControls
    private lateinit var switchCustomFunctionKeycapColor: SwitchCompat
    private lateinit var layoutFunctionKeycapColorConfig: LinearLayout
    private lateinit var functionKeycapRgb: RgbControls
    private lateinit var switchCustomMenuCardColor: SwitchCompat
    private lateinit var layoutMenuCardColorConfig: LinearLayout
    private lateinit var menuCardRgb: RgbControls
    private lateinit var switchCustomLetterKeycapColor: SwitchCompat
    private lateinit var layoutLetterKeycapColorConfig: LinearLayout
    private lateinit var letterKeycapRgb: RgbControls
    private lateinit var btnRestartIme: Button
    private lateinit var btnAbout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initStatus()
        initSwitches()
        initStyleControls()
        initButtons()
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
        switchVerboseLog = findViewById(R.id.switchVerboseLog)

        // Style controls
        switchStyleEnabled = findViewById(R.id.switchStyleEnabled)
        layoutStyleControls = findViewById(R.id.layoutStyleControls)
        tvCornerRadiusValue = findViewById(R.id.tvCornerRadiusValue)
        sbCornerRadius = findViewById(R.id.sbCornerRadius)
        tvOpacityValue = findViewById(R.id.tvOpacityValue)
        sbOpacity = findViewById(R.id.sbOpacity)
        tvBlurRadiusValue = findViewById(R.id.tvBlurRadiusValue)
        sbBlurRadius = findViewById(R.id.sbBlurRadius)
        rgBgType = findViewById(R.id.rgBgType)
        rbBgDefault = findViewById(R.id.rbBgDefault)
        rbBgColor = findViewById(R.id.rbBgColor)
        layoutBgColorConfig = findViewById(R.id.layoutBgColorConfig)
        btnColorCatppuccin = findViewById(R.id.btnColorCatppuccin)
        btnColorAmoled = findViewById(R.id.btnColorAmoled)
        btnColorSlate = findViewById(R.id.btnColorSlate)
        btnColorPurple = findViewById(R.id.btnColorPurple)
        btnColorWhite = findViewById(R.id.btnColorWhite)
        backgroundRgb = createRgbControls(findViewById(R.id.rgbBackgroundControls))
        switchCustomTextColor = findViewById(R.id.switchCustomTextColor)
        layoutTextColorConfig = findViewById(R.id.layoutTextColorConfig)
        textRgb = createRgbControls(layoutTextColorConfig)
        switchCustomFunctionKeycapColor = findViewById(R.id.switchCustomFunctionKeycapColor)
        layoutFunctionKeycapColorConfig = findViewById(R.id.layoutFunctionKeycapColorConfig)
        functionKeycapRgb = createRgbControls(layoutFunctionKeycapColorConfig)
        switchCustomMenuCardColor = findViewById(R.id.switchCustomMenuCardColor)
        layoutMenuCardColorConfig = findViewById(R.id.layoutMenuCardColorConfig)
        menuCardRgb = createRgbControls(layoutMenuCardColorConfig)
        switchCustomLetterKeycapColor = findViewById(R.id.switchCustomLetterKeycapColor)
        layoutLetterKeycapColorConfig = findViewById(R.id.layoutLetterKeycapColorConfig)
        letterKeycapRgb = createRgbControls(layoutLetterKeycapColorConfig)
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



    private fun initSwitches() {
        val prefs = ConfigManager.getLocalPrefs(this)

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

    private fun initStyleControls() {
        val prefs = ConfigManager.getLocalPrefs(this)

        val isStyleEnabled = prefs.getBoolean(ConfigManager.KEY_STYLE_ENABLED, false)
        switchStyleEnabled.isChecked = isStyleEnabled
        layoutStyleControls.visibility = if (isStyleEnabled) View.VISIBLE else View.GONE
        switchStyleEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_STYLE_ENABLED, isChecked).apply()
            layoutStyleControls.visibility = if (isChecked) View.VISIBLE else View.GONE
            showRestartHint()
        }

        // 1. Corner Radius
        val cornerRadius = prefs.getInt(ConfigManager.KEY_CORNER_RADIUS, 16)
        sbCornerRadius.progress = cornerRadius
        tvCornerRadiusValue.text = "$cornerRadius dp"
        sbCornerRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCornerRadiusValue.text = "$progress dp"
                if (fromUser) {
                    prefs.edit().putInt(ConfigManager.KEY_CORNER_RADIUS, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { showRestartHint() }
        })

        // 2. Opacity
        val opacity = prefs.getInt(ConfigManager.KEY_OPACITY, 85)
        sbOpacity.progress = opacity
        tvOpacityValue.text = "$opacity%"
        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(0, 100)
                tvOpacityValue.text = "$clamped%"
                if (fromUser) {
                    prefs.edit().putInt(ConfigManager.KEY_OPACITY, clamped).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { showRestartHint() }
        })

        // Blur Radius
        val blurRadius = prefs.getInt(ConfigManager.KEY_BLUR_RADIUS, 50)
        sbBlurRadius.progress = blurRadius
        tvBlurRadiusValue.text = "$blurRadius dp"
        sbBlurRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(20)
                tvBlurRadiusValue.text = "$clamped dp"
                if (fromUser) {
                    prefs.edit().putInt(ConfigManager.KEY_BLUR_RADIUS, clamped).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { showRestartHint() }
        })

        // 3. Background Type
        val savedBgType = prefs.getInt(ConfigManager.KEY_BG_TYPE, 0)
        val bgType = if (savedBgType == 1) 1 else 0
        if (savedBgType != bgType) {
            prefs.edit().putInt(ConfigManager.KEY_BG_TYPE, bgType).apply()
        }
        when (bgType) {
            1 -> rbBgColor.isChecked = true
            else -> rbBgDefault.isChecked = true
        }
        updateBgConfigVisibility(bgType)

        rgBgType.setOnCheckedChangeListener { _, checkedId ->
            val newBgType = when (checkedId) {
                R.id.rbBgColor -> 1
                else -> 0
            }
            prefs.edit().putInt(ConfigManager.KEY_BG_TYPE, newBgType).apply()
            updateBgConfigVisibility(newBgType)
            showRestartHint()
        }

        // Color Presets
        val savedColor = prefs.getString(ConfigManager.KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E"
        configureRgbControls(backgroundRgb, savedColor) { hex ->
            prefs.edit().putString(ConfigManager.KEY_BG_COLOR, hex).apply()
        }

        val selectColor = { hex: String ->
            setRgbColor(backgroundRgb, hex)
            prefs.edit().putString(ConfigManager.KEY_BG_COLOR, hex).apply()
            showRestartHint()
        }

        btnColorCatppuccin.setOnClickListener { selectColor("#1E1E2E") }
        btnColorAmoled.setOnClickListener { selectColor("#000000") }
        btnColorSlate.setOnClickListener { selectColor("#1E293B") }
        btnColorPurple.setOnClickListener { selectColor("#2E1065") }
        btnColorWhite.setOnClickListener { selectColor("#F1F5F9") }

        // 4. Key label / toolbar icon color. An empty value keeps automatic contrast.
        val savedTextColor = prefs.getString(ConfigManager.KEY_TEXT_COLOR, "") ?: ""
        val customTextColorEnabled = savedTextColor.isNotBlank()
        configureRgbControls(textRgb, savedTextColor.ifBlank { "#FFFFFF" }) { hex ->
            prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, hex).apply()
        }
        switchCustomTextColor.isChecked = customTextColorEnabled
        layoutTextColorConfig.visibility = if (customTextColorEnabled) View.VISIBLE else View.GONE

        switchCustomTextColor.setOnCheckedChangeListener { _, isChecked ->
            layoutTextColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, currentRgbHex(textRgb)).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, "").apply()
            }
            showRestartHint()
        }

        // 5. Function keycap color. The existing preference key is retained for migration.
        val savedFunctionKeycapColor = prefs.getString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, "") ?: ""
        val customFunctionKeycapColorEnabled = savedFunctionKeycapColor.isNotBlank()
        configureRgbControls(functionKeycapRgb, savedFunctionKeycapColor.ifBlank { "#FFFFFF" }) { hex ->
            prefs.edit().putString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, hex).apply()
        }
        switchCustomFunctionKeycapColor.isChecked = customFunctionKeycapColorEnabled
        layoutFunctionKeycapColorConfig.visibility = if (customFunctionKeycapColorEnabled) View.VISIBLE else View.GONE

        switchCustomFunctionKeycapColor.setOnCheckedChangeListener { _, isChecked ->
            layoutFunctionKeycapColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, currentRgbHex(functionKeycapRgb)).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, "").apply()
            }
            showRestartHint()
        }

        // 6. Menu card color. This controls the APPS panel C0 token independently.
        val savedMenuCardColor = prefs.getString(ConfigManager.KEY_MENU_CARD_COLOR, "") ?: ""
        val customMenuCardColorEnabled = savedMenuCardColor.isNotBlank()
        configureRgbControls(menuCardRgb, savedMenuCardColor.ifBlank { "#FFFFFF" }) { hex ->
            prefs.edit().putString(ConfigManager.KEY_MENU_CARD_COLOR, hex).apply()
        }
        switchCustomMenuCardColor.isChecked = customMenuCardColorEnabled
        layoutMenuCardColorConfig.visibility = if (customMenuCardColorEnabled) View.VISIBLE else View.GONE

        switchCustomMenuCardColor.setOnCheckedChangeListener { _, isChecked ->
            layoutMenuCardColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_MENU_CARD_COLOR, currentRgbHex(menuCardRgb)).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_MENU_CARD_COLOR, "").apply()
            }
            showRestartHint()
        }

        // 7. Letter/number main keycap color. na.d.d() returns this normal-key token.
        val savedLetterKeycapColor = prefs.getString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, "") ?: ""
        val customLetterKeycapColorEnabled = savedLetterKeycapColor.isNotBlank()
        configureRgbControls(letterKeycapRgb, savedLetterKeycapColor.ifBlank { "#FFFFFF" }) { hex ->
            prefs.edit().putString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, hex).apply()
        }
        switchCustomLetterKeycapColor.isChecked = customLetterKeycapColorEnabled
        layoutLetterKeycapColorConfig.visibility = if (customLetterKeycapColorEnabled) View.VISIBLE else View.GONE

        switchCustomLetterKeycapColor.setOnCheckedChangeListener { _, isChecked ->
            layoutLetterKeycapColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, currentRgbHex(letterKeycapRgb)).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, "").apply()
            }
            showRestartHint()
        }

    }

    private fun createRgbControls(container: LinearLayout): RgbControls {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val hexValue = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setPadding(dp(10), 0, 0, 0)
        }
        header.addView(preview)
        header.addView(hexValue)
        container.addView(header)

        fun addChannel(label: String): Pair<SeekBar, TextView> {
            val channelColor = when (label) {
                "R" -> Color.rgb(239, 68, 68)
                "G" -> Color.rgb(34, 197, 94)
                else -> Color.rgb(59, 130, 246)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val title = TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
            }
            val seekBar = SeekBar(this).apply {
                max = 255
                progressTintList = ColorStateList.valueOf(channelColor)
                thumbTintList = ColorStateList.valueOf(channelColor)
            }
            val value = TextView(this).apply {
                setTextColor(getColor(R.color.primary))
                textSize = 12f
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(title, LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value, LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(row)
            return seekBar to value
        }

        val (red, redValue) = addChannel("R")
        val (green, greenValue) = addChannel("G")
        val (blue, blueValue) = addChannel("B")
        return RgbControls(preview, hexValue, red, redValue, green, greenValue, blue, blueValue)
    }

    private fun configureRgbControls(controls: RgbControls, initialColor: String, onChanged: (String) -> Unit) {
        setRgbColor(controls, initialColor)
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshRgbDisplay(controls)
                if (fromUser) onChanged(currentRgbHex(controls))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { showRestartHint() }
        }
        controls.red.setOnSeekBarChangeListener(listener)
        controls.green.setOnSeekBarChangeListener(listener)
        controls.blue.setOnSeekBarChangeListener(listener)
    }

    private fun setRgbColor(controls: RgbControls, colorString: String) {
        val color = try {
            Color.parseColor(colorString)
        } catch (_: Throwable) {
            Color.WHITE
        }
        controls.red.progress = Color.red(color)
        controls.green.progress = Color.green(color)
        controls.blue.progress = Color.blue(color)
        refreshRgbDisplay(controls)
    }

    private fun refreshRgbDisplay(controls: RgbControls) {
        val color = Color.rgb(controls.red.progress, controls.green.progress, controls.blue.progress)
        controls.preview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(color)
            setStroke(dp(1), getColor(R.color.card_stroke))
        }
        controls.hexValue.text = currentRgbHex(controls)
        controls.redValue.text = controls.red.progress.toString()
        controls.greenValue.text = controls.green.progress.toString()
        controls.blueValue.text = controls.blue.progress.toString()
    }

    private fun currentRgbHex(controls: RgbControls): String = String.format(
        "#%02X%02X%02X",
        controls.red.progress,
        controls.green.progress,
        controls.blue.progress
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateBgConfigVisibility(bgType: Int) {
        layoutBgColorConfig.visibility = if (bgType == 1) View.VISIBLE else View.GONE
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
        Thread {
            var isSuccess = false
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("am force-stop com.miui.phrase\n")
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
            Toast.makeText(this, "配置已更新，重启超级小爱输入法生效", Toast.LENGTH_SHORT).show()
        }
    }
}
