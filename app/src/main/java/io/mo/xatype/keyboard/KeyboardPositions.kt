package io.mo.xatype.keyboard

import io.mo.xatype.keyboard18.GroupedKeys
import io.mo.xatype.keyboard18.Keyboard14Keys
import io.mo.xatype.keyboard18.Keyboard18HostLayout
import io.mo.xatype.keyboard18.Keyboard18Keys

internal object PositionKeyCode {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -6
    const val SEPARATOR = Keyboard18HostLayout.SEPARATOR_CODE
    const val SYMBOLS = -9
    const val LANGUAGE = -10
    const val NUMBERS = -15
    const val SPACE = 32
    const val COMMA = 44
    const val PERIOD = 46
}

internal data class PositionKey(
    val id: Int,
    val label: String,
    val spokenLabel: String = label,
    val weight: Float = 1f,
    val hint: String? = null,
)

private fun letterRow(letters: String) = letters.map { PositionKey(it.code, it.uppercase()) }

private fun thirdRow(letters: List<PositionKey>, separator: Boolean = false) =
    listOf(if (separator) PositionKey(PositionKeyCode.SEPARATOR, "分词") else
        PositionKey(PositionKeyCode.SHIFT, "⇧", "Shift", 1.4f)) +
        letters + PositionKey(PositionKeyCode.DELETE, "⌫", "删除", 1.4f)

private val bottomRow = listOf(
    PositionKey(PositionKeyCode.SYMBOLS, "符号"),
    PositionKey(PositionKeyCode.NUMBERS, "123", "数字"),
    PositionKey(PositionKeyCode.COMMA, "，", "逗号"),
    PositionKey(PositionKeyCode.SPACE, "空格", weight = 3f),
    PositionKey(PositionKeyCode.PERIOD, "。", "句号"),
    PositionKey(PositionKeyCode.LANGUAGE, "中英", "切换中英文"),
    PositionKey(PositionKeyCode.ENTER, "↵", "回车", 1.4f)
)

private fun groupedRows(keys: GroupedKeys): List<List<PositionKey>> =
    keys.rows.mapIndexed { index, row ->
        val letters = row.map { PositionKey(keys.code(it), it.uppercase()) }
        if (index == 2) thirdRow(letters, keys.separatorKey) else letters
    } + listOf(bottomRow)

private fun nineRows(): List<List<PositionKey?>> =
    (listOf(null) + KeyboardShortcuts.t9Letters.map { (digit, letters) ->
        PositionKey(digit.code, "$digit ${letters.uppercase()}")
    }).chunked(3)

private fun strokeRows() = listOf(
    PositionKey('1'.code, "一", "横"),
    PositionKey('2'.code, "丨", "竖"),
    PositionKey('3'.code, "丿", "撇"),
    PositionKey('4'.code, "丶", "点"),
    PositionKey('5'.code, "𠃋", "折"),
    PositionKey('6'.code, "通配"),
    PositionKey(PositionKeyCode.SEPARATOR, "分词"),
    PositionKey('：'.code, "：", "冒号"),
    PositionKey('；'.code, "；", "分号"),
).mapIndexed { index, key -> key.copy(hint = (index + 1).toString()) }.chunked(3)

/** An order is a permutation of stable key IDs, indexed by the original slots in the preview. */
internal enum class KeyboardPositions(val title: String, val rows: List<List<PositionKey?>>) {
    QWERTY("26 键", listOf(letterRow("qwertyuiop"), letterRow("asdfghjkl"),
        thirdRow(letterRow("zxcvbnm")), bottomRow)),
    NINE("9 键", nineRows()),
    GROUPED18("18 键", groupedRows(Keyboard18Keys)),
    GROUPED14("14 键", groupedRows(Keyboard14Keys)),
    STROKE("笔画", strokeRows());

    val prefKey = "pref_key_positions_${name.lowercase()}"
    val keys = rows.flatten().filterNotNull().associateBy(PositionKey::id)
    val defaultOrder = keys.keys.toList()

    fun decode(raw: String?): List<Int> {
        if (raw.isNullOrEmpty()) return defaultOrder
        val ids = raw.split(',').map { it.toIntOrNull() ?: return defaultOrder }
        return ids.takeIf { it.size == defaultOrder.size && it.toSet() == keys.keys } ?: defaultOrder
    }

    fun movedKeys(raw: String?): Map<Int, Int> = defaultOrder.zip(decode(raw))
        .filter { (slot, key) -> slot != key }.toMap()
}
