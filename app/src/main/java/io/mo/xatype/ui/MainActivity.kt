package io.mo.xatype.ui

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.mo.xatype.BuildConfig
import io.mo.xatype.R
import io.mo.xatype.compat.AdaptationStatus
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.T9ShuangpinScheme
import io.mo.xatype.keyboard.PredictionMode
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private class Tab(val label: String, val icon: Int, val page: View)

    private class NavItem(val root: View, val indicator: View, val icon: ImageView, val label: TextView)

    private lateinit var kit: SettingsKit
    private lateinit var prefs: SharedPreferences
    private lateinit var appearancePage: AppearancePage
    private lateinit var tabs: List<Tab>
    private val navItems = mutableListOf<NavItem>()
    private var currentTab = TAB_HOME

    private lateinit var restartBar: View
    private lateinit var btnRestartBar: Button
    private var restartButtons = mutableListOf<View>()
    private var restartPending = false

    // Home tab views refreshed on resume and on every settings change.
    private lateinit var statusCard: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var purifyCount: TextView
    private lateinit var keyboardCount: TextView
    private lateinit var appearanceState: TextView

    private val purifyKeys = FeatureCatalog.purifyGroups.flatMap { (_, features) -> features.map { it.key } }
    private val keyboardKeys = FeatureCatalog.keyboardFeatures.map { it.key }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshOverview() }

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selectTab(TAB_HOME)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SettingsWindow.apply(window, findViewById(R.id.settingsRoot),
            topBar = findViewById(R.id.toolbar), bottomBar = findViewById(R.id.navBar))
        kit = SettingsKit(this)
        prefs = ConfigManager.getLocalPrefs(this)

        initRestartBar()
        // Registered first so the appearance panel's own back handling wins while it is open.
        onBackPressedDispatcher.addCallback(this, backToHome)
        appearancePage = AppearancePage(this, findViewById(R.id.pageAppearance), savedInstanceState, ::markRestartNeeded)

        buildHome(findViewById(R.id.homeContent))
        buildPurify(findViewById(R.id.purifyContent))
        buildKeyboard(findViewById(R.id.keyboardContent))

        tabs = listOf(
            Tab("概览", R.drawable.ic_nav_home, findViewById(R.id.pageHome)),
            Tab("净化", R.drawable.ic_nav_shield, findViewById(R.id.pagePurify)),
            Tab("键盘", R.drawable.ic_nav_keyboard, findViewById(R.id.pageKeyboard)),
            Tab("外观", R.drawable.ic_nav_palette, findViewById(R.id.pageAppearance))
        )
        buildNavigation(findViewById(R.id.navBar))

        if (savedInstanceState?.getBoolean(STATE_RESTART_PENDING) == true) markRestartNeeded()
        selectTab(savedInstanceState?.getInt(STATE_TAB, TAB_HOME) ?: TAB_HOME, animate = false)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Only on a fresh launch: rotation and process restore go straight to the page.
        if (savedInstanceState == null && prefs.getBoolean(ConfigManager.KEY_SPLASH_ANIMATION, true) &&
            ValueAnimator.areAnimatorsEnabled()
        ) {
            showSplash()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reports arrive whenever the input method restarts.
        refreshStatus()
        refreshOverview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTab)
        outState.putBoolean(STATE_RESTART_PENDING, restartPending)
        appearancePage.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        appearancePage.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------ chrome

    private fun showSplash() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val main = content.getChildAt(0)
        main.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val pageHost = findViewById<View>(R.id.pageHost)
        val splash = SplashView(this)
        splash.onExitStart = {
            main.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            // The page rises into place as the splash fades away.
            val rise = kit.dimen(R.dimen.space_lg).toFloat()
            SpringAnimator(SpringAnimator.STANDARD_STIFFNESS, SpringAnimator.STANDARD_DAMPING) {
                pageHost.translationY = it
            }.start(rise, 0f)
        }
        splash.onFinished = { content.removeView(splash) }
        content.addView(splash, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun initRestartBar() {
        restartBar = findViewById(R.id.restartBar)
        btnRestartBar = findViewById(R.id.btnRestartBar)
        btnRestartBar.setOnClickListener { restartInputMethod() }
        restartButtons += btnRestartBar
    }

    private fun markRestartNeeded() {
        if (restartPending) return
        restartPending = true
        restartBar.visibility = View.VISIBLE
        restartBar.alpha = 0f
        restartBar.animate().alpha(1f).setDuration(160).start()
    }

    private fun restartInputMethod() {
        restartButtons.forEach { it.isEnabled = false }
        ImeRestarter.restart(this) { success ->
            if (isDestroyed) return@restart
            restartButtons.forEach { it.isEnabled = true }
            if (success) {
                restartPending = false
                restartBar.visibility = View.GONE
            }
        }
    }

    private fun buildNavigation(bar: LinearLayout) {
        tabs.forEachIndexed { index, tab ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = tab.label
                background = kit.selectableBackground()
                setOnClickListener {
                    if (currentTab != index) SettingsKit.tickHaptic(it)
                    selectTab(index)
                }
            }
            val iconHolder = FrameLayout(this)
            val indicator = View(this).apply { setBackgroundResource(R.drawable.bg_nav_indicator) }
            iconHolder.addView(indicator, FrameLayout.LayoutParams(kit.dp(56), kit.dp(30), Gravity.CENTER))
            val icon = ImageView(this).apply {
                setImageResource(tab.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            iconHolder.addView(icon, FrameLayout.LayoutParams(kit.dp(22), kit.dp(22), Gravity.CENTER))
            item.addView(iconHolder, LinearLayout.LayoutParams(kit.dp(56), kit.dp(30)))
            val label = kit.label(tab.label, 12f, R.color.text_secondary).apply { setPadding(0, kit.dp(4), 0, 0) }
            item.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            navItems += NavItem(item, indicator, icon, label)
        }
    }

    private val indicatorSprings = mutableMapOf<View, SpringAnimator>()

    private fun selectTab(index: Int, animate: Boolean = true) {
        val previous = currentTab
        currentTab = index.coerceIn(0, tabs.lastIndex)
        if (currentTab != TAB_APPEARANCE) appearancePage.closePanel()
        tabs.forEachIndexed { i, tab ->
            val show = i == currentTab
            tab.page.visibility = if (show) View.VISIBLE else View.GONE
            if (show && animate && previous != currentTab) {
                tab.page.alpha = 0f
                tab.page.animate().alpha(1f).setDuration(150).start()
            }
        }
        navItems.forEachIndexed { i, item ->
            val selected = i == currentTab
            val tint = getColor(if (selected) R.color.primary else R.color.text_secondary)
            item.icon.imageTintList = ColorStateList.valueOf(tint)
            item.label.setTextColor(tint)
            item.label.typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            item.root.isSelected = selected
            item.indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (selected && animate && previous != currentTab) {
                val spring = indicatorSprings.getOrPut(item.indicator) {
                    SpringAnimator(SpringAnimator.MICRO_STIFFNESS, SpringAnimator.MICRO_DAMPING, 0.002f) {
                        item.indicator.scaleX = it
                    }
                }
                spring.start(0.4f, 1f)
            } else {
                item.indicator.scaleX = 1f
            }
        }
        backToHome.isEnabled = currentTab != TAB_HOME
        if (currentTab == TAB_HOME) refreshOverview()
    }

    // ------------------------------------------------------------- home

    private fun buildHome(root: LinearLayout) {
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = kit.dimen(R.dimen.space_lg)
            setPadding(pad, pad + kit.dp(4), pad, pad)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        titleRow.addView(statusDot, LinearLayout.LayoutParams(kit.dp(12), kit.dp(12)))
        statusTitle = kit.label("", 17f, R.color.text_primary, bold = true).apply { setPadding(kit.dp(10), 0, 0, 0) }
        titleRow.addView(statusTitle)
        statusCard.addView(titleRow)
        statusDesc = kit.label("", 13f, R.color.text_secondary).apply {
            setPadding(0, kit.dp(6), 0, 0)
            setLineSpacing(kit.dp(2).toFloat(), 1f)
        }
        statusCard.addView(statusDesc)
        val restart = Button(this).apply {
            text = "重启超级小爱输入法"
            textSize = 14f
            isAllCaps = false
            setTextColor(getColor(R.color.on_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_button_primary)
            stateListAnimator = null
            setOnClickListener { restartInputMethod() }
        }
        restartButtons += restart
        statusCard.addView(restart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, kit.dp(48)).apply {
            topMargin = kit.dimen(R.dimen.space_lg)
        })
        root.addView(statusCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = kit.dimen(R.dimen.space_sm)
        })

        root.addView(kit.sectionHeader("已开启"))
        val tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        purifyCount = overviewTile(tiles, R.drawable.ic_nav_shield, "净化功能", TAB_PURIFY)
        keyboardCount = overviewTile(tiles, R.drawable.ic_nav_keyboard, "键盘增强", TAB_KEYBOARD)
        appearanceState = overviewTile(tiles, R.drawable.ic_nav_palette, "外观个性化", TAB_APPEARANCE)
        root.addView(tiles)

        val launcherAlias = ComponentName(this, "$packageName.ui.LauncherAlias")
        kit.group(root, "模块设置", listOf(
            prefSwitch(ConfigManager.KEY_VERBOSE_LOG, "输出详细调试日志", "",
                needsRestart = false).view,
            kit.switchRow("启动动画", null, prefs.getBoolean(ConfigManager.KEY_SPLASH_ANIMATION, true)) { checked ->
                prefs.edit().putBoolean(ConfigManager.KEY_SPLASH_ANIMATION, checked).apply()
            }.view,
            // Component state, not a preference: the launcher reads it directly.
            kit.switchRow("隐藏桌面图标", "可从 LSPosed 管理器进入设置",
                packageManager.getComponentEnabledSetting(launcherAlias) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) { hide ->
                packageManager.setComponentEnabledSetting(
                    launcherAlias,
                    if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Toast.makeText(
                    this,
                    if (hide) "已隐藏桌面图标，可在 LSPosed 管理器的模块列表中进入" else "已恢复桌面图标",
                    Toast.LENGTH_SHORT
                ).show()
            }.view
        ))

        kit.group(root, "更多", listOf(
            kit.navRow("输入法与外观诊断", "采集 / 导出日志", R.drawable.ic_bug) {
                startActivity(Intent(this, io.mo.xatype.diagnostics.DiagnosticsActivity::class.java))
            }
        ))
    }

    /** A small card with a count; tapping it opens [tab]. Returns the value view. */
    private fun overviewTile(parent: LinearLayout, iconRes: Int, title: String, tab: Int): TextView {
        val tile = kit.card().apply {
            val pad = kit.dimen(R.dimen.space_md)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            foreground = kit.selectableBackground()
            setOnClickListener { selectTab(tab) }
        }
        val size = kit.dp(32)
        tile.addView(kit.iconTile(iconRes).apply {
            val pad = kit.dp(6)
            setPadding(pad, pad, pad, pad)
        }, LinearLayout.LayoutParams(size, size))
        val value = kit.label("", 20f, R.color.text_primary, bold = true).apply {
            setPadding(0, kit.dimen(R.dimen.space_sm), 0, 0)
            maxLines = 1
        }
        tile.addView(value)
        tile.addView(kit.label(title, 12f, R.color.text_secondary).apply { maxLines = 1 })
        val index = parent.childCount
        parent.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) marginStart = kit.dimen(R.dimen.space_sm)
        })
        tile.contentDescription = title
        return value
    }

    private fun refreshOverview() {
        if (!::purifyCount.isInitialized) return
        purifyCount.text = "${purifyKeys.count { prefs.getBoolean(it, false) }}/${purifyKeys.size}"
        val predictionEnabled = PredictionMode.resolve(prefs.getString(ConfigManager.KEY_PREDICTION_MODE, null)) != PredictionMode.KEEP
        keyboardCount.text = "${keyboardKeys.count { prefs.getBoolean(it, false) } + if (predictionEnabled) 1 else 0}/${keyboardKeys.size + 1}"
        appearanceState.text = if (prefs.getBoolean(ConfigManager.KEY_STYLE_ENABLED, false)) "已启用" else "未启用"
    }

    private fun refreshStatus() {
        val targetPkg = "com.xiaomi.type"
        var ready: Boolean
        try {
            val pkgInfo = packageManager.getPackageInfo(targetPkg, 0)
            val versionName = pkgInfo.versionName ?: "Unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            val adaptation = adaptationSummary(versionCode)
            ready = adaptation.second
            statusTitle.text = if (ready) "模块已生效" else "小爱输入法已安装"
            statusDesc.text = "版本 $versionName ($versionCode)\n${adaptation.first}"
        } catch (_: PackageManager.NameNotFoundException) {
            ready = false
            statusTitle.text = "未检测到超级小爱输入法"
            statusDesc.text = "请确认已安装 com.xiaomi.type 并启用模块"
        }
        statusDot.setBackgroundResource(if (ready) R.drawable.dot_active else R.drawable.dot_inactive)
        statusCard.background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.radius_card)
            setColor(getColor(if (ready) R.color.status_active_container else R.color.status_inactive_container))
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

        val phrase = report(AdaptationStatus.PHRASE_PACKAGE)
        val phraseVersion = runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(AdaptationStatus.PHRASE_PACKAGE, 0)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }.getOrNull()
        if (phraseVersion != null) {
            if (phrase == null) return "输入法已运行，剪贴板适配状态尚未上报" to false
            if (phrase.optLong(AdaptationStatus.EXTRA_VERSION_CODE) != phraseVersion ||
                phrase.optString(AdaptationStatus.EXTRA_MODULE_STAMP) != stamp) {
                return "剪贴板或模块已更新，重启输入法后重新适配" to false
            }
        }
        val reports = listOfNotNull(ime, phrase.takeIf { phraseVersion != null })
        if (reports.any { it.optString(AdaptationStatus.EXTRA_SOURCE) == "UNAVAILABLE" }) {
            return "自适配未能运行，已回退到内置适配" to false
        }
        val missing = reports.flatMap { json ->
            val keys = json.optJSONArray(AdaptationStatus.EXTRA_MISSING) ?: JSONArray()
            List(keys.length()) { keys.getString(it) }
        }
        return if (missing.isEmpty()) {
            "已完成自动适配，功能按各自开关启用" to true
        } else {
            "尚未适配或安装失败：" + AdaptationStatus.featureNames(missing).joinToString("、") to false
        }
    }

    // ----------------------------------------------------------- purify

    private fun buildPurify(root: LinearLayout) {
        FeatureCatalog.purifyGroups.forEachIndexed { index, (title, features) ->
            kit.group(root, title, features.map { featureSwitch(it).view }, first = index == 0)
        }
    }

    // --------------------------------------------------------- keyboard

    private fun buildKeyboard(root: LinearLayout) {
        // Key shortcuts are independent of experimental keyboards and appearance.
        kit.group(root, "输入增强", listOf(
            featureSwitch(FeatureCatalog.keyboardShortcuts).view,
            featureSwitch(FeatureCatalog.emojiCandidates).view,
            predictionModeRow()
        ), first = true)

        kit.group(root, "尺寸与布局", listOf(
            featureSwitch(FeatureCatalog.keyboardHeight).view,
            bottomSpacingRow()
        ))

        kit.group(root, "输入方案", listOf(
            featureSwitch(FeatureCatalog.t9Shuangpin).view,
            t9ShuangpinSchemeRow(),
            kit.navRow("形码输入", null, R.drawable.ic_text_fields) {
                startActivity(Intent(this, ShapeInputActivity::class.java))
            },
            kit.navRow("18 键键盘", null, R.drawable.ic_nav_keyboard) {
                startActivity(Intent(this, Keyboard18Activity::class.java))
            }
        ))
    }

    private fun predictionModeRow(): View {
        val modes = PredictionMode.entries
        fun selected() = PredictionMode.resolve(prefs.getString(ConfigManager.KEY_PREDICTION_MODE, null))
        lateinit var row: View
        row = kit.navRow("上屏后联想", "${selected().title} · ${selected().summary}", null,
            detail = SettingsKit.Detail("控制文字上屏后是否显示输入法原有的联想词。",
                notes = listOf("选择后重启输入法生效。适用于原生拼音与双拼，不影响输入中的候选词和 Emoji 候选增强。"))) {
            InfoSheet.choose(this, "上屏后联想", modes.map { it.title }, modes.indexOf(selected()),
                modes.map { it.summary }) { index ->
                prefs.edit().putString(ConfigManager.KEY_PREDICTION_MODE, modes[index].name).apply()
                val parent = row.parent as? ViewGroup
                if (parent != null) {
                    val position = parent.indexOfChild(row)
                    val params = row.layoutParams
                    parent.removeView(row)
                    parent.addView(predictionModeRow(), position, params)
                }
                markRestartNeeded()
            }
        }
        return row
    }

    private fun t9ShuangpinSchemeRow(): View {
        val schemes = T9ShuangpinScheme.entries
        fun selected() = T9ShuangpinScheme.resolve(
            prefs.getString(ConfigManager.KEY_T9_SHUANGPIN_SCHEME, null))
        lateinit var row: View
        row = kit.navRow("九键双拼方案", selected().title, R.drawable.ic_text_fields) {
            InfoSheet.choose(this, "九键双拼方案", schemes.map { it.title }, schemes.indexOf(selected())) { index ->
                val scheme = schemes[index]
                prefs.edit().putString(ConfigManager.KEY_T9_SHUANGPIN_SCHEME, scheme.name).apply()
                // Rebuild the row's subtitle without rebuilding the page or losing scroll position.
                val parent = row.parent as? ViewGroup
                if (parent != null) {
                    val position = parent.indexOfChild(row)
                    val params = row.layoutParams
                    parent.removeView(row)
                    parent.addView(t9ShuangpinSchemeRow(), position, params)
                }
                markRestartNeeded()
            }
        }
        return row
    }

    /** Keyboard bottom spacing is independent of appearance profiles. */
    private fun bottomSpacingRow(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val value = kit.label("", 13f, R.color.primary, bold = true)
        val seek = kit.seekBar(100, prefs.getInt(ConfigManager.KEY_BOTTOM_SPACING, 0).coerceIn(0, 100),
            "键盘底部间距，0 到 100 dp")
        val toggle = featureSwitch(FeatureCatalog.bottomSpacing) { checked ->
            seek.isEnabled = checked
            value.text = if (checked) "${seek.progress} dp" else "原生间距"
        }
        container.addView(toggle.view)

        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = kit.dimen(R.dimen.settings_row_padding)
            setPadding(pad - kit.dp(8), 0, pad, kit.dimen(R.dimen.space_sm))
        }
        sliderRow.addView(seek, LinearLayout.LayoutParams(0, kit.dp(48), 1f))
        sliderRow.addView(value, LinearLayout.LayoutParams(kit.dp(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = kit.dimen(R.dimen.space_sm)
        })
        value.gravity = Gravity.END
        container.addView(sliderRow)

        val enabled = toggle.switch.isChecked
        seek.isEnabled = enabled
        value.text = if (enabled) "${seek.progress} dp" else "原生间距"
        var lastTick = seek.progress / 10
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (toggle.switch.isChecked) value.text = "$progress dp"
                if (!fromUser) return
                prefs.edit().putInt(ConfigManager.KEY_BOTTOM_SPACING, progress).apply()
                if (progress / 10 != lastTick) {
                    lastTick = progress / 10
                    SettingsKit.tickHaptic(seekBar)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) = markRestartNeeded()
        })
        return container
    }

    private fun featureSwitch(feature: Feature, onChange: ((Boolean) -> Unit)? = null) =
        prefSwitch(feature.key, feature.title, feature.summary, feature.detail, onChange = onChange)

    /** A switch bound to a boolean preference that defaults to off. */
    private fun prefSwitch(
        key: String,
        title: String,
        summary: String,
        detail: SettingsKit.Detail? = null,
        needsRestart: Boolean = true,
        onChange: ((Boolean) -> Unit)? = null
    ): SettingsKit.SwitchRow = kit.switchRow(title, summary, prefs.getBoolean(key, false), detail = detail) { checked ->
        prefs.edit().putBoolean(key, checked).apply()
        onChange?.invoke(checked)
        if (needsRestart) markRestartNeeded()
    }

    private companion object {
        const val STATE_TAB = "main_tab"
        const val STATE_RESTART_PENDING = "restart_pending"
        const val TAB_HOME = 0
        const val TAB_PURIFY = 1
        const val TAB_KEYBOARD = 2
        const val TAB_APPEARANCE = 3
    }
}
