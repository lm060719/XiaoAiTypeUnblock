package io.mo.xatype.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.mo.xatype.R
import io.mo.xatype.config.AppearancePresetStore
import io.mo.xatype.config.AppearanceProfiles
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.hooks.BackgroundOpacity
import io.mo.xatype.ui.KeyboardPreviewView.Focus

/**
 * The appearance tab. Every option opens an edit panel, and the floating
 * preview highlights the part that option changes. The preview can be
 * dragged anywhere, resized or folded, and the panel makes room for it.
 */
class AppearancePage(
    private val activity: AppCompatActivity,
    private val root: View,
    savedInstanceState: Bundle?,
    /** Called after a change that applies once the input method restarts. */
    private val onConfigChanged: () -> Unit
) : ContextWrapper(activity) {

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
    private val uiPrefs: SharedPreferences = activity.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
    private val kit = SettingsKit(activity)
    private var editingDark = false
    private lateinit var preview: KeyboardPreviewView
    private lateinit var previewHint: TextView
    private lateinit var floating: FloatingPreviewCard
    private lateinit var sizeButton: ImageButton
    private lateinit var collapseButton: ImageButton
    private lateinit var options: LinearLayout
    private var previewLarge = true
    private var previewCollapsed = false
    private lateinit var overlay: FrameLayout
    private lateinit var panelCard: View
    private lateinit var panelTitle: TextView
    private lateinit var panelContent: LinearLayout
    private lateinit var styleSwitch: SwitchCompat
    private val rows = mutableListOf<Row>()
    private var panelChanged = false

    // Profile writes go to the same file, so one listener covers both profiles.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A scheme name or snapshot is metadata, not a live keyboard change.
        if (overlay.visibility == View.VISIBLE && key != AppearancePresetStore.STORAGE_KEY) panelChanged = true
        refresh()
    }

    private val closePanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closePanel()
    }

    init {
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

        preview = root.findViewById(R.id.keyboardPreview)
        previewHint = root.findViewById(R.id.tvPreviewHint)
        overlay = root.findViewById(R.id.overlayPanel)
        panelCard = root.findViewById(R.id.panelCard)
        panelTitle = root.findViewById(R.id.tvPanelTitle)
        panelContent = root.findViewById(R.id.panelContent)
        overlay.setOnClickListener { closePanel() }
        activity.onBackPressedDispatcher.addCallback(activity, closePanelOnBack)
        // While typing a hex value the real keyboard shows the result, so the
        // drawn preview gets out of the panel's way.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val typing = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val visibility = if (typing) View.INVISIBLE else View.VISIBLE
            if (floating.visibility != visibility) {
                floating.visibility = visibility
                // The IME changes the space available below the preview as well as its visibility.
                if (overlay.visibility == View.VISIBLE) placePanel()
            }
        }

        options = root.findViewById(R.id.layoutOptions)
        initFloatingPreview()
        buildOptions(options)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean(STATE_EDITING_DARK, editingDark)
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
        root.addView(kit.sectionHeader("总开关", first = true))
        root.addView(card().apply {
            val row = horizontalRow()
            row.addView(titleBlock("启用键盘外观个性化", ""),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            styleSwitch = SwitchCompat(context).apply {
                isChecked = prefs.getBoolean(ConfigManager.KEY_STYLE_ENABLED, false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(ConfigManager.KEY_STYLE_ENABLED, checked).apply()
                    SettingsKit.confirmHaptic(this)
                    onConfigChanged()
                }
            }
            row.addView(styleSwitch)
            row.setOnClickListener { styleSwitch.toggle() }
            addView(row)
        })

        section(root, "配置", listOf(
            Option("深浅色配置", "", Focus.NONE,
                { Summary((if (editingDark) "深色" else "浅色") +
                    if (prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false)) " · 跟随系统" else "") },
                ::buildProfilePanel),
            Option("一键外观预设", "快速尝试不同风格", Focus.NONE,
                { Summary("内置 " + AppearancePresetStore.presets.size + " 套") },
                ::buildPresetPanel),
            Option("我的外观配置", "命名保存、切换与管理多套外观", Focus.NONE,
                { Summary(AppearancePresetStore.saved(prefs).size.toString() + " 套") },
                ::buildSchemePanel)
        ))

        section(root, "形状与背景", listOf(
            Option("边角圆角弧度", "", Focus.CORNER,
                { Summary("${stylePrefs().getInt(ConfigManager.KEY_CORNER_RADIUS, 16)} dp") }) { panel ->
                slider(panel, "圆角弧度", 0, 40, stylePrefs().getInt(ConfigManager.KEY_CORNER_RADIUS, 16), { "$it dp" }) {
                    stylePrefs().edit().putInt(ConfigManager.KEY_CORNER_RADIUS, it).apply()
                }
            },
            Option("背景类型", "", Focus.BACKGROUND, {
                val p = stylePrefs()
                if (p.getInt(ConfigManager.KEY_BG_TYPE, 0) == 1) {
                    Summary("纯色", parseColor(p.getString(ConfigManager.KEY_BG_COLOR, "")))
                } else Summary("动态毛玻璃")
            }, ::buildBackgroundPanel),
            Option("背景不透明度", "", Focus.BACKGROUND,
                { Summary("${stylePrefs().getInt(ConfigManager.KEY_OPACITY, 85)}%") }) { panel ->
                val warning = warningText(panel)
                val combined = note(panel, "")
                slider(panel, "背景不透明度", 0, 100, stylePrefs().getInt(ConfigManager.KEY_OPACITY, 85), { "$it%" }) {
                    stylePrefs().edit().putInt(ConfigManager.KEY_OPACITY, it).apply()
                    updateWarning(warning)
                    updateCombinedOpacity(combined)
                }
                panel.removeView(combined)
                panel.addView(combined)
                panel.removeView(warning)
                panel.addView(warning)
                updateWarning(warning)
                updateCombinedOpacity(combined)
            },
            Option("毛玻璃模糊强度", "", Focus.BACKGROUND,
                { Summary("${stylePrefs().getInt(ConfigManager.KEY_BLUR_RADIUS, 50)} dp") }) { panel ->
                slider(panel, "模糊强度", 20, 100, stylePrefs().getInt(ConfigManager.KEY_BLUR_RADIUS, 50), { "$it dp" }) {
                    stylePrefs().edit().putInt(ConfigManager.KEY_BLUR_RADIUS, it).apply()
                }
                if (stylePrefs().getInt(ConfigManager.KEY_BG_TYPE, 0) != 0) {
                    note(panel, "纯色背景下不生效")
                }
            }
        ))

        section(root, "文字与可读性", listOf(
            colorOption("按键字体颜色", "含候选字与工具栏图标", Focus.TEXT,
                ConfigManager.KEY_TEXT_COLOR, null, "#FFFFFF",
                "自动"),
            Option("可读性底色", "让透明键盘的文字保持清晰", Focus.SCRIM, {
                if (!prefs.getBoolean(ConfigManager.KEY_SCRIM_ENABLED, true)) Summary("关闭") else {
                    val p = stylePrefs()
                    val color = parseColor(p.getString(ConfigManager.KEY_SCRIM_COLOR, ""))
                    Summary("${p.getInt(ConfigManager.KEY_SCRIM_OPACITY, ConfigManager.DEFAULT_SCRIM_OPACITY)}%" +
                        if (color == null) " · 自动" else "", color)
                }
            }, ::buildScrimPanel)
        ))

        section(root, "键帽", listOf(
            colorOption("字母键键帽颜色", "", Focus.LETTER_KEY,
                ConfigManager.KEY_LETTER_KEYCAP_COLOR, ConfigManager.KEY_LETTER_KEYCAP_OPACITY, "#FFFFFF",
                "原生"),
            colorOption("功能键键帽颜色", "", Focus.FUNCTION_KEY,
                ConfigManager.KEY_FUNCTION_KEYCAP_COLOR, ConfigManager.KEY_FUNCTION_KEYCAP_OPACITY, "#FFFFFF",
                "原生")
        ))

        section(root, "面板卡片", listOf(
            colorOption("菜单功能卡片颜色", "菜单键展开后的功能卡片", Focus.MENU_CARD,
                ConfigManager.KEY_MENU_CARD_COLOR, ConfigManager.KEY_MENU_CARD_OPACITY, "#FFFFFF",
                "原生"),
            colorOption("剪贴板卡片颜色", "含常用语卡片", Focus.CLIPBOARD_CARD,
                ConfigManager.KEY_CLIPBOARD_CARD_COLOR, ConfigManager.KEY_CLIPBOARD_CARD_OPACITY, "#FFFFFF",
                "原生")
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
        val toggle = switchRow(panel, "自定义颜色", "", saved.isNotBlank()) { checked ->
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
            hint.text = if (follow) "选择要编辑的配置" else "键盘固定使用所选配置"
        }
        val follow = prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false)
        switchRow(panel, "跟随系统切换深浅色", "", follow) { checked ->
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


    // --------------------------------------------- preset and scheme library

    private fun buildPresetPanel(panel: LinearLayout)
    {
        AppearancePresetStore.presets.forEach { preset ->
            panel.addView(kit.navRow(preset.name, preset.description, null) {
                AlertDialog.Builder(activity)
                    .setTitle("应用「" + preset.name + "」")
                    .setMessage("将替换当前" + (if (editingDark) "深色" else "浅色") +
                        "外观的全部颜色、透明度和圆角设置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("应用") { _, _ ->
                        if (AppearancePresetStore.applyPreset(prefs, editingDark, preset.id))
                        {
                            panelChanged = true
                            refresh()
                            Toast.makeText(this, "已应用预设，重启输入法后生效", Toast.LENGTH_SHORT).show()
                        }
                        else
                        {
                            Toast.makeText(this, "应用预设失败", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            })
        }
    }

    private fun buildSchemePanel(panel: LinearLayout)
    {
        showSchemes(panel)
    }

    private fun showSchemes(panel: LinearLayout)
    {
        panel.removeAllViews()
        panel.addView(kit.navRow("保存当前为新配置", null, null) {
            askSchemeName(panel, "保存新配置", "") { name ->
                AppearancePresetStore.save(prefs, name)
            }
        })

        val schemes = AppearancePresetStore.saved(prefs)
        if (schemes.isEmpty())
        {
            note(panel, "还没有保存的配置，先调整外观，再点击上方保存。")
            return
        }
        note(panel, "已保存 " + schemes.size + " 套 · 点击名称加载配置")
        schemes.forEach { scheme ->
            panel.addView(kit.navRow(scheme.name, "包含浅色与深色外观 · 点击切换", null) {
                AlertDialog.Builder(activity)
                    .setTitle("加载「" + scheme.name + "」")
                    .setMessage("这将覆盖当前未保存的浅色、深色外观及跟随系统设置，是否继续？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("加载") { _, _ ->
                        if (AppearancePresetStore.applySaved(prefs, scheme.id))
                        {
                            val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                                Configuration.UI_MODE_NIGHT_YES
                            editingDark = AppearanceProfiles.usesDark(
                                prefs.getBoolean(AppearanceProfiles.FOLLOW_SYSTEM, false),
                                prefs.getBoolean(AppearanceProfiles.MANUAL_DARK, false),
                                systemDark)
                            panelChanged = true
                            refresh()
                            Toast.makeText(this, "配置已加载，重启输入法后生效", Toast.LENGTH_SHORT).show()
                        }
                        else
                        {
                            Toast.makeText(this, "读取配置失败", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(schemeAction("覆盖保存") {
                AlertDialog.Builder(activity)
                    .setTitle("覆盖「" + scheme.name + "」")
                    .setMessage("将用当前浅色、深色外观替换这套已保存的配置。此操作不可撤销。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("覆盖") { _, _ ->
                        if (AppearancePresetStore.overwrite(prefs, scheme.id))
                        {
                            showSchemes(panel)
                            Toast.makeText(this, "已更新保存的配置", Toast.LENGTH_SHORT).show()
                        }
                        else Toast.makeText(this, "覆盖保存失败", Toast.LENGTH_SHORT).show()
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(schemeAction("重命名") {
                askSchemeName(panel, "重命名配置", scheme.name) { name ->
                    AppearancePresetStore.rename(prefs, scheme.id, name)
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(schemeAction("删除") {
                AlertDialog.Builder(activity)
                    .setTitle("删除「" + scheme.name + "」")
                    .setMessage("只删除这一套存档，不会改变当前键盘外观。删除后不能恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        if (AppearancePresetStore.delete(prefs, scheme.id))
                        {
                            showSchemes(panel)
                            Toast.makeText(this, "配置已删除", Toast.LENGTH_SHORT).show()
                        }
                        else Toast.makeText(this, "删除配置失败", Toast.LENGTH_SHORT).show()
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            panel.addView(actions)
        }
    }

    private fun schemeAction(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumHeight = dp(44)
        setOnClickListener { onClick() }
    }

    private fun askSchemeName(panel: LinearLayout, title: String, initial: String,
                              onSave: (String) -> Boolean)
    {
        val input = AppCompatEditText(activity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(30))
            hint = "例如：日常通透、深夜护眼"
            setText(initial)
            setSelection(text?.length ?: 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (onSave(input.text?.toString() ?: ""))
                {
                    dialog.dismiss()
                    showSchemes(panel)
                    Toast.makeText(this, "配置保存成功", Toast.LENGTH_SHORT).show()
                }
                else
                {
                    input.error = "名称不能为空、不能与其他配置重名，或保存失败"
                }
            }
        }
        dialog.show()
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
        val toggle = switchRow(panel, "启用可读性底色", "深浅色配置共用", enabled) { checked ->
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
        val custom = switchRow(config, "自定义底色颜色", "", saved.isNotBlank()) { checked ->
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

    /** Scrim opacity in effect, 0 when it is switched off. */
    private fun activeScrimOpacity(): Int =
        if (prefs.getBoolean(ConfigManager.KEY_SCRIM_ENABLED, true)) {
            stylePrefs().getInt(ConfigManager.KEY_SCRIM_OPACITY, ConfigManager.DEFAULT_SCRIM_OPACITY).coerceIn(0, 100)
        } else 0

    /** Background and scrim stack, so the keyboard is at least as opaque as the scrim. */
    private fun effectiveOpacity(): Int {
        val opacity = stylePrefs().getInt(ConfigManager.KEY_OPACITY, 85).coerceIn(0, 100)
        return opacity + activeScrimOpacity() * (100 - opacity) / 100
    }

    private fun updateCombinedOpacity(view: TextView) {
        val scrim = activeScrimOpacity()
        // A low result is already reported by the warning below.
        view.visibility = if (scrim > 0 && (scrim >= 100 || effectiveOpacity() >= LOW_OPACITY)) View.VISIBLE else View.GONE
        view.text = if (scrim >= 100) {
            "可读性底色为 100%，背景透明度不会生效"
        } else {
            "叠加底色后整体约 ${effectiveOpacity()}%"
        }
    }

    /** Warn when the keyboard, scrim included, is mostly see-through. */
    private fun updateWarning(view: TextView) {
        val effective = effectiveOpacity()
        view.visibility = if (effective < LOW_OPACITY) View.VISIBLE else View.GONE
        view.text = "整体仅约 $effective%，文字可能看不清，建议调高可读性底色"
    }

    // ------------------------------------------------------- floating preview

    private fun initFloatingPreview() {
        floating = root.findViewById(R.id.floatingPreview)
        sizeButton = root.findViewById(R.id.btnPreviewSize)
        collapseButton = root.findViewById(R.id.btnPreviewCollapse)
        previewLarge = uiPrefs.getBoolean(KEY_PREVIEW_LARGE, true)
        previewCollapsed = uiPrefs.getBoolean(KEY_PREVIEW_COLLAPSED, false)

        floating.clipToOutline = true
        preview.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
        }
        preview.clipToOutline = true

        sizeButton.setOnClickListener {
            previewLarge = !previewLarge
            uiPrefs.edit().putBoolean(KEY_PREVIEW_LARGE, previewLarge).apply()
            SettingsKit.tickHaptic(it)
            applyPreviewSize()
        }
        collapseButton.setOnClickListener {
            SettingsKit.tickHaptic(it)
            setPreviewCollapsed(!previewCollapsed)
        }
        floating.onTap = { if (previewCollapsed) setPreviewCollapsed(false) }
        floating.onSettled = { if (overlay.visibility == View.VISIBLE) placePanel() }
        floating.bindPosition(uiPrefs)

        root.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) root.post { applyPreviewSize() }
        }
        // Let the last option scroll clear of the preview.
        floating.addOnLayoutChangeListener { _, _, t, _, b, _, oldT, _, oldB ->
            if (b - t != oldB - oldT) {
                options.setPadding(options.paddingLeft, options.paddingTop, options.paddingRight, b - t + dp(32))
            }
        }
        applyPreviewSize()
    }

    private fun setPreviewCollapsed(collapsed: Boolean) {
        previewCollapsed = collapsed
        uiPrefs.edit().putBoolean(KEY_PREVIEW_COLLAPSED, collapsed).apply()
        applyPreviewSize()
        if (overlay.visibility == View.VISIBLE) floating.post { placePanel() }
    }

    private fun applyPreviewSize() {
        val parentWidth = root.width
        if (parentWidth > 0) {
            val margin = resources.getDimensionPixelSize(R.dimen.floating_margin)
            val available = parentWidth - 2 * margin
            val width = when {
                previewCollapsed -> minOf(dp(200), available)
                previewLarge -> minOf(available, dp(560))
                else -> minOf(maxOf((parentWidth * 0.6f).toInt(), dp(220)), dp(360), available)
            }
            floating.layoutParams = floating.layoutParams.apply { this.width = width }
            preview.layoutParams = preview.layoutParams.apply {
                height = ((width - dp(12)) * PREVIEW_ASPECT).toInt()
            }
        }
        preview.visibility = if (previewCollapsed) View.GONE else View.VISIBLE
        sizeButton.visibility = if (previewCollapsed) View.GONE else View.VISIBLE
        floating.setPadding(0, 0, 0, if (previewCollapsed) 0 else dp(6))
        sizeButton.setImageResource(if (previewLarge) R.drawable.ic_close_fullscreen else R.drawable.ic_open_in_full)
        sizeButton.contentDescription = if (previewLarge) "缩小预览" else "放大预览"
        collapseButton.setImageResource(if (previewCollapsed) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        collapseButton.contentDescription = if (previewCollapsed) "展开预览" else "收起预览"
    }

    /** Puts the edit panel on whichever side of the preview has more room. */
    private fun placePanel() {
        val lp = panelCard.layoutParams as FrameLayout.LayoutParams
        val margin = dp(16)
        lp.gravity = Gravity.CENTER
        lp.setMargins(margin, margin, margin, margin)
        val height = root.height
        if (floating.visibility == View.VISIBLE && !previewCollapsed && height > 0 && floating.height > 0) {
            val gap = dp(8)
            val top = floating.translationY.toInt()
            val bottom = top + floating.height
            val above = top - gap - margin
            val below = height - bottom - gap - margin
            if (maxOf(above, below) >= dp(MIN_PANEL_HEIGHT_DP)) {
                if (above >= below) {
                    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    lp.bottomMargin = height - top + gap
                } else {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    lp.topMargin = bottom + gap
                }
            }
        }
        panelCard.layoutParams = lp
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
        if (previewCollapsed) setPreviewCollapsed(false)
        placePanel()
        closePanelOnBack.isEnabled = true
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(160).start()
        panelCard.scaleX = 0.94f
        panelCard.scaleY = 0.94f
        panelCard.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    fun closePanel() {
        if (overlay.visibility != View.VISIBLE) return
        activity.currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        closePanelOnBack.isEnabled = false
        preview.focus = Focus.NONE
        overlay.animate().alpha(0f).setDuration(140).withEndAction {
            overlay.visibility = View.GONE
            panelContent.removeAllViews()
        }.start()
        if (panelChanged) onConfigChanged()
    }

    private fun refresh() {
        val style = readStyle()
        preview.style = style
        previewHint.text = "编辑" + (if (editingDark) "深色" else "浅色") + "配置" +
            if (!style.enabled) " · 未启用" else ""
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

    // ------------------------------------------------------------- view kit

    private fun section(root: LinearLayout, title: String, options: List<Option>) {
        kit.group(root, title, options.map { optionRow(it) })
    }

    private fun optionRow(option: Option): View {
        val row = horizontalRow()
        row.addView(kit.titleBlock(option.title, option.subtitle, singleLine = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val swatch = View(this)
        row.addView(swatch, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
        val value = label("", 13f, R.color.primary).apply {
            setPadding(dp(6), 0, dp(4), 0)
            maxWidth = dp(130)
            maxLines = 1
        }
        row.addView(value)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_shape_chevron)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val item = Row(option, row, value, swatch)
        row.setOnClickListener { openPanel(item) }
        rows += item
        return row
    }

    private fun card() = kit.card()

    private fun horizontalRow() = kit.row()

    private fun titleBlock(title: String, subtitle: String) = kit.titleBlock(title, subtitle)

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
        val root = LinearLayout(this@AppearancePage).apply { orientation = LinearLayout.VERTICAL }
        var hex = hex(parseColor(initialHex) ?: Color.WHITE)
            private set
        private val swatch = View(this@AppearancePage)
        private val wheel = ColorWheelView(this@AppearancePage)
        private val brightness = SeekBar(this@AppearancePage)
        private val brightnessValue = label("", 12f, R.color.primary)
        private var syncingText = false
        private val input = AppCompatEditText(this@AppearancePage).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setSingleLine()
            hint = "#RRGGBB"
        }

        init {
            val header = LinearLayout(this@AppearancePage).apply {
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
                val bar = SeekBar(this@AppearancePage).apply {
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

        private fun sliderRow(title: String, bar: SeekBar, value: TextView) = LinearLayout(this@AppearancePage).apply {
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
        private const val UI_PREFS = "ui_state"
        /** Below this overall opacity, keys may be hard to read. */
        private const val LOW_OPACITY = 40
        private const val KEY_PREVIEW_LARGE = "floating_preview_large"
        private const val KEY_PREVIEW_COLLAPSED = "floating_preview_collapsed"
        private const val PREVIEW_ASPECT = 0.6f
        private const val MIN_PANEL_HEIGHT_DP = 260

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
    }
}
