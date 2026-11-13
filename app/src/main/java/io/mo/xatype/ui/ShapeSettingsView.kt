package io.mo.xatype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import io.mo.xatype.R
import io.mo.xatype.shape.ShapeStore
import io.mo.xatype.ui.ShapeUiStyle.Companion.BODY
import io.mo.xatype.ui.ShapeUiStyle.Companion.BUTTON
import io.mo.xatype.ui.ShapeUiStyle.Companion.DISABLED_ALPHA
import io.mo.xatype.ui.ShapeUiStyle.Companion.HEADING
import io.mo.xatype.ui.ShapeUiStyle.Companion.ICON
import io.mo.xatype.ui.ShapeUiStyle.Companion.ICON_TILE
import io.mo.xatype.ui.ShapeUiStyle.Companion.LG
import io.mo.xatype.ui.ShapeUiStyle.Companion.MAX_WIDTH
import io.mo.xatype.ui.ShapeUiStyle.Companion.MD
import io.mo.xatype.ui.ShapeUiStyle.Companion.METRIC
import io.mo.xatype.ui.ShapeUiStyle.Companion.SM
import io.mo.xatype.ui.ShapeUiStyle.Companion.SMALL_RADIUS
import io.mo.xatype.ui.ShapeUiStyle.Companion.SWITCH_WIDTH
import io.mo.xatype.ui.ShapeUiStyle.Companion.TITLE
import io.mo.xatype.ui.ShapeUiStyle.Companion.TOUCH
import io.mo.xatype.ui.ShapeUiStyle.Companion.XL
import io.mo.xatype.ui.ShapeUiStyle.Companion.XS
import java.text.NumberFormat

/** Stable view hierarchy: updating a setting does not rebuild the page or move the scroll position. */
internal class ShapeSettingsView(context: Context, private val actions: Actions) : LinearLayout(context) {
    data class Actions(
        val back: () -> Unit,
        val help: () -> Unit,
        val importTable: () -> Unit,
        val restore: () -> Unit,
        val maxCode: () -> Unit,
        val restart: () -> Unit,
        val retry: () -> Unit,
        val toggle: (String, Boolean) -> Unit
    )

    val style = ShapeUiStyle(context)
    val toolbar: Toolbar
    val footer: FrameLayout
    private val toggles = LinkedHashMap<String, SwitchCompat>()
    private val interactive = ArrayList<View>()
    private val dictionaryControls = ArrayList<View>()
    private var binding = false
    private var hasSnapshot = false
    private var busy = false
    private var hasDictionary = false
    private lateinit var name: TextView
    private lateinit var count: TextView
    private lateinit var longest: TextView
    private lateinit var metrics: LinearLayout
    private lateinit var skeleton: LinearLayout
    private lateinit var hostStatus: TextView
    private lateinit var maxCode: TextView
    private lateinit var restart: AppCompatButton
    private lateinit var errorCard: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var errorTitle: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(style.background)
        toolbar = Toolbar(context).apply {
            title = "形码输入"
            setTitleTextColor(style.primary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(style.primary)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { actions.back() }
            contentInsetStartWithNavigation = 0
            minimumHeight = style.dp(BUTTON)
            menu.add("帮助").apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                actionView = text("帮助", BODY, style.accentText, true).apply {
                    gravity = Gravity.CENTER
                    minWidth = style.dp(TOUCH)
                    minHeight = style.dp(TOUCH)
                    setPadding(style.dp(SM), 0, style.dp(SM), 0)
                    background = style.ripple(style.background, SMALL_RADIUS)
                    setOnClickListener { actions.help() }
                }
            }
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val body = column().apply { setPadding(style.dp(LG), style.dp(SM), style.dp(LG), style.dp(XL)) }
        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(centered(body))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        errorCard = column().apply {
            background = style.surface(style.errorSurface)
            pad(LG)
            visibility = GONE
            errorTitle = text("操作未完成", HEADING, style.errorText, true)
            addView(errorTitle)
            errorMessage = text("", BODY, style.errorText)
            addView(errorMessage, spaced(top = SM))
            addView(text("重新读取码表", BODY, style.errorText, true).apply {
                minHeight = style.dp(TOUCH)
                gravity = Gravity.CENTER_VERTICAL
                background = style.ripple(style.errorSurface, SMALL_RADIUS)
                setOnClickListener { actions.retry() }
            })
        }
        body.addView(errorCard, spaced(bottom = LG))

        val hero = column().apply { background = style.surface(); pad(XL) }
        body.addView(hero)
        name = text("准备码表", TITLE, style.primary, true)
        hero.addView(name, spaced(top = SM))
        skeleton = column().apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(View(context).apply { background = style.surface(style.outline, SMALL_RADIUS) },
                LayoutParams(style.dp(ICON_TILE * 3), style.dp(LG)))
            addView(View(context).apply { background = style.surface(style.outline, SMALL_RADIUS) },
                LayoutParams(style.dp(ICON_TILE * 2), style.dp(MD)).apply { topMargin = style.dp(SM) })
        }
        hero.addView(skeleton, spaced(top = LG, bottom = SM))
        metrics = row().apply {
            gravity = Gravity.TOP
            val first = metric("码表条目").also { count = it.second }
            val second = metric("最长编码").also { longest = it.second }
            addView(first.first, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = style.dp(LG) })
            addView(second.first, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            visibility = GONE
        }
        hero.addView(metrics, spaced(top = LG, bottom = SM))
        hero.addView(toggleRow("在键盘选择中显示虎码/五笔", "显示入口后，在小爱键盘面板中选择使用", ShapeStore.SHOW_IN_PICKER, inset = false), spaced(top = SM))

        val tables = group(body, "码表管理")
        tables.addView(actionRow("导入码表", R.drawable.ic_shape_import, action = actions.importTable))
        tables.addView(actionRow("恢复内置虎码", R.drawable.ic_shape_restore, action = actions.restore))

        val rules = group(body, "输入规则")
        maxCode = text("—", BODY, style.accentText, true)
        rules.addView(actionRow("最大码长", value = maxCode, needsDictionary = true, action = actions.maxCode))
        rules.addView(toggleRow("唯一候选自动上屏", "达到最大码长，且只有一个候选时提交", ShapeStore.AUTO_COMMIT))
        rules.addView(toggleRow("满码顶屏", "继续输入时提交首选，再开始下一组编码", ShapeStore.OVERFLOW))
        rules.addView(toggleRow("按权重排序", "关闭时保留码表中的原始候选顺序", ShapeStore.SORT_WEIGHT))

        val selection = group(body, "快捷选词")
        selection.addView(toggleRow("数字选词", "按 1–9 选择对应候选", ShapeStore.DIGITS))
        selection.addView(toggleRow("分号选二", "用半角分号 ; 选择第二候选", ShapeStore.SEMICOLON))
        selection.addView(toggleRow("引号选三", "用半角单引号 ' 选择第三候选", ShapeStore.QUOTE))

        val runtime = group(body, "运行状态")
        runtime.pad(LG)
        hostStatus = text("正在准备内置码表…", BODY, style.secondary).apply {
            setTextIsSelectable(true)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        runtime.addView(hostStatus)

        val footerContent = column().apply {
            setPadding(style.dp(LG), style.dp(MD), style.dp(LG), style.dp(MD))
            restart = AppCompatButton(context).apply {
                text = "重启输入法"
                textSize = BODY
                setTextColor(style.onAccent)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAllCaps = false
                minHeight = style.dp(BUTTON)
                minimumHeight = style.dp(BUTTON)
                setPadding(style.dp(LG), style.dp(MD), style.dp(LG), style.dp(MD))
                background = style.ripple(style.accent)
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); actions.restart() }
            }
            interactive.add(restart)
            addView(restart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        footer = FrameLayout(context).apply {
            setBackgroundColor(style.surface)
            addView(footerContent, centeredParams())
        }
        addView(footer)
        setBusy(true, "正在准备内置码表…")
    }

    fun show(snapshot: Bundle, pendingRestart: Boolean) {
        binding = true
        hasSnapshot = true
        skeleton.visibility = GONE
        metrics.visibility = VISIBLE
        hasDictionary = snapshot.getInt(ShapeStore.COUNT) > 0
        name.text = snapshot.getString(ShapeStore.NAME, "尚未选择码表")
        count.text = if (hasDictionary) NumberFormat.getIntegerInstance().format(snapshot.getInt(ShapeStore.COUNT)) else "—"
        longest.text = if (hasDictionary) "${snapshot.getInt(ShapeStore.DICT_MAX)} 码" else "—"
        maxCode.text = "${snapshot.getInt(ShapeStore.MAX_CODE, 4)} 码"
        toggles.forEach { (key, toggle) ->
            toggle.isChecked = snapshot.getBoolean(key, key != ShapeStore.SORT_WEIGHT)
        }
        hostStatus.text = if (pendingRestart) "设置已保存，重启输入法后生效。" else
            snapshot.getString(ShapeStore.STATUS, "尚未收到输入法运行结果")
        restart.text = if (pendingRestart) "重启并应用更改" else "重启输入法"
        errorCard.visibility = GONE
        binding = false
        updateEnabled()
    }

    fun setBusy(value: Boolean, message: String = "") {
        busy = value
        skeleton.visibility = if (value && !hasSnapshot) VISIBLE else GONE
        metrics.visibility = if (hasSnapshot) VISIBLE else GONE
        if (value) hostStatus.text = message
        updateEnabled()
    }

    fun showError(message: String, title: String = "操作未完成") {
        errorTitle.text = title
        errorMessage.text = message
        errorCard.visibility = VISIBLE
        skeleton.visibility = GONE
        if (!hasSnapshot) name.text = "尚未选择码表"
        if (!hasSnapshot) hostStatus.text = "可重试读取、导入码表，或恢复内置虎码。"
    }

    private fun updateEnabled() {
        interactive.forEach { view ->
            view.isEnabled = !busy && (view !in dictionaryControls || hasDictionary)
            view.alpha = if (view.isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private fun group(body: LinearLayout, title: String): LinearLayout {
        body.addView(column().apply {
            setPadding(style.dp(XS), 0, style.dp(XS), 0)
            addView(text(title, HEADING, style.primary, true))
        }, spaced(top = XL, bottom = MD))
        return column().apply {
            background = style.surface()
            setPadding(0, style.dp(XS), 0, style.dp(XS))
            body.addView(this)
        }
    }

    private fun metric(label: String): Pair<LinearLayout, TextView> {
        val value = text("—", METRIC, style.primary, true)
        return column().apply {
            addView(value)
            addView(text(label, BODY, style.primary), spaced(top = XS))
        } to value
    }

    private fun labels(title: String, accessible: Boolean = true) = column().apply {
        if (!accessible) importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(text(title, BODY, style.primary, true))
    }

    private fun toggleRow(title: String, subtitle: String, key: String, inset: Boolean = true): View {
        val toggle = SwitchCompat(context).apply {
            minHeight = style.dp(TOUCH)
            minimumWidth = style.dp(SWITCH_WIDTH)
            contentDescription = "$title。$subtitle"
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(style.accent, style.secondary))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(style.accentSurface, style.outline))
            setOnCheckedChangeListener { _, checked ->
                if (!binding) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    actions.toggle(key, checked)
                }
            }
        }
        toggles[key] = toggle
        interactive.add(toggle)
        if (key != ShapeStore.SHOW_IN_PICKER) dictionaryControls.add(toggle)
        return row().apply {
            setPadding(if (inset) style.dp(LG) else 0, style.dp(MD), if (inset) style.dp(LG) else 0, style.dp(MD))
            background = style.ripple()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(labels(title, false), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = style.dp(MD) })
            setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        }
    }

    private fun actionRow(title: String, icon: Int? = null, value: TextView? = null,
                          needsDictionary: Boolean = false, action: () -> Unit): View = row().apply {
        setPadding(style.dp(LG), style.dp(LG), style.dp(LG), style.dp(LG))
        minimumHeight = style.dp(TOUCH)
        background = style.ripple()
        if (icon != null) addView(icon(icon, style.accent).apply {
            background = style.surface(style.accentSurface, SMALL_RADIUS)
            pad(MD)
        }, LayoutParams(style.dp(ICON_TILE), style.dp(ICON_TILE)).apply { marginEnd = style.dp(MD) })
        addView(labels(title), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        value?.let { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = style.dp(SM) }) }
        addView(icon(R.drawable.ic_shape_chevron, style.secondary),
            LayoutParams(style.dp(ICON), style.dp(ICON)).apply { marginStart = style.dp(SM) })
        setOnClickListener { action() }
        // One accessibility node for the entire action, including its current value.
        isFocusable = true
        interactive.add(this)
        if (needsDictionary) dictionaryControls.add(this)
    }

    private fun text(value: String, size: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun icon(id: Int, tint: Int) = ImageView(context).apply {
        setImageResource(id)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun View.pad(value: Int) = setPadding(style.dp(value), style.dp(value), style.dp(value), style.dp(value))
    private fun spaced(top: Int = 0, bottom: Int = 0) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = style.dp(top); bottomMargin = style.dp(bottom)
    }
    private fun centeredParams() = FrameLayout.LayoutParams(
        minOf(resources.displayMetrics.widthPixels, style.dp(MAX_WIDTH)), LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
    private fun centered(child: View) = FrameLayout(context).apply { addView(child, centeredParams()) }
}
