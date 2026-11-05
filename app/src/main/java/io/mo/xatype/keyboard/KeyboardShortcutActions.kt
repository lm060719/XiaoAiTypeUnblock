package io.mo.xatype.keyboard

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

object KeyboardShortcutActions {
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
