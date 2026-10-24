package io.mo.xatype.keyboard

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

object KeyboardShortcutActions {
    fun perform(connection: InputConnection, action: String, context: Context? = null) {
        when (action) {
            "全选" -> connection.performContextMenuAction(android.R.id.selectAll)
            "复制" -> { connection.performContextMenuAction(android.R.id.selectAll)
                connection.performContextMenuAction(android.R.id.copy) }
            "剪切" -> {
                // Use the host's own cut, but select the whole field first so it acts on all text.
                val extracted = runCatching { connection.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
                val text = extracted?.text
                if (extracted != null && !text.isNullOrEmpty()) {
                    connection.setSelection(extracted.startOffset, extracted.startOffset + text.length)
                }
                connection.performContextMenuAction(android.R.id.cut)
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
