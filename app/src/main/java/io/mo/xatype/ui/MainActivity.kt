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
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
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

    private lateinit var statusCard: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var updater: AppUpdater
    private lateinit var updateValue: TextView

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selectTab(TAB_HOME)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // The test keyboard overlays this page; keep its bottom navigation in place.
        SettingsWindow.apply(window, findViewById(R.id.settingsRoot),
            topBar = findViewById(R.id.toolbar), bottomBar = findViewById(R.id.navBar),
            insetIme = false)
        kit = SettingsKit(this)
        prefs = ConfigManager.getLocalPrefs(this)
        val testInput = findViewById<AppCompatEditText>(R.id.keyboardTestInput)
        val clearTest = findViewById<Button>(R.id.clearKeyboardTest)
        clearTest.isEnabled = !testInput.text.isNullOrEmpty()
        testInput.doAfterTextChanged { clearTest.isEnabled = !it.isNullOrEmpty() }
        clearTest.setOnClickListener { testInput.text?.clear() }
        updater = AppUpdater(this, ::refreshUpdate)

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
        refreshUpdate()
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
        updater.maybeAutoCheck()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTab)
        outState.putBoolean(STATE_RESTART_PENDING, restartPending)
        appearancePage.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        appearancePage.destroy()
        updater.destroy()
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
            val label = kit.label(tab.label, 12f, R.color.text_secondary).apply {
                setPadding(0, kit.dimen(R.dimen.space_xs), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
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
        statusTitle = kit.label("", 17f, R.color.text_primary, bold = true).apply {
            setPadding(kit.dimen(R.dimen.space_sm), 0, 0, 0)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        titleRow.addView(statusTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(titleRow)
        statusDesc = kit.label("", 13f, R.color.text_secondary).apply {
            setPadding(0, kit.dp(6), 0, 0)
            setLineSpacing(kit.dp(2).toFloat(), 1f)
        }
        statusCard.addView(statusDesc)
        val restart = Button(this).apply {
            text = "重启输入法"
            textSize = 14f
            isAllCaps = false
            setTextColor(getColor(R.color.on_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_button_primary)
            stateListAnimator = null
            minHeight = kit.dimen(R.dimen.settings_touch_height)
            setOnClickListener { restartInputMethod() }
        }
        restartButtons += restart
        statusCard.addView(restart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = kit.dimen(R.dimen.space_lg)
        })
        root.addView(statusCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = kit.dimen(R.dimen.space_sm)
        })

        val launcherAlias = ComponentName(this, "$packageName.ui.LauncherAlias")
        kit.group(root, "模块设置", listOf(
            prefSwitch(ConfigManager.KEY_VERBOSE_LOG, "输出详细调试日志", needsRestart = false).view,
            kit.switchRow("启动动画", prefs.getBoolean(ConfigManager.KEY_SPLASH_ANIMATION, true)) { checked ->
                prefs.edit().putBoolean(ConfigManager.KEY_SPLASH_ANIMATION, checked).apply()
            }.view,
            // Component state, not a preference: the launcher reads it directly.
            kit.switchRow("隐藏桌面图标",
                packageManager.getComponentEnabledSetting(launcherAlias) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                detail = SettingsKit.Detail("隐藏后，可从 LSPosed 管理器的模块列表打开设置。")
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
            }.view,
            kit.navRow("输入法与外观诊断", R.drawable.ic_bug) {
                startActivity(Intent(this, io.mo.xatype.diagnostics.DiagnosticsActivity::class.java))
            }
        ))

        updateValue = kit.rowValue("")
        kit.group(root, "版本更新", listOf(
            kit.navRow("检查更新", value = updateValue) {
                val available = updater.available()
                if (available != null) updater.show(available) else updater.check(manual = true)
            },
            kit.switchRow("打开设置时自动检查更新", updater.autoCheck) { checked ->
                updater.autoCheck = checked
                if (checked) updater.maybeAutoCheck()
            }.view
        ))
    }

    private fun refreshUpdate() {
        if (!::updateValue.isInitialized) return
        updateValue.text = updater.available()?.let { "可更新至 ${it.tag}" } ?: BuildConfig.VERSION_NAME
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
            statusDesc.text = listOfNotNull("输入法 $versionName ($versionCode)", adaptation.first).joinToString("\n")
        } catch (_: PackageManager.NameNotFoundException) {
            ready = false
            statusTitle.text = "未检测到超级小爱输入法"
            statusDesc.text = "请安装超级小爱输入法（com.xiaomi.type）"
        }
        statusDot.setBackgroundResource(if (ready) R.drawable.dot_active else R.drawable.dot_inactive)
        statusCard.background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.radius_card)
            setColor(getColor(if (ready) R.color.status_active_container else R.color.status_inactive_container))
        }
    }

    /** Returns any outstanding adaptation issue and whether the module is ready. */
    private fun adaptationSummary(imeVersionCode: Long): Pair<String?, Boolean> {
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
            null to true
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
            kit.navRow("自定义快捷键", R.drawable.ic_nav_keyboard) {
                startActivity(Intent(this, ShortcutKeysActivity::class.java))
            },
            shortcutLabelRow(),
            kit.navRow("个人字典", R.drawable.ic_text_fields) {
                startActivity(Intent(this, PersonalDictionaryActivity::class.java))
            },
            handwritingRow(),
            featureSwitch(FeatureCatalog.emojiCandidates).view,
            predictionModeRow()
        ), first = true)

        kit.group(root, "按键反馈", listOf(featureSwitch(FeatureCatalog.keyboardHaptics).view))

        kit.group(root, "尺寸与布局", listOf(
            oneHandRow(),
            featureSwitch(FeatureCatalog.keyboardHeight).view,
            toolbarHeightRow(),
            bottomSpacingRow()
        ))

        kit.group(root, "输入方案", listOf(
            featureSwitch(FeatureCatalog.t9Shuangpin).view,
            t9ShuangpinSchemeRow(),
            kit.navRow("形码输入", R.drawable.ic_text_fields) {
                startActivity(Intent(this, ShapeInputActivity::class.java))
            },
            kit.navRow("18 键键盘", R.drawable.ic_nav_keyboard) {
                startActivity(Intent(this, Keyboard18Activity::class.java))
            },
            kit.navRow("14 键键盘", R.drawable.ic_nav_keyboard) {
                startActivity(Intent(this, Keyboard18Activity::class.java)
                    .putExtra(Keyboard18Activity.EXTRA_LAYOUT, Keyboard18Activity.LAYOUT_14))
            },
            kit.navRow("在线词库", R.drawable.ic_shape_import) {
                startActivity(Intent(this, OnlineDictActivity::class.java))
            }
        ))
    }

    private fun predictionModeRow(): View {
        val modes = PredictionMode.entries
        fun selected() = PredictionMode.resolve(prefs.getString(ConfigManager.KEY_PREDICTION_MODE, null))
        val value = kit.rowValue(selected().title)
        return kit.navRow("上屏后联想", value = value) {
            InfoSheet.choose(this, "上屏后联想", modes.map { it.title }, modes.indexOf(selected())) { index ->
                prefs.edit().putString(ConfigManager.KEY_PREDICTION_MODE, modes[index].name).apply()
                value.text = modes[index].title
                markRestartNeeded()
            }
        }
    }

    private fun oneHandRow(): View {
        val labels = listOf("关闭", "左手", "右手")
        fun selected() = prefs.getInt(ConfigManager.KEY_ONE_HAND_SIDE, 0).coerceIn(labels.indices)
        val policy = io.mo.xatype.hooks.OneHandPolicy
        val width = settingSlider("单手键盘宽度", policy.WIDTH_RANGE,
            prefs.getInt(ConfigManager.KEY_ONE_HAND_WIDTH, policy.DEFAULT_WIDTH),
            "单手键盘宽度百分比", { "$it%" }) {
            prefs.edit().putInt(ConfigManager.KEY_ONE_HAND_WIDTH, it).apply()
        }
        width.visibility = if (selected() == 0) View.GONE else View.VISIBLE
        val value = kit.rowValue(labels[selected()])
        val row = kit.navRow("单手模式", value = value, detail = SettingsKit.Detail(
            "也可从键盘菜单即时切换。适用于竖屏的非悬浮键盘，最小宽度为 240 dp。"
        )) {
            InfoSheet.choose(this, "单手模式", labels, selected()) { index ->
                ConfigManager.editOneHandSide(prefs, index).apply()
                value.text = labels[index]
                width.visibility = if (index == 0) View.GONE else View.VISIBLE
                markRestartNeeded()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(width)
        }
    }

    private fun t9ShuangpinSchemeRow(): View {
        val schemes = T9ShuangpinScheme.entries
        fun selected() = T9ShuangpinScheme.resolve(
            prefs.getString(ConfigManager.KEY_T9_SHUANGPIN_SCHEME, null))
        val value = kit.rowValue(selected().title)
        return kit.navRow("九键双拼方案", value = value) {
            InfoSheet.choose(this, "九键双拼方案", schemes.map { it.title }, schemes.indexOf(selected())) { index ->
                val scheme = schemes[index]
                prefs.edit().putString(ConfigManager.KEY_T9_SHUANGPIN_SCHEME, scheme.name).apply()
                value.text = scheme.title
                markRestartNeeded()
            }
        }
    }

    private fun toolbarHeightRow(): View {
        val range = ConfigManager.TOOLBAR_HEIGHT_RANGE
        return featureControls(FeatureCatalog.toolbarHeight,
            settingSlider("高度", range,
                prefs.getInt(ConfigManager.KEY_TOOLBAR_HEIGHT, ConfigManager.TOOLBAR_HEIGHT_DEFAULT),
                "顶部功能栏高度（dp）", { "$it dp" }, tickStep = 4) {
                prefs.edit().putInt(ConfigManager.KEY_TOOLBAR_HEIGHT, it).apply()
            }
        )
    }

    private fun bottomSpacingRow(): View =
        featureControls(FeatureCatalog.bottomSpacing,
            settingSlider("间距", 0..100, prefs.getInt(ConfigManager.KEY_BOTTOM_SPACING, 0),
                "键盘底部间距（dp）", { "$it dp" }, tickStep = 10) {
                prefs.edit().putInt(ConfigManager.KEY_BOTTOM_SPACING, it).apply()
            }
        )

    private fun featureSwitch(feature: Feature, onChange: ((Boolean) -> Unit)? = null) =
        prefSwitch(feature.key, feature.title, feature.detail, onChange = onChange)

    private fun featureControls(feature: Feature, vararg controls: View): View {
        val children = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            controls.forEach { addView(it) }
        }
        val toggle = featureSwitch(feature) { checked ->
            children.visibility = if (checked) View.VISIBLE else View.GONE
        }
        children.visibility = if (toggle.switch.isChecked) View.VISIBLE else View.GONE
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle.view)
            addView(children)
        }
    }

    private fun shortcutLabelRow(): View = featureControls(FeatureCatalog.shortcutLabels,
        settingSlider("文字大小", 8..20,
            prefs.getInt(ConfigManager.KEY_SHORTCUT_LABEL_SIZE, 11),
            "快捷操作文字大小（sp）", { "$it sp" }) { size ->
            prefs.edit().putInt(ConfigManager.KEY_SHORTCUT_LABEL_SIZE, size).apply()
        },
        settingSlider("水平位置", -40..40,
            prefs.getInt(ConfigManager.KEY_SHORTCUT_LABEL_X, 0),
            "快捷操作文字水平位置，负值向左", ::labelOffset) { x ->
            prefs.edit().putInt(ConfigManager.KEY_SHORTCUT_LABEL_X, x).apply()
        },
        settingSlider("垂直位置", -40..40,
            prefs.getInt(ConfigManager.KEY_SHORTCUT_LABEL_Y, -2),
            "快捷操作文字垂直位置，负值向上", ::labelOffset) { y ->
            prefs.edit().putInt(ConfigManager.KEY_SHORTCUT_LABEL_Y, y).apply()
        }
    )

    private fun handwritingRow(): View = featureControls(FeatureCatalog.keyboardHandwriting,
        settingSlider("上滑起笔距离", ConfigManager.HANDWRITING_UP_DISTANCE_RANGE,
            prefs.getInt(ConfigManager.KEY_HANDWRITING_UP_DISTANCE, ConfigManager.HANDWRITING_UP_DISTANCE_DEFAULT),
            "上滑起笔距离（dp）", { "$it dp" }) { distance ->
            prefs.edit().putInt(ConfigManager.KEY_HANDWRITING_UP_DISTANCE, distance).apply()
        },
        settingSlider("左右起笔距离", ConfigManager.HANDWRITING_SIDE_DISTANCE_RANGE,
            prefs.getInt(ConfigManager.KEY_HANDWRITING_SIDE_DISTANCE, ConfigManager.HANDWRITING_SIDE_DISTANCE_DEFAULT),
            "左右起笔距离（dp）", { "$it dp" }) { distance ->
            prefs.edit().putInt(ConfigManager.KEY_HANDWRITING_SIDE_DISTANCE, distance).apply()
        }
    )

    private fun labelOffset(value: Int) = when {
        value == 0 -> "居中"
        value > 0 -> "+$value dp"
        else -> "$value dp"
    }

    private fun settingSlider(
        title: String,
        range: IntRange,
        initial: Int,
        description: String,
        format: (Int) -> String,
        tickStep: Int = 8,
        save: (Int) -> Unit
    ): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = kit.dimen(R.dimen.settings_row_padding)
            setPadding(pad, kit.dimen(R.dimen.space_sm), pad, 0)
        }
        head.addView(kit.label(title, 14f, R.color.text_primary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val currentValue = format(initial.coerceIn(range))
        val value = kit.rowValue(currentValue)
        head.addView(value)
        column.addView(head)
        val seek = kit.seekBar(range.last - range.first,
            (initial - range.first).coerceIn(0, range.last - range.first), description)
        ViewCompat.setStateDescription(seek, currentValue)
        head.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = kit.dimen(R.dimen.settings_row_padding)
            setPadding(pad - kit.dp(8), 0, pad, 0)
        }
        body.addView(seek, LinearLayout.LayoutParams(0, kit.dimen(R.dimen.settings_touch_height), 1f))
        column.addView(body)
        var lastTick = seek.progress / tickStep
        var trackingTouch = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val current = range.first + progress
                val formatted = format(current)
                value.text = formatted
                ViewCompat.setStateDescription(seekBar, formatted)
                if (!fromUser) return
                save(current)
                if (!trackingTouch) markRestartNeeded()
                if (progress / tickStep != lastTick) {
                    lastTick = progress / tickStep
                    SettingsKit.tickHaptic(seekBar)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingTouch = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingTouch = false
                markRestartNeeded()
            }
        })
        return column
    }

    /** A switch bound to a boolean preference that defaults to off. */
    private fun prefSwitch(
        key: String,
        title: String,
        detail: SettingsKit.Detail? = null,
        needsRestart: Boolean = true,
        onChange: ((Boolean) -> Unit)? = null
    ): SettingsKit.SwitchRow = kit.switchRow(title, prefs.getBoolean(key, false), detail = detail) { checked ->
        prefs.edit().putBoolean(key, checked).apply()
        if (key == ConfigManager.KEY_SCREENSHOT_CLIPBOARD) {
            contentResolver.notifyChange(ConfigManager.SCREENSHOT_CONFIG_URI, null)
        }
        onChange?.invoke(checked)
        if (needsRestart && key != ConfigManager.KEY_SCREENSHOT_CLIPBOARD) markRestartNeeded()
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
