package io.mo.xatype.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.keyboard.KeyboardShortcuts
import io.mo.xatype.keyboard.ShortcutKeyboard
import io.mo.xatype.ui.SettingsKit.Detail

/** A plain on/off hook setting with an optional [summary] and caveats in an optional [detail]. */
internal class Feature(val key: String, val title: String, val summary: String?, val detail: Detail? = null)

/** Every feature's wording, used by the settings tabs. */
internal object FeatureCatalog {

    val purifyGroups = listOf(
        "内容风控" to listOf(
            Feature(ConfigManager.KEY_AI_SAFETY, "AI 表达安全拦截解除", "AI 润色与回复不再被屏蔽"),
            Feature(ConfigManager.KEY_VOICE_MODERATION, "语音转写合规审查解除", "语音输入不再被风控打断"),
            Feature(ConfigManager.KEY_CLOUD_BLACKLIST, "云端黑名单词库下发拦截", "保留完整的云端联想与热词")
        ),
        "系统与机型限制" to listOf(
            Feature(ConfigManager.KEY_OS_VERSION_UNBLOCK, "澎湃 OS4+ 版本限制解除", "旧系统与第三方 ROM 也能使用"),
            Feature(ConfigManager.KEY_AI_ABILITY_UNLOCK, "解除 AI 功能机型限制", "显示被机型限制隐藏的 AI 入口")
        ),
        "剪贴板" to listOf(
            Feature(ConfigManager.KEY_CLIPBOARD_SENSITIVE, "剪贴板敏感标记忽略绕过", "敏感内容也能记录与联想"),
            Feature(ConfigManager.KEY_CLIPBOARD_PERMANENT, "剪贴板永久保存", "不限条数、时长与文字长度"),
            Feature(ConfigManager.KEY_SCREENSHOT_CLIPBOARD, "截图自动复制", "截图保存后自动写入剪贴板")
        ),
        "隐私" to listOf(
            Feature(ConfigManager.KEY_TELEMETRY_BLOCK, "遥测与埋点拦截", "关闭小米统计与广告监控上报")
        )
    )

    val keyboardShortcuts = Feature(ConfigManager.KEY_KEYBOARD_SHORTCUTS, "按键下滑快捷操作",
        null, Detail(
            "按住对应按键向下滑，字母会随距离渐变为功能图标；轻震后松手执行，往回滑可取消。可在「自定义快捷键」中把操作改到任意字母键。",
            notes = listOf("适用于拼音九键、18 键、14 键、拼音/英文 26 键与虎码/五笔。18/14 键切到英文、虎码/五笔均使用 26 键快捷操作。",
                "复制、剪切优先处理已选文字；没有选区时先全选。剪切使用系统编辑操作。",
                "方向键先将组词上屏，再移动文本光标。Esc、撤销、恢复及对应组合键在组词时仅取消组词，再次操作才发送给应用。",
                "可设置 Ctrl、Alt、Shift 组合键，每次下滑发送完整组合；撤销、恢复和按键行为由目标应用决定。"),
            extra = ::shortcutTable
        ))

    /** Hint text drawn on the same keys that carry the swipe action. */
    val shortcutLabels = Feature(ConfigManager.KEY_SHORTCUT_LABELS, "在按键上显示操作文字",
        null)

    val keyboardHandwriting = Feature(ConfigManager.KEY_KEYBOARD_HANDWRITING, "键盘区域手写",
        null)

    val keyboardHeight = Feature(ConfigManager.KEY_KEYBOARD_HEIGHT_UNBLOCK, "解除键盘高度上限",
        "可继续向上拉伸")

    val keyboardHaptics = Feature(ConfigManager.KEY_NATIVE_VIBRATION_LIMIT, "提高原生振动上限",
        "输入法原有振动滑块最高可调到 200",
        Detail("开启后，将输入法原有的振动强度滑块从 0～100 扩展到 0～200。0～100 的含义保持不变，继续使用原生振动算法和试振功能。",
            notes = listOf("重启输入法后，在输入法的按键反馈设置中选择「自定义」振动模式，再调整原有滑块。",
                "关闭后，实际使用值限制在原生上限 100；已保存的更高数值会保留，重新开启后可继续使用。",
                "超过 100 的效果由系统和马达决定，部分设备可能限制最大强度。")))

    val t9Shuangpin = Feature(ConfigManager.KEY_T9_SHUANGPIN, "九键双拼", "在拼音九键中使用双拼")

    val emojiCandidates = Feature(ConfigManager.KEY_EMOJI_CANDIDATES, "Emoji 候选增强",
        "打出关键词时，同时候选相关表情")

    val bottomSpacing = Feature(ConfigManager.KEY_BOTTOM_SPACING_ENABLED, "自定义键盘底部间距",
        "调节按键下方的留白")

    val toolbarHeight = Feature(ConfigManager.KEY_TOOLBAR_HEIGHT_ENABLED, "自定义顶部功能栏高度",
        "调薄顶部区域，减少键盘占用的空间",
        SettingsKit.Detail("调节键盘顶部功能栏与候选栏共用区域的高度，范围为 48～80 dp。数值越小，顶部区域越薄。",
            notes = listOf("关闭后恢复原生高度，保留已选数值。修改后重启输入法生效。",
                "同步调整键盘总高度，保留字母按键的高度；悬浮键盘保持原生布局。")))

    val keyboardFeatures get() = listOf(keyboardShortcuts, shortcutLabels, keyboardHandwriting, emojiCandidates, keyboardHeight, bottomSpacing, toolbarHeight, t9Shuangpin, keyboardHaptics)

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
