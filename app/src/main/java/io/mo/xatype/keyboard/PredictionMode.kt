package io.mo.xatype.keyboard

enum class PredictionMode(val title: String, val summary: String) {
    KEEP("保持", "使用输入法原有的联想"),
    DISABLE("关闭", "文字上屏后不再显示联想词");

    companion object {
        fun resolve(value: String?): PredictionMode = when (value) {
            "DISABLE", "DISABLE_NEXT", "DISABLE_CONTINUOUS" -> DISABLE
            else -> KEEP
        }
    }
}
