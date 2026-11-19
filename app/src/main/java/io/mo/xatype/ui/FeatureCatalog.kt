package io.mo.xatype.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.keyboard.ShortcutKeyboard
import io.mo.xatype.ui.SettingsKit.Detail

internal class Feature(val key: String, val title: String, val detail: Detail? = null)

/** Every feature's wording, used by the settings tabs. */
internal object FeatureCatalog {

    val purifyGroups = listOf(
        "内容风控" to listOf(
            Feature(ConfigManager.KEY_AI_SAFETY, "解除 AI 表达拦截"),
            Feature(ConfigManager.KEY_VOICE_MODERATION, "解除语音转写审查"),
            Feature(ConfigManager.KEY_CLOUD_BLACKLIST, "拦截云端黑名单下发")
        ),
        "系统与机型限制" to listOf(
            Feature(ConfigManager.KEY_OS_VERSION_UNBLOCK, "解除澎湃 OS 4+ 限制"),
            Feature(ConfigManager.KEY_AI_ABILITY_UNLOCK, "解除 AI 功能机型限制")
        ),
        "剪贴板" to listOf(
            Feature(ConfigManager.KEY_CLIPBOARD_SENSITIVE, "保存剪贴板敏感内容",
                Detail("允许被系统标记为敏感的剪贴板内容进入输入法历史及联想。")),
            Feature(ConfigManager.KEY_CLIPBOARD_PERMANENT, "剪贴板永久保存",
                Detail("解除剪贴板记录的条数、时长和文字长度限制。")),
            Feature(ConfigManager.KEY_SCREENSHOT_CLIPBOARD, "自动复制截图")
        ),
        "隐私" to listOf(
            Feature(ConfigManager.KEY_TELEMETRY_BLOCK, "拦截小米遥测与埋点")
        )
    )

    val keyboardShortcuts = Feature(ConfigManager.KEY_KEYBOARD_SHORTCUTS, "按键下滑快捷操作",
        Detail(
            "按住对应按键向下滑，字母会随距离渐变为功能图标；轻震后松手执行，往回滑可取消。可在「自定义快捷键」中把操作改到任意字母键。",
            notes = listOf("适用于拼音九键、18 键、14 键、拼音/英文 26 键与虎码/五笔。18/14 键切到英文、虎码/五笔均使用 26 键快捷操作。",
                "复制、剪切优先处理已选文字；没有选区时先全选。剪切使用系统编辑操作。",
                "方向键先将组词上屏，再移动文本光标。Esc、撤销、恢复及对应组合键在组词时仅取消组词，再次操作才发送给应用。",
                "可设置 Ctrl、Alt、Shift 组合键，每次下滑发送完整组合；撤销、恢复和按键行为由目标应用决定。"),
            extra = ::shortcutTable
        ))

    /** Hint text drawn on the same keys that carry the swipe action. */
    val shortcutLabels = Feature(ConfigManager.KEY_SHORTCUT_LABELS, "在按键上显示操作文字")

    val keyboardHandwriting = Feature(ConfigManager.KEY_KEYBOARD_HANDWRITING, "键盘区域手写")

    val keyboardHeight = Feature(ConfigManager.KEY_KEYBOARD_HEIGHT_UNBLOCK, "解除键盘高度上限")

    val keyboardHaptics = Feature(ConfigManager.KEY_NATIVE_VIBRATION_LIMIT, "原生振动上限提高至 200",
        Detail("开启后，将输入法原有的振动强度滑块从 0～100 扩展到 0～200。0～100 的含义保持不变，继续使用原生振动算法和试振功能。",
            notes = listOf("重启输入法后，在输入法的按键反馈设置中选择「自定义」振动模式，再调整原有滑块。",
                "关闭后，实际使用值限制在原生上限 100；已保存的更高数值会保留，重新开启后可继续使用。",
                "超过 100 的效果由系统和马达决定，部分设备可能限制最大强度。")))

    val t9Shuangpin = Feature(ConfigManager.KEY_T9_SHUANGPIN, "九键双拼")

    val emojiCandidates = Feature(ConfigManager.KEY_EMOJI_CANDIDATES, "显示 Emoji 候选")

    val bottomSpacing = Feature(ConfigManager.KEY_BOTTOM_SPACING_ENABLED, "自定义键盘底部间距")

    val toolbarHeight = Feature(ConfigManager.KEY_TOOLBAR_HEIGHT_ENABLED, "自定义顶部功能栏高度",
        Detail("调节键盘顶部功能栏与候选栏共用区域的高度，范围为 48～80 dp。数值越小，顶部区域越薄。",
            notes = listOf("关闭后恢复原生高度，保留已选数值。修改后重启输入法生效。",
                "同步调整键盘总高度，保留字母按键的高度；悬浮键盘保持原生布局。")))

    private fun shortcutTable(kit: SettingsKit): View {
        val context = kit.context
        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun line(cells: List<String>, header: Boolean) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            cells.forEachIndexed { index, text ->
                val cell = when {
                    header -> kit.label(text, 12f, R.color.text_secondary).apply { if (index > 0) gravity = Gravity.CENTER }
                    index == 0 -> kit.label(text, 14f, R.color.text_primary)
                    else -> kit.label(text, 14f, R.color.primary, bold = true).apply {
                        gravity = Gravity.CENTER
                        minHeight = kit.dp(32)
                        val pad = kit.dp(2)
                        setPadding(pad, pad, pad, pad)
                        setBackgroundResource(R.drawable.bg_key_chip)
                    }
                }
                addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = kit.dimen(R.dimen.space_sm)
                })
            }
        }
        table.addView(line(listOf("操作") + ShortcutKeyboard.entries.map { it.title }, header = true))
        // The user's current assignment; see ShortcutKeysActivity.
        val custom = ShortcutKeyboard.entries.flatMap { ShortcutMappings.load(context, it).values }
        val rows = (KeyboardShortcuts.actionOrder + custom).distinct().map { action ->
            listOf(KeyboardShortcuts.label(action)) + ShortcutKeyboard.entries.map { ShortcutMappings.keysFor(context, it, action) }
        }
        rows.forEach { cells ->
            table.addView(line(cells, header = false), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = kit.dimen(R.dimen.space_sm) })
        }
        return table
    }
}
