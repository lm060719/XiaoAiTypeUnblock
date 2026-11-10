package io.mo.xatype.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.keyboard.ShortcutKeyboard

/** The settings app's view of the swipe-shortcut mappings, stored in the shared settings file. */
internal object ShortcutMappings {
    fun load(context: Context, keyboard: ShortcutKeyboard): Map<String, String> =
        keyboard.decode(ConfigManager.getLocalPrefs(context).getString(keyboard.prefKey, null))

    fun save(context: Context, keyboard: ShortcutKeyboard, mapping: Map<String, String>) {
        ConfigManager.getLocalPrefs(context).edit().putString(keyboard.prefKey, keyboard.encode(mapping)).apply()
    }

    fun reset(context: Context, keyboard: ShortcutKeyboard) {
        ConfigManager.getLocalPrefs(context).edit().remove(keyboard.prefKey).apply()
    }

    /** Keys bound to [action] on [keyboard], e.g. "Z / BN", or "—". */
    fun keysFor(context: Context, keyboard: ShortcutKeyboard, action: String): String {
        val mapping = load(context, keyboard)
        val bound = keyboard.keys.filter { mapping[it] == action }.map { key ->
            if (keyboard == ShortcutKeyboard.NINE) KeyboardShortcuts.t9Letters.entries.first { it.value == key }.key.toString()
            else key.uppercase()
        }
        return if (bound.isEmpty()) "—" else bound.joinToString(" / ")
    }
}

/** Assign any letter key of each keyboard to a downward-swipe action. */
class ShortcutKeysActivity : AppCompatActivity() {
    private lateinit var kit: SettingsKit
    private lateinit var body: LinearLayout
    private var selected = ShortcutKeyboard.QWERTY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = SettingsKit(this)
        val style = ShapeUiStyle(this)
        selected = savedInstanceState?.getString(STATE_KEYBOARD)
            ?.let { name -> ShortcutKeyboard.entries.firstOrNull { it.name == name } } ?: ShortcutKeyboard.QWERTY
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(kit.color(R.color.background_light))
        }
        val toolbar = Toolbar(this).apply {
            title = "自定义快捷键"
            setTitleTextColor(style.primary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(style.primary)
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, style.dp(ShapeUiStyle.BUTTON)))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = kit.dimen(R.dimen.space_lg)
            setPadding(side, kit.dimen(R.dimen.space_sm), side, kit.dimen(R.dimen.space_xl))
        }
        root.addView(NestedScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = kit.dimen(R.dimen.space_lg)
            setPadding(side, kit.dimen(R.dimen.space_sm), side, kit.dimen(R.dimen.space_sm))
        }
        footer.addView(Button(this).apply {
            text = "重启输入法并应用"
            textSize = 14f
            isAllCaps = false
            setTextColor(kit.color(R.color.on_primary))
            setBackgroundResource(R.drawable.bg_button_primary)
            stateListAnimator = null
            setOnClickListener { button ->
                button.isEnabled = false
                ImeRestarter.restart(this@ShortcutKeysActivity) {
                    if (!isDestroyed) button.isEnabled = true
                }
            }
        }, LinearLayout.LayoutParams(-1, kit.dp(48)))
        root.addView(footer)
        setContentView(root)
        SettingsWindow.apply(window, root, topBar = toolbar, bottomBar = footer)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_KEYBOARD, selected.name)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        body.removeAllViews()
        body.addView(kit.label("点按键盘上的任意字母键，选择向下滑动时执行的操作。同一操作可以放在多个键上；每种键盘分别保存，重启输入法后生效。",
            13f, R.color.text_secondary).apply {
            setLineSpacing(kit.dp(3).toFloat(), 1f)
            setPadding(kit.dimen(R.dimen.space_xs), kit.dimen(R.dimen.space_sm), kit.dimen(R.dimen.space_xs), 0)
        })
        body.addView(tabs(), LinearLayout.LayoutParams(-1, kit.dp(40)).apply { topMargin = kit.dimen(R.dimen.space_lg) })

        val mapping = ShortcutMappings.load(this, selected)
        val card = kit.card().apply {
            val pad = kit.dimen(R.dimen.space_sm)
            setPadding(pad, pad, pad, pad)
        }
        // Shorter rows are centred so every key keeps the width it has on the real keyboard.
        val widest = selected.rows.maxOf { keys -> keys.sumOf { keyWeight(it).toDouble() } }.toFloat()
        selected.rows.forEachIndexed { index, keys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                weightSum = widest
            }
            // Nine-key's top row starts with the 1 (separator) key, which takes no shortcut.
            if (selected == ShortcutKeyboard.NINE && index == 0) {
                row.addView(placeholderKey("1"), LinearLayout.LayoutParams(0, kit.dp(56), 1f))
            }
            keys.forEach { key -> row.addView(keyView(key, mapping[key]), LinearLayout.LayoutParams(0, kit.dp(56), keyWeight(key))) }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dimen(R.dimen.space_md) })

        val summary = KeyboardShortcuts.actionOrder.joinToString("　") { action ->
            "$action ${ShortcutMappings.keysFor(this, selected, action)}"
        }
        body.addView(kit.label(summary, 13f, R.color.text_primary).apply {
            setLineSpacing(kit.dp(3).toFloat(), 1f)
            setPadding(kit.dimen(R.dimen.space_xs), kit.dimen(R.dimen.space_md), kit.dimen(R.dimen.space_xs), 0)
        })

        val note = when (selected) {
            ShortcutKeyboard.QWERTY -> "用于拼音/英文 26 键、虎码/五笔，以及 18/14 键切到英文后的 26 键。"
            ShortcutKeyboard.NINE -> "用于拼音九键；数字面板不启用快捷操作。"
            else -> "用于中文${selected.title}；切到英文后使用 26 键的设置。"
        }
        body.addView(kit.label(note, 12f, R.color.text_secondary).apply {
            setPadding(kit.dimen(R.dimen.space_xs), kit.dimen(R.dimen.space_sm), kit.dimen(R.dimen.space_xs), 0)
        })

        kit.group(body, null, listOf(
            kit.navRow("恢复默认", "默认：" + selected.defaults.entries.joinToString("、") { (key, action) ->
                "${selected.keyLabel(key).substringBefore(' ')} $action"
            }, R.drawable.ic_shape_restore) {
                ShortcutMappings.reset(this, selected)
                render()
            }
        )).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = kit.dimen(R.dimen.space_lg)
        }
    }

    /** Wider keys for grouped letters, so 18/14-key rows keep their shape. */
    private fun keyWeight(key: String) = if (selected == ShortcutKeyboard.NINE) 1f else 1f + (key.length - 1) * 0.35f

    private fun tabs(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(kit.color(R.color.surface_light), kit.color(R.color.card_stroke))
            val pad = kit.dp(3)
            setPadding(pad, pad, pad, pad)
        }
        ShortcutKeyboard.entries.forEach { keyboard ->
            val active = keyboard == selected
            bar.addView(TextView(this).apply {
                text = keyboard.title
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(kit.color(if (active) R.color.on_primary else R.color.text_primary))
                if (active) background = rounded(kit.color(R.color.primary), null)
                isClickable = true
                isFocusable = true
                contentDescription = keyboard.title + if (active) "，已选择" else ""
                setOnClickListener {
                    if (keyboard == selected) return@setOnClickListener
                    SettingsKit.tickHaptic(it)
                    selected = keyboard
                    render()
                }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return bar
    }

    private fun keyView(key: String, action: String?): View {
        val bound = action != null
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(kit.color(if (bound) R.color.primary_container else R.color.background_light),
                if (bound) kit.color(R.color.primary) else kit.color(R.color.card_stroke))
            isClickable = true
            isFocusable = true
            foreground = kit.selectableBackground()
            contentDescription = "${selected.keyLabel(key)} 键，" + (action?.let { "下滑$it" } ?: "未设置")
            setOnClickListener { choose(key) }
        }
        val label = if (selected == ShortcutKeyboard.NINE) selected.keyLabel(key).replace(' ', '\n') else key.uppercase()
        cell.addView(kit.label(label, if (selected == ShortcutKeyboard.NINE) 12f else 15f, R.color.text_primary, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        })
        cell.addView(kit.label(action ?: "—", 10f, if (bound) R.color.primary else R.color.text_secondary, bold = bound).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        })
        return LinearLayout(this).apply {
            val gap = kit.dp(2)
            setPadding(gap, gap, gap, gap)
            addView(cell, LinearLayout.LayoutParams(-1, -1))
        }
    }

    private fun placeholderKey(label: String): View = LinearLayout(this).apply {
        val gap = kit.dp(2)
        setPadding(gap, gap, gap, gap)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(kit.label(label, 12f, R.color.text_secondary).apply {
            gravity = Gravity.CENTER
            background = rounded(kit.color(R.color.background_light), null)
            alpha = 0.6f
        }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun choose(key: String) {
        val mapping = ShortcutMappings.load(this, selected)
        val options = listOf("不设置") + KeyboardShortcuts.actionOrder
        val current = mapping[key]?.let { options.indexOf(it) } ?: 0
        InfoSheet.choose(this, "${selected.title} ${selected.keyLabel(key)} 键下滑", options, current) { index ->
            val updated = mapping.toMutableMap()
            if (index == 0) updated.remove(key) else updated[key] = options[index]
            ShortcutMappings.save(this, selected, updated)
            render()
        }
    }

    private fun rounded(fill: Int, stroke: Int?) = GradientDrawable().apply {
        cornerRadius = resources.getDimension(R.dimen.radius_sm)
        setColor(fill)
        stroke?.let { setStroke(kit.dp(1), it) }
    }

    private companion object {
        const val STATE_KEYBOARD = "shortcut_keyboard"
    }
}
