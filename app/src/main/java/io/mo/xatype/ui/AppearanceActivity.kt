package io.mo.xatype.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.mo.xatype.R
import io.mo.xatype.config.AppearanceProfiles
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.hooks.BackgroundOpacity
import io.mo.xatype.ui.KeyboardPreviewView.Focus

/**
 * Keyboard appearance settings. Every option opens a floating panel, and the
 * fixed preview at the bottom highlights the part that option changes.
 */
class AppearanceActivity : AppCompatActivity() {

    private class Summary(val text: String, val color: Int? = null)

    private class Option(
        val title: String,
        val subtitle: String,
        val focus: Focus,
        val summary: () -> Summary,
        val build: (LinearLayout) -> Unit
    )

    private class Row(val option: Option, val view: View, val value: TextView, val swatch: View)

    private lateinit var prefs: SharedPreferences
    private var editingDark = false
    private lateinit var preview: KeyboardPreviewView
    private lateinit var previewHint: TextView
    private lateinit var overlay: FrameLayout
    private lateinit var panelCard: View
    private lateinit var panelTitle: TextView
    private lateinit var panelContent: LinearLayout
    private lateinit var styleSwitch: SwitchCompat
    private val rows = mutableListOf<Row>()
    private var panelChanged = false

    // Profile writes go to the same file, so one listener covers both profiles.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (overlay.visibility == View.VISIBLE) panelChanged = true
        refresh()
    }

    private val closePanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePanel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance)
        prefs = ConfigManager.getLocalPrefs(this)
        AppearanceProfiles.initialize(prefs)
        migrateProfiles()

        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        editingDark = savedInstanceState?.getBoolean(STATE_EDITING_DARK) ?: AppearanceProfiles.usesDark(
            prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false),
            prefs.getBoolean(AppearanceProfiles.MANUAL_DARK, false),
            systemDark
        )

        findViewById<Toolbar>(R.id.toolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(getColor(R.color.text_primary))
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            menu.add("重启输入法").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                ImeRestarter.restart(this@AppearanceActivity)
                true
            }
        }

        preview = findViewById(R.id.keyboardPreview)
        previewHint = findViewById(R.id.tvPreviewHint)
        overlay = findViewById(R.id.overlayPanel)
        panelCard = findViewById(R.id.panelCard)
        panelTitle = findViewById(R.id.tvPanelTitle)
        panelContent = findViewById(R.id.panelContent)
        overlay.setOnClickListener { closePanel() }
        preview.setOnClickListener { if (overlay.visibility == View.VISIBLE) closePanel() }
        onBackPressedDispatcher.addCallback(this, closePanelOnBack)
        // While typing a hex value the real keyboard shows the result, so the
        // drawn preview gives its space to the panel.
        val previewContainer = findViewById<View>(R.id.previewContainer)
        val root = previewContainer.parent as View
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val typing = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val visibility = if (typing) View.GONE else View.VISIBLE
            if (previewContainer.visibility != visibility) previewContainer.visibility = visibility
        }

        buildOptions(findViewById(R.id.layoutOptions))
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EDITING_DARK, editingDark)
        super.onSaveInstanceState(outState)
    }

    private fun stylePrefs() = AppearanceProfiles.forProfile(prefs, editingDark)

    /** One-time fixes previously done while binding the controls. */
    private fun migrateProfiles() {
        for (dark in listOf(false, true)) {
            val p = AppearanceProfiles.forProfile(prefs, dark)
            // Image backgrounds have no editor; fall back to glass.
            if (p.getInt(ConfigManager.KEY_BG_TYPE, 0) !in 0..1) {
                p.edit().putInt(ConfigManager.KEY_BG_TYPE, 0).apply()
            }
            // The clipboard card once shared the menu card color.
            if (!p.contains(ConfigManager.KEY_CLIPBOARD_CARD_COLOR)) {
                val legacy = p.getString(ConfigManager.KEY_MENU_CARD_COLOR, "") ?: ""
                if (legacy.isNotBlank()) {
                    p.edit()
                        .putString(ConfigManager.KEY_CLIPBOARD_CARD_COLOR, legacy)
                        .putInt(ConfigManager.KEY_CLIPBOARD_CARD_OPACITY,
                            p.getInt(ConfigManager.KEY_MENU_CARD_OPACITY, 100))
                        .apply()
                }
            }
        }
    }

    // ---------------------------------------------------------------- options

    private fun buildOptions(root: LinearLayout) {
        root.addView(card().apply {
            val row = horizontalRow()
            row.addView(titleBlock("启用键盘外观个性化", "关闭时键盘保持原生样式"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            styleSwitch = SwitchCompat(context).apply {
                isChecked = prefs.getBoolean(ConfigManager.KEY_STYLE_ENABLED, false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(ConfigManager.KEY_STYLE_ENABLED, checked).apply()
                    showRestartHint()
                }
            }
            row.addView(styleSwitch)
            row.setOnClickListener { styleSwitch.toggle() }
            addView(row)
        })

        section(root, "配置", listOf(
            Option("深浅色配置", "选择正在编辑的配置，及是否跟随系统切换", Focus.NONE,
                { Summary((if (editingDark) "深色" else "浅色") +
                    if (prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false)) " · 跟随系统" else "") },
                ::buildProfilePanel)
        ))

        section(root, "形状与背景", listOf(
            Option("边角圆角弧度", "键盘顶部两角的圆角大小", Focus.CORNER,
                { Summary("${stylePrefs().getInt(ConfigManager.KEY_CORNER_RADIUS, 16)} dp") }) { panel ->
                slider(panel, "圆角弧度", 0, 40, stylePrefs().getInt(ConfigManager.KEY_CORNER_RADIUS, 16), { "$it dp" }) {
                    stylePrefs().edit().putInt(ConfigManager.KEY_CORNER_RADIUS, it).apply()
                }
            },
            Option("背景类型", "动态毛玻璃或纯色背景", Focus.BACKGROUND, {
                val p = stylePrefs()
                if (p.getInt(ConfigManager.KEY_BG_TYPE, 0) == 1) {
                    Summary("纯色", parseColor(p.getString(ConfigManager.KEY_BG_COLOR, "")))
                } else Summary("动态毛玻璃")
            }, ::buildBackgroundPanel),
            Option("背景不透明度", "100% 保留原背景，越低越透明", Focus.BACKGROUND,
                { Summary("${stylePrefs().getInt(ConfigManager.KEY_OPACITY, 85)}%") }) { panel ->
                val warning = warningText(panel)
                slider(panel, "背景不透明度", 0, 100, stylePrefs().getInt(ConfigManager.KEY_OPACITY, 85), { "$it%" }) {
                    stylePrefs().edit().putInt(ConfigManager.KEY_OPACITY, it).apply()
                    updateWarning(warning)
                }
                panel.removeView(warning)
                panel.addView(warning)
                updateWarning(warning)
            },
            Option("毛玻璃模糊强度", "仅对动态毛玻璃背景生效", Focus.BACKGROUND,
                { Summary("${stylePrefs().getInt(ConfigManager.KEY_BLUR_RADIUS, 50)} dp") }) { panel ->
                slider(panel, "模糊强度", 20, 100, stylePrefs().getInt(ConfigManager.KEY_BLUR_RADIUS, 50), { "$it dp" }) {
                    stylePrefs().edit().putInt(ConfigManager.KEY_BLUR_RADIUS, it).apply()
                }
                if (stylePrefs().getInt(ConfigManager.KEY_BG_TYPE, 0) != 0) {
                    note(panel, "当前是纯色背景，模糊强度不会生效。")
                }
            }
        ))

        section(root, "文字与可读性", listOf(
            colorOption("按键字体颜色", "按键文字、候选字与工具栏图标", Focus.TEXT,
                ConfigManager.KEY_TEXT_COLOR, null, "#FFFFFF",
                "关闭时根据键盘底色自动使用黑色或白色", "自动"),
            Option("可读性底色", "透明键盘叠在任何应用上都能看清", Focus.SCRIM, {
                if (!prefs.getBoolean(ConfigManager.KEY_SCRIM_ENABLED, true)) Summary("关闭") else {
                    val p = stylePrefs()
                    val color = parseColor(p.getString(ConfigManager.KEY_SCRIM_COLOR, ""))
                    Summary("${p.getInt(ConfigManager.KEY_SCRIM_OPACITY, ConfigManager.DEFAULT_SCRIM_OPACITY)}%" +
                        if (color == null) " · 自动" else "", color)
                }
            }, ::buildScrimPanel)
        ))

        section(root, "键帽", listOf(
            colorOption("字母键键帽颜色", "字母、数字、空格等主键的常态键帽", Focus.LETTER_KEY,
                ConfigManager.KEY_LETTER_KEYCAP_COLOR, ConfigManager.KEY_LETTER_KEYCAP_OPACITY, "#FFFFFF",
                "关闭时使用输入法原生键帽颜色", "原生"),
            colorOption("功能键键帽颜色", "分词、退格、符号、123、中/英等功能键", Focus.FUNCTION_KEY,
                ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY, "#FFFFFF",
                "关闭时使用输入法原生键帽颜色", "原生")
        ))

        section(root, "面板卡片", listOf(
            colorOption("菜单功能卡片颜色", "菜单键展开后的语音、表情等功能卡片", Focus.MENU_CARD,
                ConfigManager.KEY_MENU_CARD_COLOR, ConfigManager.KEY_MENU_CARD_OPACITY, "#FFFFFF",
                "关闭时使用输入法原生卡片颜色", "原生"),
            colorOption("剪贴板卡片颜色", "剪贴板与常用语中的内容卡片", Focus.CLIPBOARD_CARD,
                ConfigManager.KEY_CLIPBOARD_CARD_COLOR, ConfigManager.KEY_CLIPBOARD_CARD_OPACITY, "#FFFFFF",
                "关闭时使用输入法原生卡片颜色", "原生")
        ))
    }

    /** An optional color: empty means the native or automatic color. */
    private fun colorOption(
        title: String,
        subtitle: String,
        focus: Focus,
        key: String,
        opacityKey: String?,
        defaultHex: String,
        offHint: String,
        offSummary: String
    ) = Option(title, subtitle, focus, {
        val p = stylePrefs()
        val color = parseColor(p.getString(key, ""))
        if (color == null) Summary(offSummary) else Summary(
            hex(color) + (opacityKey?.let { " · ${p.getInt(it, 100)}%" } ?: ""), color)
    }) { panel ->
        val p = stylePrefs()
        val saved = p.getString(key, "") ?: ""
        val editor = ColorEditor(
            panel,
            saved.ifBlank { defaultHex },
            opacityKey?.let { p.getInt(it, 100).coerceIn(0, 100) },
            onColor = { stylePrefs().edit().putString(key, it).apply() },
            onOpacity = { value -> opacityKey?.let { stylePrefs().edit().putInt(it, value).apply() } }
        )
        val toggle = switchRow(panel, "自定义颜色", offHint, saved.isNotBlank()) { checked ->
            editor.root.visibility = if (checked) View.VISIBLE else View.GONE
            stylePrefs().edit().putString(key, if (checked) editor.hex else "").apply()
        }
        // The switch reads better above the editor.
        panel.removeView(toggle)
        panel.addView(toggle, 0)
        editor.root.visibility = if (saved.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun buildProfilePanel(panel: LinearLayout) {
        val hint = note(panel, "")
        fun updateHint(follow: Boolean) {
            hint.text = if (follow) "键盘会跟随系统深浅色自动切换，下方选择要编辑哪一套配置。"
            else "键盘固定使用所选配置，两套配置分别保存。"
        }
        val follow = prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false)
        switchRow(panel, "跟随系统切换深浅色", "开启后浅色和深色模式下分别使用对应配置", follow) { checked ->
            prefs.edit().putBoolean(AppearanceProfiles.FOLLOW_SYSTEM, checked)
                .putBoolean(AppearanceProfiles.MANUAL_DARK, editingDark).apply()
            updateHint(checked)
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val light = radio("浅色配置")
        val dark = radio("深色配置")
        group.addView(light, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        group.addView(dark, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        group.check(if (editingDark) dark.id else light.id)
        group.setOnCheckedChangeListener { _, id ->
            editingDark = id == dark.id
            if (!prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false)) {
                prefs.edit().putBoolean(AppearanceProfiles.MANUAL_DARK, editingDark).apply()
            }
            panelChanged = true
            refresh()
        }
        panel.addView(group)
        panel.removeView(hint)
        panel.addView(hint)
        updateHint(follow)
    }

    private fun buildBackgroundPanel(panel: LinearLayout) {
        val p = stylePrefs()
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val glass = radio("动态毛玻璃")
        val solid = radio("纯色背景")
        group.addView(glass, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        group.addView(solid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(group)

        val solidConfig = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(solidConfig)
        solidConfig.addView(label("常用预设色", 12f, R.color.text_secondary).apply { setPadding(0, dp(8), 0, dp(6)) })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        solidConfig.addView(presets)
        val editor = ColorEditor(solidConfig, p.getString(ConfigManager.KEY_BG_COLOR, "#1E1E2E") ?: "#1E1E2E", null,
            onColor = { stylePrefs().edit().putString(ConfigManager.KEY_BG_COLOR, it).apply() },
            onOpacity = {})
        listOf("深灰" to "#1E1E2E", "纯黑" to "#000000", "晨曦蓝" to "#1E293B",
            "暗夜紫" to "#2E1065", "极简白" to "#F1F5F9").forEachIndexed { i, (name, value) ->
            val color = Color.parseColor(value)
            presets.addView(TextView(this).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (luma(color) < 150) Color.WHITE else 0xff1e293b.toInt())
                background = rounded(color, 8)
                setOnClickListener { editor.setHex(value, notify = true) }
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i > 0) marginStart = dp(6) })
        }

        val type = p.getInt(ConfigManager.KEY_BG_TYPE, 0)
        group.check(if (type == 1) solid.id else glass.id)
        solidConfig.visibility = if (type == 1) View.VISIBLE else View.GONE
        group.setOnCheckedChangeListener { _, id ->
            val newType = if (id == solid.id) 1 else 0
            stylePrefs().edit().putInt(ConfigManager.KEY_BG_TYPE, newType).apply()
            solidConfig.visibility = if (newType == 1) View.VISIBLE else View.GONE
        }
    }

    private fun buildScrimPanel(panel: LinearLayout) {
        val p = stylePrefs()
        val config = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val warning = warningText(panel)
        val enabled = prefs.getBoolean(ConfigManager.KEY_SCRIM_ENABLED, true)
        val toggle = switchRow(panel, "启用可读性底色",
            "在键盘背景下垫一层底色；背景完全不透明时看不到。开关对深浅色配置共用。", enabled) { checked ->
            prefs.edit().putBoolean(ConfigManager.KEY_SCRIM_ENABLED, checked).apply()
            config.visibility = if (checked) View.VISIBLE else View.GONE
            updateWarning(warning)
        }
        panel.removeView(warning)
        panel.addView(warning)
        panel.addView(config)
        config.visibility = if (enabled) View.VISIBLE else View.GONE

        slider(config, "底色不透明度", 0, 100,
            p.getInt(ConfigManager.KEY_SCRIM_OPACITY, ConfigManager.DEFAULT_SCRIM_OPACITY).coerceIn(0, 100), { "$it%" }) {
            stylePrefs().edit().putInt(ConfigManager.KEY_SCRIM_OPACITY, it).apply()
            updateWarning(warning)
        }
        val saved = p.getString(ConfigManager.KEY_SCRIM_COLOR, "") ?: ""
        val editor = ColorEditor(config, saved.ifBlank { "#121212" }, null,
            onColor = { stylePrefs().edit().putString(ConfigManager.KEY_SCRIM_COLOR, it).apply() },
            onOpacity = {})
        val custom = switchRow(config, "自定义底色颜色", "关闭时按字体颜色自动取色：浅色字配深底，深色字配浅底",
            saved.isNotBlank()) { checked ->
            editor.root.visibility = if (checked) View.VISIBLE else View.GONE
            stylePrefs().edit().putString(ConfigManager.KEY_SCRIM_COLOR, if (checked) editor.hex else "").apply()
        }
        config.removeView(editor.root)
        config.addView(editor.root)
        editor.root.visibility = if (saved.isNotBlank()) View.VISIBLE else View.GONE
        custom.setPadding(0, dp(8), 0, 0)
        updateWarning(warning)
    }

    private fun warningText(panel: LinearLayout) = label("", 12f, R.color.status_inactive).apply {
        setPadding(0, dp(8), 0, 0)
        visibility = View.GONE
        panel.addView(this)
    }

    /** Warn when the keyboard, scrim included, is mostly see-through. */
    private fun updateWarning(view: TextView) {
        val p = stylePrefs()
        val opacity = p.getInt(ConfigManager.KEY_OPACITY, 85).coerceIn(0, 100)
        val scrim = if (prefs.getBoolean(ConfigManager.KEY_SCRIM_ENABLED, true)) {
            p.getInt(ConfigManager.KEY_SCRIM_OPACITY, ConfigManager.DEFAULT_SCRIM_OPACITY).coerceIn(0, 100)
        } else 0
        val effective = opacity + scrim * (100 - opacity) / 100
        view.visibility = if (effective < 40) View.VISIBLE else View.GONE
        view.text = "键盘整体不透明度约 $effective%，在浅色或深色背景的应用中，" +
            "按键和候选字可能看不清。建议开启可读性底色并调到 30% 以上。"
    }

    // ------------------------------------------------------------ panel state

    private fun openPanel(row: Row) {
        if (!styleSwitch.isChecked && row.option.focus != Focus.NONE) {
            Toast.makeText(this, "请先启用键盘外观个性化", Toast.LENGTH_SHORT).show()
            return
        }
        panelContent.removeAllViews()
        panelTitle.text = row.option.title
        row.option.build(panelContent)
        panelChanged = false
        preview.focus = row.option.focus
        closePanelOnBack.isEnabled = true
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(160).start()
        panelCard.scaleX = 0.94f
        panelCard.scaleY = 0.94f
        panelCard.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    private fun closePanel() {
        if (overlay.visibility != View.VISIBLE) return
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        closePanelOnBack.isEnabled = false
        preview.focus = Focus.NONE
        overlay.animate().alpha(0f).setDuration(140).withEndAction {
            overlay.visibility = View.GONE
            panelContent.removeAllViews()
        }.start()
        if (panelChanged) showRestartHint()
    }

    private fun refresh() {
        val style = readStyle()
        preview.style = style
        previewHint.text = "实时预览 · 正在编辑" + (if (editingDark) "深色" else "浅色") + "配置" +
            if (!style.enabled) " · 个性化未启用，显示原生样式" else ""
        rows.forEach { row ->
            val summary = row.option.summary()
            row.value.text = summary.text
            row.swatch.visibility = if (summary.color != null) View.VISIBLE else View.GONE
            summary.color?.let { row.swatch.background = swatchDrawable(it, dp(9).toFloat()) }
            val active = style.enabled || row.option.focus == Focus.NONE
            row.view.alpha = if (active) 1f else 0.45f
        }
    }

    private fun readStyle(): KeyboardPreviewView.Style {
        val p = stylePrefs()
        fun color(key: String) = parseColor(p.getString(key, ""))
        return KeyboardPreviewView.Style(
            enabled = prefs.getBoolean(ConfigManager.KEY_STYLE_ENABLED, false),
            dark = editingDark,
            cornerRadiusDp = p.getInt(ConfigManager.KEY_CORNER_RADIUS, 16),
            opacity = p.getInt(ConfigManager.KEY_OPACITY, 85).coerceIn(0, 100),
            blurDp = p.getInt(ConfigManager.KEY_BLUR_RADIUS, 50),
            bgType = p.getInt(ConfigManager.KEY_BG_TYPE, 0),
            bgColor = color(ConfigManager.KEY_BG_COLOR) ?: 0xff1e1e2e.toInt(),
            textColor = color(ConfigManager.KEY_TEXT_COLOR),
            scrimEnabled = prefs.getBoolean(ConfigManager.KEY_SCRIM_ENABLED, true),
            scrimColor = color(ConfigManager.KEY_SCRIM_COLOR),
            scrimOpacity = p.getInt(ConfigManager.KEY_SCRIM_OPACITY, ConfigManager.DEFAULT_SCRIM_OPACITY).coerceIn(0, 100),
            letterKey = color(ConfigManager.KEY_LETTER_KEYCAP_COLOR),
            letterOpacity = p.getInt(ConfigManager.KEY_LETTER_KEYCAP_OPACITY, 100),
            functionKey = color(ConfigManager.KEY_FUNCTION_KEYCAP_COLOR),
            functionOpacity = p.getInt(ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY, 100),
            menuCard = color(ConfigManager.KEY_MENU_CARD_COLOR),
            menuOpacity = p.getInt(ConfigManager.KEY_MENU_CARD_OPACITY, 100),
            clipboardCard = color(ConfigManager.KEY_CLIPBOARD_CARD_COLOR),
            clipboardOpacity = p.getInt(ConfigManager.KEY_CLIPBOARD_CARD_OPACITY, 100)
        )
    }

    private var lastToastTime = 0L
    private fun showRestartHint() {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 3000) {
            lastToastTime = now
            Toast.makeText(this, "配置已更新，重启超级小爱输入法生效", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------- view kit

    private fun section(root: LinearLayout, title: String, options: List<Option>) {
        root.addView(label(title, 13f, R.color.text_secondary).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(18), 0, dp(8))
        })
        val card = card()
        options.forEachIndexed { index, option ->
            if (index > 0) card.addView(divider())
            card.addView(optionRow(option))
        }
        root.addView(card)
    }

    private fun optionRow(option: Option): View {
        val row = horizontalRow()
        row.addView(titleBlock(option.title, option.subtitle),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val swatch = View(this)
        row.addView(swatch, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
        val value = label("", 13f, R.color.primary).apply {
            setPadding(dp(6), 0, dp(4), 0)
            maxWidth = dp(130)
            maxLines = 1
        }
        row.addView(value)
        row.addView(label("›", 20f, R.color.text_secondary))
        val item = Row(option, row, value, swatch)
        row.setOnClickListener { openPanel(item) }
        rows += item
        return row
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        isFocusable = true
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        foreground = getDrawable(outValue.resourceId)
    }

    private fun titleBlock(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 15f, R.color.text_primary).apply { setTypeface(typeface, Typeface.BOLD) })
        if (subtitle.isNotEmpty()) {
            addView(label(subtitle, 11f, R.color.text_secondary).apply { setPadding(0, dp(2), 0, 0) })
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.card_stroke))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        }
    }

    private fun label(text: String, size: Float, colorRes: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(colorRes))
    }

    private fun note(panel: LinearLayout, text: String) = label(text, 12f, R.color.text_secondary).apply {
        setPadding(0, dp(8), 0, 0)
        panel.addView(this)
    }

    private fun radio(text: String) = RadioButton(this).apply {
        id = View.generateViewId()
        this.text = text
        minHeight = dp(48)
        setTextColor(getColor(R.color.text_primary))
        buttonTintList = ColorStateList.valueOf(getColor(R.color.primary))
    }

    private fun switchRow(
        panel: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(titleBlock(title, subtitle).apply {
            (getChildAt(0) as TextView).textSize = 14f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val switch = SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(switch)
        row.setOnClickListener { switch.toggle() }
        panel.addView(row)
        return row
    }

    private fun slider(
        panel: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        header.addView(label(title, 14f, R.color.text_primary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = label(format(value.coerceIn(min, max)), 13f, R.color.primary).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(valueView)
        panel.addView(header)
        panel.addView(SeekBar(this).apply {
            this.max = max
            this.min = min
            progress = value.coerceIn(min, max)
            contentDescription = title
            progressTintList = ColorStateList.valueOf(getColor(R.color.primary))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.primary))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueView.text = format(progress)
                    if (fromUser) onChange(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
    }

    /** Inline wheel, brightness, hex field and optional opacity; changes save as they happen. */
    private inner class ColorEditor(
        panel: LinearLayout,
        initialHex: String,
        private var opacity: Int?,
        private val onColor: (String) -> Unit,
        private val onOpacity: (Int) -> Unit
    ) {
        val root = LinearLayout(this@AppearanceActivity).apply { orientation = LinearLayout.VERTICAL }
        var hex = hex(parseColor(initialHex) ?: Color.WHITE)
            private set
        private val swatch = View(this@AppearanceActivity)
        private val wheel = ColorWheelView(this@AppearanceActivity)
        private val brightness = SeekBar(this@AppearanceActivity)
        private val brightnessValue = label("", 12f, R.color.primary)
        private var syncingText = false
        private val input = AppCompatEditText(this@AppearanceActivity).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setSingleLine()
            hint = "#RRGGBB"
        }

        init {
            val header = LinearLayout(this@AppearanceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            header.addView(swatch, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            header.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(header)
            root.addView(wheel, LinearLayout.LayoutParams(dp(200), dp(200)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            })
            root.addView(sliderRow("明度", brightness, brightnessValue))
            brightness.max = 100
            opacity?.let { value ->
                val bar = SeekBar(this@AppearanceActivity).apply {
                    max = 100
                    progress = value
                }
                val text = label("$value%", 12f, R.color.primary)
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        text.text = "$progress%"
                        if (!fromUser) return
                        opacity = progress
                        updateSwatch()
                        onOpacity(progress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
                root.addView(sliderRow("不透明度", bar, text))
            }

            wheel.onColorChangedListener = { _, _, _, v, fromUser ->
                updateBrightness(v)
                if (fromUser) {
                    hex = wheel.getColorHex()
                    setInput(hex)
                    updateSwatch()
                    onColor(hex)
                }
            }
            brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    brightnessValue.text = "$progress%"
                    if (fromUser) wheel.setBrightness(progress / 100f, fromUser = true)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (syncingText) return
                    val raw = s?.toString()?.trim()?.removePrefix("#") ?: return
                    if (raw.length != 6) return
                    val color = parseColor("#$raw") ?: return
                    setHex(hex(color), notify = true, fromInput = true)
                }
            })

            setHex(hex, notify = false)
            panel.addView(root)
        }

        fun setHex(value: String, notify: Boolean, fromInput: Boolean = false) {
            val color = parseColor(value) ?: return
            hex = hex(color)
            wheel.setColor(color)
            if (!fromInput) setInput(hex)
            updateSwatch()
            if (notify) onColor(hex)
        }

        private fun setInput(value: String) {
            syncingText = true
            input.setText(value)
            input.setSelection(value.length)
            syncingText = false
        }

        private fun updateBrightness(v: Float) {
            val percent = (v * 100).toInt().coerceIn(0, 100)
            if (brightness.progress != percent) brightness.progress = percent
            brightnessValue.text = "$percent%"
        }

        private fun updateSwatch() {
            val color = parseColor(hex) ?: Color.WHITE
            swatch.background = swatchDrawable(BackgroundOpacity.argb(color, opacity ?: 100), dp(10).toFloat())
        }

        private fun sliderRow(title: String, bar: SeekBar, value: TextView) = LinearLayout(this@AppearanceActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            bar.progressTintList = ColorStateList.valueOf(getColor(R.color.primary))
            bar.thumbTintList = ColorStateList.valueOf(getColor(R.color.primary))
            bar.contentDescription = title
            value.gravity = Gravity.END
            addView(label(title, 13f, R.color.text_secondary), LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(value, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun swatchDrawable(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        setStroke(dp(1), getColor(R.color.card_stroke))
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_EDITING_DARK = "editing_dark"

        fun parseColor(value: String?): Int? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try {
                Color.parseColor(if (it.startsWith("#")) it else "#$it")
            } catch (_: Throwable) {
                null
            }
        }

        fun hex(color: Int) = String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color))

        private fun luma(color: Int) =
            (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000

        /** Whether the main screen should report the style as enabled. */
        fun isEnabled(context: Context) =
            ConfigManager.getLocalPrefs(context).getBoolean(ConfigManager.KEY_STYLE_ENABLED, false)
    }
}
