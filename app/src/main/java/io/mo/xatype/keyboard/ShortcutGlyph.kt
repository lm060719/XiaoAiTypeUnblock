package io.mo.xatype.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Canvas paths avoid emoji/font substitutions and inherit the native letter color. */
object ShortcutGlyph {
    fun draw(canvas: Canvas, action: String, cx: Float, cy: Float, size: Float, color: Int, alpha: Float) {
        if (alpha <= 0f || size <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.alpha = (android.graphics.Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt()
            style = Paint.Style.STROKE
            strokeWidth = ShortcutSwipeGesture.ICON_STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val save = canvas.save()
        try {
            canvas.translate(cx - size / 2f, cy - size / 2f)
            canvas.scale(size / ShortcutSwipeGesture.ICON_SIZE, size / ShortcutSwipeGesture.ICON_SIZE)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
            fun box(l: Float, t: Float, r: Float, b: Float) = canvas.drawRoundRect(RectF(l, t, r, b), 1.8f, 1.8f, paint)
            when (action) {
                "复制" -> { box(8f, 8f, 20f, 21f); line(16f, 4f, 4f, 4f); line(4f, 4f, 4f, 16f) }
                "粘贴" -> {
                    box(5f, 5f, 19f, 21f); box(9f, 3f, 15f, 7f)
                    line(9f, 12f, 15f, 12f); line(9f, 16f, 15f, 16f)
                }
                "剪切" -> {
                    canvas.drawCircle(7f, 18.5f, 3f, paint); canvas.drawCircle(17f, 18.5f, 3f, paint)
                    line(8.6f, 16f, 20f, 3f); line(15.4f, 16f, 4f, 3f)
                }
                "全选" -> {
                    line(3f, 8f, 3f, 3f); line(3f, 3f, 8f, 3f)
                    line(16f, 3f, 21f, 3f); line(21f, 3f, 21f, 8f)
                    line(3f, 16f, 3f, 21f); line(3f, 21f, 8f, 21f)
                    line(16f, 21f, 21f, 21f); line(21f, 21f, 21f, 16f)
                    line(8f, 9f, 16f, 9f); line(8f, 15f, 16f, 15f)
                }
                "段首", "段尾" -> {
                    if (action == "段尾") { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }
                    line(4f, 5f, 4f, 19f); line(8f, 12f, 20f, 12f)
                    line(8f, 12f, 12f, 8f); line(8f, 12f, 12f, 16f)
                }
            }
        } finally { canvas.restoreToCount(save) }
    }
}
