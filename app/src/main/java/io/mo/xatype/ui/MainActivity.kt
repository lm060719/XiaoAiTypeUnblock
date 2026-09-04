package io.mo.xatype.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.provider.LogContentProvider
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

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
    private lateinit var rbBgImage: RadioButton
    private lateinit var layoutBgColorConfig: LinearLayout
    private lateinit var btnColorCatppuccin: Button
    private lateinit var btnColorAmoled: Button
    private lateinit var btnColorSlate: Button
    private lateinit var btnColorPurple: Button
    private lateinit var btnColorWhite: Button
    private lateinit var etCustomColor: EditText
    private lateinit var btnApplyCustomColor: Button
    private lateinit var layoutBgImageConfig: LinearLayout
    private lateinit var btnPickBgImage: Button
    private lateinit var btnClearBgImage: Button
    private lateinit var ivBgImagePreview: ImageView

    private lateinit var btnRestartIme: Button
    private lateinit var btnAbout: Button

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            saveCustomBackgroundImage(uri)
        }
    }

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
        rbBgImage = findViewById(R.id.rbBgImage)
        layoutBgColorConfig = findViewById(R.id.layoutBgColorConfig)
        btnColorCatppuccin = findViewById(R.id.btnColorCatppuccin)
        btnColorAmoled = findViewById(R.id.btnColorAmoled)
        btnColorSlate = findViewById(R.id.btnColorSlate)
        btnColorPurple = findViewById(R.id.btnColorPurple)
        btnColorWhite = findViewById(R.id.btnColorWhite)
        etCustomColor = findViewById(R.id.etCustomColor)
        btnApplyCustomColor = findViewById(R.id.btnApplyCustomColor)
        layoutBgImageConfig = findViewById(R.id.layoutBgImageConfig)
        btnPickBgImage = findViewById(R.id.btnPickBgImage)
        btnClearBgImage = findViewById(R.id.btnClearBgImage)
        ivBgImagePreview = findViewById(R.id.ivBgImagePreview)

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

        switchClipboardPermanent.isChecked = prefs.getBoolean(ConfigManager.KEY_CLIPBOARD_PERMANENT, true)
        switchClipboardPermanent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(ConfigManager.KEY_CLIPBOARD_PERMANENT, isChecked).apply()
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

    private fun initStyleControls() {
        val prefs = ConfigManager.getLocalPrefs(this)

        val isStyleEnabled = prefs.getBoolean(ConfigManager.KEY_STYLE_ENABLED, true)
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
                val clamped = progress.coerceAtLeast(10)
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
        val bgType = prefs.getInt(ConfigManager.KEY_BG_TYPE, 0)
        when (bgType) {
            1 -> rbBgColor.isChecked = true
            2 -> rbBgImage.isChecked = true
            else -> rbBgDefault.isChecked = true
        }
        updateBgConfigVisibility(bgType)

        rgBgType.setOnCheckedChangeListener { _, checkedId ->
            val newBgType = when (checkedId) {
                R.id.rbBgColor -> 1
                R.id.rbBgImage -> 2
                else -> 0
            }
            prefs.edit().putInt(ConfigManager.KEY_BG_TYPE, newBgType).apply()
            updateBgConfigVisibility(newBgType)
            showRestartHint()
        }

        // Color Presets
        val savedColor = prefs.getString(ConfigManager.KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E"
        etCustomColor.setText(savedColor)

        val selectColor = { hex: String ->
            etCustomColor.setText(hex)
            prefs.edit().putString(ConfigManager.KEY_BG_COLOR, hex).apply()
            showRestartHint()
        }

        btnColorCatppuccin.setOnClickListener { selectColor("#1E1E2E") }
        btnColorAmoled.setOnClickListener { selectColor("#000000") }
        btnColorSlate.setOnClickListener { selectColor("#1E293B") }
        btnColorPurple.setOnClickListener { selectColor("#2E1065") }
        btnColorWhite.setOnClickListener { selectColor("#F1F5F9") }

        btnApplyCustomColor.setOnClickListener {
            val hex = etCustomColor.text.toString().trim()
            try {
                Color.parseColor(hex)
                prefs.edit().putString(ConfigManager.KEY_BG_COLOR, hex).apply()
                Toast.makeText(this, "颜色已更新：$hex", Toast.LENGTH_SHORT).show()
                showRestartHint()
            } catch (_: Throwable) {
                Toast.makeText(this, "无效的 HEX 颜色代码（例如 #1E1E2E）", Toast.LENGTH_SHORT).show()
            }
        }

        // Image Picker
        btnPickBgImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnClearBgImage.setOnClickListener {
            val file = File(filesDir, LogContentProvider.BG_IMAGE_FILENAME)
            if (file.exists()) file.delete()
            prefs.edit().putLong(ConfigManager.KEY_BG_IMAGE_VERSION, System.currentTimeMillis()).apply()
            ivBgImagePreview.visibility = View.GONE
            Toast.makeText(this, "自定义背景图已清除", Toast.LENGTH_SHORT).show()
            showRestartHint()
        }

        updateImagePreviewThumbnail()
    }

    private fun updateBgConfigVisibility(bgType: Int) {
        layoutBgColorConfig.visibility = if (bgType == 1) View.VISIBLE else View.GONE
        layoutBgImageConfig.visibility = if (bgType == 2) View.VISIBLE else View.GONE
    }

    private fun saveCustomBackgroundImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    val targetFile = File(filesDir, LogContentProvider.BG_IMAGE_FILENAME)
                    FileOutputStream(targetFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                    }
                    val prefs = ConfigManager.getLocalPrefs(this)
                    prefs.edit()
                        .putLong(ConfigManager.KEY_BG_IMAGE_VERSION, System.currentTimeMillis())
                        .apply()
                    updateImagePreviewThumbnail()
                    Toast.makeText(this, "背景图片已成功保存！", Toast.LENGTH_SHORT).show()
                    showRestartHint()
                } else {
                    Toast.makeText(this, "无法读取图片内容", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "保存背景图片失败：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateImagePreviewThumbnail() {
        val file = File(filesDir, LogContentProvider.BG_IMAGE_FILENAME)
        if (file.exists() && file.canRead()) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                ivBgImagePreview.setImageBitmap(bmp)
                ivBgImagePreview.visibility = View.VISIBLE
                return
            }
        }
        ivBgImagePreview.visibility = View.GONE
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
                            "1. 键盘外观个性化定制：支持边角圆角弧度、背景透明度、动态液态玻璃、自定义背景图及纯色。\n" +
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
            Toast.makeText(this, "配置已更新，请点击'重启超级小爱输入法'生效", Toast.LENGTH_SHORT).show()
        }
    }
}
