package io.mo.xatype.hooks

import kotlin.math.roundToInt

/** Native dimension adjustments use dp margins and an offset from the centred keyboard. */
object OneHandPolicy {
    const val OFF = 0
    const val LEFT = 1
    const val RIGHT = 2
    const val DEFAULT_WIDTH = 80
    val WIDTH_RANGE = 70..90
    private const val MIN_WIDTH_DP = 240
    data class Geometry(val leftMargin: Int, val rightMargin: Int, val offset: Int)

    const val LEFT_ACTION = "左手"
    const val RIGHT_ACTION = "右手"
    const val MENU_ACTION = "单手模式"
    fun actionSide(action: String): Int? = when (action) {
        LEFT_ACTION, "左手模式" -> LEFT
        RIGHT_ACTION, "右手模式" -> RIGHT
        else -> null
    }
    fun rememberedSide(current: Int, previous: Int): Int =
        current.takeIf { it in LEFT..RIGHT } ?: previous.takeIf { it in LEFT..RIGHT } ?: RIGHT

    fun menuTarget(current: Int, previous: Int): Int =
        if (current in LEFT..RIGHT) OFF else rememberedSide(current, previous)
    fun toggle(current: Int, requested: Int): Int {
        require(requested in LEFT..RIGHT)
        return if (current == requested) OFF else requested
    }

    fun geometry(availableDp: Int, percent: Int, side: Int, portrait: Boolean, floating: Boolean): Geometry? {
        if (!portrait || floating || side !in LEFT..RIGHT || availableDp <= MIN_WIDTH_DP) return null
        val width = (availableDp * percent.coerceIn(WIDTH_RANGE) / 100f).roundToInt()
            .coerceIn(MIN_WIDTH_DP, availableDp)
        val gap = availableDp - width
        if (gap == 0) return null
        val left = gap / 2
        val right = gap - left
        return Geometry(left, right, if (side == LEFT) -left else right)
    }
}
