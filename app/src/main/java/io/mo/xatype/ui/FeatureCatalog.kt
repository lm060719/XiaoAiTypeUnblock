package io.mo.xatype.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.mo.xatype.R
import io.mo.xatype.config.ConfigManager
import io.mo.xatype.ui.SettingsKit.Detail

/** A plain on/off hook setting: a one-line [summary] under the title, caveats in an optional [detail]. */
internal class Feature(val key: String, val title: String, val summary: String, val detail: Detail? = null)

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
            Feature(ConfigManager.KEY_CLIPBOARD_PERMANENT, "剪贴板永久保存", "不限条数、时长与文字长度")
        ),
        "隐私" to listOf(
            Feature(ConfigManager.KEY_TELEMETRY_BLOCK, "遥测与埋点拦截", "关闭小米统计与广告监控上报")
        )
    )

    val keyboardShortcuts = Feature(ConfigManager.KEY_KEYBOARD_SHORTCUTS, "按键下滑快捷操作",
        "按住按键下滑，快速段首、复制、粘贴、剪切、全选", Detail(
            "按住对应按键向下滑，字母会随距离渐变为功能图标；轻震后松手执行，往回滑可取消。",
            notes = listOf("适用于拼音九键、18 键与拼音/英文 26 键。18 键切到英文后使用 26 键快捷操作。",
                "复制、剪切优先处理已选文字；没有选区时先全选。剪切使用系统编辑操作。"),
            extra = ::shortcutTable
        ))

    /** Hint text drawn on the same keys that carry the swipe action. */
    val shortcutLabels = Feature(ConfigManager.KEY_SHORTCUT_LABELS, "在按键上显示操作文字",
        "在九键、18键标出段首、复制、粘贴、全选等文字，大小与位置可调")

    val keyboardHeight = Feature(ConfigManager.KEY_KEYBOARD_HEIGHT_UNBLOCK, "解除键盘高度上限",
        "可继续向上拉伸")

    val t9Shuangpin = Feature(ConfigManager.KEY_T9_SHUANGPIN, "九键双拼", "在拼音九键中使用双拼")

    val emojiCandidates = Feature(ConfigManager.KEY_EMOJI_CANDIDATES, "Emoji 候选增强",
        "打出关键词时，同时候选相关表情", Detail(
            "根据当前输入与前几项汉字候选离线匹配表情，保留汉字首选，点选表情直接上屏。",
            notes = listOf("开启后重启输入法。支持原生全拼及双拼，输入完整关键词时增强。",
                "每个词最多补充 3 个表情。整句中的部分词、已有选词片段、密码框与独立形码/18 键引擎不增强。")
        ))

    val bottomSpacing = Feature(ConfigManager.KEY_BOTTOM_SPACING_ENABLED, "自定义键盘底部间距",
        "调节按键下方的留白")

    val keyboardFeatures get() = listOf(keyboardShortcuts, shortcutLabels, emojiCandidates, keyboardHeight, bottomSpacing, t9Shuangpin)

    /** Rows of action, nine-key, 26-key and 18-key triggers. */
    private val shortcutKeys = listOf(
        listOf("段首", "—", "—", "Z"),
        listOf("复制", "8", "C", "XC"),
        listOf("粘贴", "9", "V", "V"),
        listOf("剪切", "4", "B", "—"),
        listOf("全选", "7", "Z", "BN"),
        listOf("段尾", "6", "M", "M")
    )

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
                        setBackgroundResource(R.drawable.bg_key_chip)
                    }
                }
                addView(cell, LinearLayout.LayoutParams(0, if (header || index == 0) LinearLayout.LayoutParams.WRAP_CONTENT else kit.dp(32), 1f).apply {
                    if (index > 0) marginStart = kit.dimen(R.dimen.space_sm)
                })
            }
        }
        table.addView(line(listOf("操作", "九键", "26 键", "18 键"), header = true))
        shortcutKeys.forEach { cells ->
            table.addView(line(cells, header = false), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = kit.dimen(R.dimen.space_sm) })
        }
        return table
    }
}
