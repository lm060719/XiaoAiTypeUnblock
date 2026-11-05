package io.mo.xatype.keyboard

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

object KeyboardShortcutActions {
    /** Last field content removed by [clearInput], for swipe-down restore. */
    private var clearedText: String? = null

    /** Delete the visible field content immediately, without waiting for key repeat. */
    fun clearInput(connection: InputConnection) {
        // Close any pending composing region first; otherwise the host keeps invisible
        // composing state and the next delete consumes it instead of deleting text.
        connection.finishComposingText()
        // Swipe-up clears only the text before the caret; keep the suffix after it.
        val before = connection.getTextBeforeCursor(100_000, 0)?.toString().orEmpty()
        if (before.isEmpty()) {
            clearedText = null
            return
        }
        clearedText = before
        connection.deleteSurroundingText(before.length, 0)
    }

    /** Put back the text removed by the immediately preceding swipe-up clear. */
    fun restoreInput(connection: InputConnection) {
        var text = clearedText ?: return
        clearedText = null
        // The host editor appends its own newline to extracted/restored text; do not
        // duplicate it into the field.
        if (text.endsWith("\r\n")) text = text.dropLast(2) else if (text.endsWith("\n")) text = text.dropLast(1)
        if (text.isEmpty()) return
        connection.commitText(text, 1)
        connection.finishComposingText()
    }

    fun perform(connection: InputConnection, action: String) {
        when (action) {
            "全选" -> connection.performContextMenuAction(android.R.id.selectAll)
            "复制", "剪切" -> {
                // Preserve an explicit selection. ExtractedText may contain only a window of
                // the editor, so it cannot establish the range for selecting the whole field.
                if (connection.getSelectedText(0).isNullOrEmpty() &&
                    !connection.performContextMenuAction(android.R.id.selectAll)) return
                connection.performContextMenuAction(if (action == "复制") android.R.id.copy else android.R.id.cut)
            }
            "粘贴" -> connection.performContextMenuAction(android.R.id.paste)
            "段首", "段尾" -> {
                val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
                val text = extracted?.text
                if (text != null) {
                    val position = KeyboardShortcuts.paragraphPosition(text, extracted.selectionStart,
                        extracted.startOffset, action == "段尾")
                    connection.setSelection(position, position)
                } else {
                    val key = if (action == "段首") KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                    connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                }
            }
        }
    }
}
