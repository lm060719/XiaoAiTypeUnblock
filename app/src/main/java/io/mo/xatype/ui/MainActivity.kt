package io.mo.xatype.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
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
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.hooks.BackgroundOpacity
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private data class ColorControls(
        val title: String,
        val preview: View,
        val hexValue: TextView,
        var currentColorHex: String = "#FFFFFF",
        val opacity: SeekBar? = null,
        val opacityValue: TextView? = null,
        val opacityKey: String? = null,
        var onColorChanged: ((String) -> Unit)? = null
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
    private lateinit var backgroundRgb: ColorControls
    private lateinit var switchCustomTextColor: SwitchCompat
    private lateinit var layoutTextColorConfig: LinearLayout
    private lateinit var textRgb: ColorControls
    private lateinit var switchCustomFunctionKeycapColor: SwitchCompat
    private lateinit var layoutFunctionKeycapColorConfig: LinearLayout
    private lateinit var functionKeycapRgb: ColorControls
    private lateinit var switchCustomMenuCardColor: SwitchCompat
    private lateinit var layoutMenuCardColorConfig: LinearLayout
    private lateinit var menuCardRgb: ColorControls
    private lateinit var switchCustomClipboardCardColor: SwitchCompat
    private lateinit var layoutClipboardCardColorConfig: LinearLayout
    private lateinit var clipboardCardRgb: ColorControls
    private lateinit var switchCustomLetterKeycapColor: SwitchCompat
    private lateinit var layoutLetterKeycapColorConfig: LinearLayout
    private lateinit var letterKeycapRgb: ColorControls
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
        backgroundRgb = createColorControls(findViewById(R.id.rgbBackgroundControls), "背景颜色")
        switchCustomTextColor = findViewById(R.id.switchCustomTextColor)
        layoutTextColorConfig = findViewById(R.id.layoutTextColorConfig)
        textRgb = createColorControls(layoutTextColorConfig, "按键字体颜色")
        switchCustomFunctionKeycapColor = findViewById(R.id.switchCustomFunctionKeycapColor)
        layoutFunctionKeycapColorConfig = findViewById(R.id.layoutFunctionKeycapColorConfig)
        functionKeycapRgb = createColorControls(
            layoutFunctionKeycapColorConfig,
            "功能键键帽颜色",
            includeOpacity = true,
            opacityKey = ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY
        )
        switchCustomMenuCardColor = findViewById(R.id.switchCustomMenuCardColor)
        layoutMenuCardColorConfig = findViewById(R.id.layoutMenuCardColorConfig)
        menuCardRgb = createColorControls(
            layoutMenuCardColorConfig,
            "菜单功能卡片颜色",
            includeOpacity = true,
            opacityKey = ConfigManager.KEY_MENU_CARD_OPACITY
        )
        switchCustomClipboardCardColor = findViewById(R.id.switchCustomClipboardCardColor)
        layoutClipboardCardColorConfig = findViewById(R.id.layoutClipboardCardColorConfig)
        clipboardCardRgb = createColorControls(
            layoutClipboardCardColorConfig,
            "剪贴板卡片颜色",
            includeOpacity = true,
            opacityKey = ConfigManager.KEY_CLIPBOARD_CARD_OPACITY
        )
        switchCustomLetterKeycapColor = findViewById(R.id.switchCustomLetterKeycapColor)
        layoutLetterKeycapColorConfig = findViewById(R.id.layoutLetterKeycapColorConfig)
        letterKeycapRgb = createColorControls(
            layoutLetterKeycapColorConfig,
            "字母键键帽颜色",
            includeOpacity = true,
            opacityKey = ConfigManager.KEY_LETTER_KEYCAP_OPACITY
        )
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
            tvStatusDesc.text = "版本: $versionName ($versionCode) | 模块已生效"
            viewStatusDot.setBackgroundResource(R.drawable.dot_active)
        } catch (_: PackageManager.NameNotFoundException) {
            tvStatusTitle.text = "未检测到超级小爱输入法"
            tvStatusDesc.text = "请确认已安装 com.xiaomi.type 并启用模块"
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
        configureColorControls(backgroundRgb, savedColor) { hex ->
            prefs.edit().putString(ConfigManager.KEY_BG_COLOR, hex).apply()
        }

        val selectColor = { hex: String ->
            setColor(backgroundRgb, hex)
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
        configureColorControls(textRgb, savedTextColor.ifBlank { "#FFFFFF" }) { hex ->
            prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, hex).apply()
        }
        switchCustomTextColor.isChecked = customTextColorEnabled
        layoutTextColorConfig.visibility = if (customTextColorEnabled) View.VISIBLE else View.GONE

        switchCustomTextColor.setOnCheckedChangeListener { _, isChecked ->
            layoutTextColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, textRgb.currentColorHex).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_TEXT_COLOR, "").apply()
            }
            showRestartHint()
        }

        // 5. Function keycap color. The existing preference key is retained for migration.
        val savedFunctionKeycapColor = prefs.getString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, "") ?: ""
        val customFunctionKeycapColorEnabled = savedFunctionKeycapColor.isNotBlank()
        configureColorControls(functionKeycapRgb, savedFunctionKeycapColor.ifBlank { "#FFFFFF" }, ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY) { hex ->
            prefs.edit().putString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, hex).apply()
        }
        switchCustomFunctionKeycapColor.isChecked = customFunctionKeycapColorEnabled
        layoutFunctionKeycapColorConfig.visibility = if (customFunctionKeycapColorEnabled) View.VISIBLE else View.GONE

        switchCustomFunctionKeycapColor.setOnCheckedChangeListener { _, isChecked ->
            layoutFunctionKeycapColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, functionKeycapRgb.currentColorHex).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, "").apply()
            }
            showRestartHint()
        }

        // 6. Menu card color. This controls the APPS panel C0 token independently.
        val savedMenuCardColor = prefs.getString(ConfigManager.KEY_MENU_CARD_COLOR, "") ?: ""
        val customMenuCardColorEnabled = savedMenuCardColor.isNotBlank()
        configureColorControls(menuCardRgb, savedMenuCardColor.ifBlank { "#FFFFFF" }, ConfigManager.KEY_MENU_CARD_OPACITY) { hex ->
            prefs.edit().putString(ConfigManager.KEY_MENU_CARD_COLOR, hex).apply()
        }
        switchCustomMenuCardColor.isChecked = customMenuCardColorEnabled
        layoutMenuCardColorConfig.visibility = if (customMenuCardColorEnabled) View.VISIBLE else View.GONE

        switchCustomMenuCardColor.setOnCheckedChangeListener { _, isChecked ->
            layoutMenuCardColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_MENU_CARD_COLOR, menuCardRgb.currentColorHex).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_MENU_CARD_COLOR, "").apply()
            }
            showRestartHint()
        }

        // 7. Clipboard card color. Migrate the former shared menu color once, then keep it independent.
        if (!prefs.contains(ConfigManager.KEY_CLIPBOARD_CARD_COLOR)) {
            val legacyClipboardColor = savedMenuCardColor
            if (legacyClipboardColor.isNotBlank()) {
                prefs.edit()
                    .putString(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, legacyClipboardColor)
                    .putInt(
                        ConfigManager.KEY_CLIPBOARD_CARD_OPACITY,
                        prefs.getInt(ConfigManager.KEY_MENU_CARD_OPACITY, 100)
                    )
                    .apply()
            }
        }

        val savedClipboardCardColor =
            prefs.getString(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, "") ?: ""
        val customClipboardCardColorEnabled = savedClipboardCardColor.isNotBlank()

        configureColorControls(
            clipboardCardRgb,
            savedClipboardCardColor.ifBlank { "#FFFFFF" },
            ConfigManager.KEY_CLIPBOARD_CARD_OPACITY
        ) { hex ->
            prefs.edit()
                .putString(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, hex)
                .apply()
        }

        switchCustomClipboardCardColor.isChecked = customClipboardCardColorEnabled
        layoutClipboardCardColorConfig.visibility =
            if (customClipboardCardColorEnabled) View.VISIBLE else View.GONE

        switchCustomClipboardCardColor.setOnCheckedChangeListener { _, isChecked ->
            layoutClipboardCardColorConfig.visibility =
                if (isChecked) View.VISIBLE else View.GONE

            if (isChecked) {
                prefs.edit()
                    .putString(
                        ConfigManager.KEY_CLIPBOARD_CARD_COLOR,
                        clipboardCardRgb.currentColorHex
                    )
                    .apply()
            } else {
                prefs.edit()
                    .putString(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, "")
                    .apply()
            }
            showRestartHint()
        }

        // 8. Letter/number main keycap color. na.d.d() returns this normal-key token.
        val savedLetterKeycapColor = prefs.getString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, "") ?: ""
        val customLetterKeycapColorEnabled = savedLetterKeycapColor.isNotBlank()
        configureColorControls(letterKeycapRgb, savedLetterKeycapColor.ifBlank { "#FFFFFF" }, ConfigManager.KEY_LETTER_KEYCAP_OPACITY) { hex ->
            prefs.edit().putString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, hex).apply()
        }
        switchCustomLetterKeycapColor.isChecked = customLetterKeycapColorEnabled
        layoutLetterKeycapColorConfig.visibility = if (customLetterKeycapColorEnabled) View.VISIBLE else View.GONE

        switchCustomLetterKeycapColor.setOnCheckedChangeListener { _, isChecked ->
            layoutLetterKeycapColorConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                prefs.edit().putString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, letterKeycapRgb.currentColorHex).apply()
            } else {
                prefs.edit().putString(ConfigManager.KEY_LETTER_KEYCAP_COLOR, "").apply()
            }
            showRestartHint()
        }
    }

    private fun createColorControls(
        container: LinearLayout,
        title: String,
        includeOpacity: Boolean = false,
        opacityKey: String? = null
    ): ColorControls {
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(14)
            }
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = getDrawable(outValue.resourceId)
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val hexValue = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            isClickable = true
            isFocusable = true
            setPadding(0, dp(2), dp(8), dp(2))
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }

        val hintText = TextView(this).apply {
            text = "点击色块调色 · 点击色号修改"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        }

        infoCol.addView(hexValue)
        infoCol.addView(hintText)
        colorRow.addView(preview)
        colorRow.addView(infoCol)
        container.addView(colorRow)

        var opacitySeekBar: SeekBar? = null
        var opacityValue: TextView? = null

        if (includeOpacity) {
            val opacityRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val opacityTitle = TextView(this).apply {
                text = "不透明度"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
            }
            opacitySeekBar = SeekBar(this).apply {
                max = 100
                progress = 100
                contentDescription = "不透明度"
                progressTintList = ColorStateList.valueOf(getColor(R.color.primary))
                thumbTintList = ColorStateList.valueOf(getColor(R.color.primary))
            }
            opacityValue = TextView(this).apply {
                setTextColor(getColor(R.color.primary))
                textSize = 12f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                text = "100%"
            }
            opacityRow.addView(opacityTitle, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
            opacityRow.addView(opacitySeekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            opacityRow.addView(opacityValue, LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(opacityRow)

            val opacityHint = TextView(this).apply {
                text = "0% 全透明，100% 不透明"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            }
            container.addView(opacityHint)
        }

        val controls = ColorControls(
            title = title,
            preview = preview,
            hexValue = hexValue,
            currentColorHex = "#FFFFFF",
            opacity = opacitySeekBar,
            opacityValue = opacityValue,
            opacityKey = opacityKey
        )

        // 点击色块即可调出色盘
        preview.setOnClickListener {
            showColorPickerDialog(controls)
        }

        // 点击色号可直接修改色号
        hexValue.setOnClickListener {
            showHexInputDialog(controls)
        }

        return controls
    }

    private fun configureColorControls(
        controls: ColorControls,
        initialColor: String,
        opacityKey: String? = null,
        onChanged: (String) -> Unit
    ) {
        controls.onColorChanged = onChanged
        if (opacityKey != null && controls.opacity != null) {
            val prefs = ConfigManager.getLocalPrefs(this)
            controls.opacity.progress = prefs.getInt(opacityKey, 100).coerceIn(0, 100)
            controls.opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    refreshColorDisplay(controls)
                    if (fromUser) prefs.edit().putInt(opacityKey, progress.coerceIn(0, 100)).apply()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { showRestartHint() }
            })
        }
        setColor(controls, initialColor)
    }

    private fun setColor(controls: ColorControls, colorString: String) {
        val cleanColor = if (colorString.isNotBlank() && !colorString.startsWith("#")) {
            "#$colorString"
        } else {
            colorString
        }
        val validHex = try {
            val parsed = Color.parseColor(cleanColor)
            String.format("#%02X%02X%02X", Color.red(parsed), Color.green(parsed), Color.blue(parsed))
        } catch (_: Throwable) {
            "#FFFFFF"
        }
        controls.currentColorHex = validHex
        refreshColorDisplay(controls)
    }

    private fun refreshColorDisplay(controls: ColorControls) {
        val color = try {
            Color.parseColor(controls.currentColorHex)
        } catch (_: Throwable) {
            Color.WHITE
        }
        val opacity = controls.opacity?.progress ?: 100
        controls.preview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(BackgroundOpacity.argb(color, opacity))
            setStroke(dp(1), getColor(R.color.card_stroke))
        }
        controls.hexValue.text = controls.currentColorHex
        controls.opacityValue?.text = "$opacity%"
    }

    private fun showColorPickerDialog(controls: ColorControls) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
        }

        // Header: Preview box + Hex code + Tip
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }

        val dialogPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(14)
            }
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dialogHex = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            isClickable = true
            isFocusable = true
            setPadding(0, dp(2), dp(8), dp(2))
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }

        val dialogHint = TextView(this).apply {
            text = "滑动色盘调色 · 点击色号可直接修改"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        }

        infoCol.addView(dialogHex)
        infoCol.addView(dialogHint)
        header.addView(dialogPreview)
        header.addView(infoCol)
        dialogView.addView(header)

        val colorWheel = ColorWheelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dp(12))
            }
        }
        dialogView.addView(colorWheel)

        // Brightness Slider
        val brightnessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val brightnessTitle = TextView(this).apply {
            text = "明度"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
        }
        val sbBrightness = SeekBar(this).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(getColor(R.color.primary))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.primary))
        }
        val tvBrightnessValue = TextView(this).apply {
            setTextColor(getColor(R.color.primary))
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        brightnessRow.addView(brightnessTitle, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        brightnessRow.addView(sbBrightness, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        brightnessRow.addView(tvBrightnessValue, LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT))
        dialogView.addView(brightnessRow)

        // Opacity Slider (if supported)
        var sbDialogOpacity: SeekBar? = null
        var tvDialogOpacityValue: TextView? = null
        if (controls.opacity != null) {
            val opacityRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val opacityTitle = TextView(this).apply {
                text = "不透明度"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            }
            sbDialogOpacity = SeekBar(this).apply {
                max = 100
                progress = controls.opacity.progress
                progressTintList = ColorStateList.valueOf(getColor(R.color.primary))
                thumbTintList = ColorStateList.valueOf(getColor(R.color.primary))
            }
            tvDialogOpacityValue = TextView(this).apply {
                text = "${controls.opacity.progress}%"
                setTextColor(getColor(R.color.primary))
                textSize = 12f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            opacityRow.addView(opacityTitle, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            opacityRow.addView(sbDialogOpacity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            opacityRow.addView(tvDialogOpacityValue, LinearLayout.LayoutParams(dp(38), LinearLayout.LayoutParams.WRAP_CONTENT))
            dialogView.addView(opacityRow)
        }

        // Initialize state
        val initialColor = try {
            Color.parseColor(controls.currentColorHex)
        } catch (_: Throwable) {
            Color.WHITE
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        colorWheel.setColor(initialColor)
        val initialBrightness = (hsv[2] * 100).toInt().coerceIn(0, 100)
        sbBrightness.progress = initialBrightness
        tvBrightnessValue.text = "$initialBrightness%"

        fun updateDialogPreview() {
            val color = colorWheel.getColor()
            val opacity = controls.opacity?.progress ?: 100
            dialogPreview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(BackgroundOpacity.argb(color, opacity))
                setStroke(dp(1), getColor(R.color.card_stroke))
            }
            dialogHex.text = colorWheel.getColorHex()
        }
        updateDialogPreview()

        colorWheel.onColorChangedListener = { _, _, _, v, fromUser ->
            val hex = colorWheel.getColorHex()
            controls.currentColorHex = hex
            updateDialogPreview()
            refreshColorDisplay(controls)
            if (fromUser) {
                controls.onColorChanged?.invoke(hex)
            }
            val bPercent = (v * 100).toInt().coerceIn(0, 100)
            if (sbBrightness.progress != bPercent) {
                sbBrightness.progress = bPercent
                tvBrightnessValue.text = "$bPercent%"
            }
        }

        sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightnessValue.text = "$progress%"
                if (fromUser) {
                    colorWheel.setBrightness(progress / 100f, fromUser = true)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                showRestartHint()
            }
        })

        sbDialogOpacity?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDialogOpacityValue?.text = "$progress%"
                controls.opacity?.progress = progress
                refreshColorDisplay(controls)
                updateDialogPreview()
                if (fromUser && controls.opacityKey != null) {
                    ConfigManager.getLocalPrefs(this@MainActivity)
                        .edit()
                        .putInt(controls.opacityKey, progress.coerceIn(0, 100))
                        .apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                showRestartHint()
            }
        })

        dialogHex.setOnClickListener {
            showHexInputDialog(controls) { newHex ->
                val newColor = try { Color.parseColor(newHex) } catch (_: Throwable) { Color.WHITE }
                colorWheel.setColor(newColor)
                val newHsv = FloatArray(3)
                Color.colorToHSV(newColor, newHsv)
                val newB = (newHsv[2] * 100).toInt().coerceIn(0, 100)
                sbBrightness.progress = newB
                tvBrightnessValue.text = "$newB%"
                updateDialogPreview()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("调整${controls.title}")
            .setView(dialogView)
            .setPositiveButton("完成") { _, _ ->
                showRestartHint()
            }
            .show()
    }

    private fun showHexInputDialog(
        controls: ColorControls,
        onConfirmedExtra: ((String) -> Unit)? = null
    ) {
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
        }

        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val previewBox = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(12)
            }
        }

        val statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            text = "支持 6 位或 8 位 16 进制，如 #1E1E2E"
        }

        previewRow.addView(previewBox)
        previewRow.addView(statusText)
        inputLayout.addView(previewRow)

        val editText = AppCompatEditText(this).apply {
            hint = "#FFFFFF"
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setText(controls.currentColorHex)
            selectAll()
            filters = arrayOf(InputFilter.LengthFilter(9))
        }
        inputLayout.addView(editText)

        fun parseAndPreview(text: String): String? {
            var raw = text.trim()
            if (raw.isEmpty()) return null
            if (!raw.startsWith("#")) {
                raw = "#$raw"
            }
            return try {
                val color = Color.parseColor(raw)
                previewBox.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(color)
                    setStroke(dp(1), getColor(R.color.card_stroke))
                }
                statusText.text = "颜色有效"
                statusText.setTextColor(getColor(R.color.status_active))
                if (raw.length == 7) {
                    raw.uppercase()
                } else if (raw.length == 9) {
                    String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))
                } else {
                    null
                }
            } catch (_: Throwable) {
                previewBox.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.LTGRAY)
                    setStroke(dp(1), getColor(R.color.card_stroke))
                }
                statusText.text = "格式无效 (示例: #1E1E2E)"
                statusText.setTextColor(Color.RED)
                null
            }
        }

        parseAndPreview(controls.currentColorHex)

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                parseAndPreview(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("修改色号 - ${controls.title}")
            .setView(inputLayout)
            .setPositiveButton("确定") { _, _ ->
                val validHex = parseAndPreview(editText.text.toString())
                if (validHex != null) {
                    setColor(controls, validHex)
                    controls.onColorChanged?.invoke(validHex)
                    onConfirmedExtra?.invoke(validHex)
                    showRestartHint()
                } else {
                    Toast.makeText(this, "输入的颜色代码无效，未保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
