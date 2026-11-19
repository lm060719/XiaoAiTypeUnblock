package io.mo.xatype.keyboard

enum class PredictionMode(val title: String) {
    KEEP("跟随输入法"),
    DISABLE("关闭");

    companion object {
        fun resolve(value: String?): PredictionMode = when (value) {
            "DISABLE", "DISABLE_NEXT", "DISABLE_CONTINUOUS" -> DISABLE
            else -> KEEP
        }
    }
}
