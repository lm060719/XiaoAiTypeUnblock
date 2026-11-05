package io.mo.xatype.keyboard

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

object KeyboardShortcutActions {
    /** Field content removed by [clearInput], for swipe-down restore in the same editor. */
    private val cleared = DeleteSwipeBuffer()

    /** Identity of the focused editor; a restore is only allowed back into the same one. */
    fun editorOwner(editor: EditorInfo?): String? =
        editor?.let { "${it.packageName}#${it.fieldId}#${it.inputType}" }

    /** Password-class inputs, whose content must never be retained in memory. */
    fun isPasswordType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Delete the text before the caret immediately, keeping the suffix after it.
     * [dropComposing] removes the keyboard's own pinyin preedit instead of committing it.
     */
    fun clearInput(connection: InputConnection, editor: EditorInfo?, dropComposing: Boolean = false) {
        if (dropComposing) connection.setComposingText("", 1)
        // Close any pending composing region first; otherwise the host keeps invisible
        // composing state and the next delete consumes it instead of deleting text.
        connection.finishComposingText()
        val before = connection.getTextBeforeCursor(100_000, 0)?.toString().orEmpty()
        cleared.store(before, editorOwner(editor), editor == null || isPasswordType(editor.inputType))
        if (before.isNotEmpty()) connection.deleteSurroundingText(before.length, 0)
    }

    /** Put back the text removed by the preceding swipe-up clear; true when something was restored. */
    fun restoreInput(connection: InputConnection, editor: EditorInfo?): Boolean {
        var text = cleared.take(editorOwner(editor)) ?: return false
        // The host editor appends its own newline to extracted/restored text; do not
        // duplicate it into the field.
        if (text.endsWith("\r\n")) text = text.dropLast(2) else if (text.endsWith("\n")) text = text.dropLast(1)
        if (text.isEmpty()) return false
        connection.commitText(text, 1)
        connection.finishComposingText()
        return true
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
