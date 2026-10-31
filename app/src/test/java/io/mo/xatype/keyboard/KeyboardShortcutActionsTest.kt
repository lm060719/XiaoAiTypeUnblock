package io.mo.xatype.keyboard

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class KeyboardShortcutActionsTest {
    /** An editor model that rejects all unrelated IPC (key events, extraction, editor actions). */
    private class Editor(var text: String, var start: Int, var end: Int,
                         val selectAllSupported: Boolean = true, val readThrows: Boolean = false) {
        var clipboard = "unchanged"
        val events = mutableListOf<Int>()
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getSelectedText" -> {
                    if (readThrows) error("Connection is closed")
                    text.substring(minOf(start, end), maxOf(start, end)).ifEmpty { null }
                }
                "performContextMenuAction" -> {
                    val action = args!![0] as Int
                    events.add(action)
                    when (action) {
                        android.R.id.selectAll -> if (!selectAllSupported) false else {
                            start = 0; end = text.length; true
                        }
                        android.R.id.copy, android.R.id.cut -> {
                            val left = minOf(start, end)
                            val right = maxOf(start, end)
                            if (left == right) false else {
                                clipboard = text.substring(left, right)
                                if (action == android.R.id.cut) {
                                    text = text.removeRange(left, right); start = left; end = left
                                }
                                true
                            }
                        }
                        android.R.id.paste -> {
                            val left = minOf(start, end)
                            text = text.replaceRange(left, maxOf(start, end), clipboard)
                            start = left + clipboard.length; end = start; true
                        }
                        else -> error("Unexpected action $action")
                    }
                }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as InputConnection
    }

    @Test fun `copy and cut preserve forward and reversed selections`() {
        for (action in listOf("复制", "剪切")) for ((start, end) in listOf(1 to 3, 3 to 1)) {
            val editor = Editor("甲乙丙丁", start, end)
            KeyboardShortcutActions.perform(editor.connection, action)
            assertEquals("乙丙", editor.clipboard)
            assertEquals(if (action == "剪切") "甲丁" else "甲乙丙丁", editor.text)
            assertEquals(listOf(if (action == "剪切") android.R.id.cut else android.R.id.copy), editor.events)
        }
    }

    @Test fun `copy and cut without selection select the entire multiline editor first`() {
        val text = "长文本😀\n".repeat(2000)
        for (action in listOf("复制", "剪切")) {
            val editor = Editor(text, 100, 100)
            KeyboardShortcutActions.perform(editor.connection, action)
            assertEquals(text, editor.clipboard)
            assertEquals(if (action == "剪切") "" else text, editor.text)
            assertEquals(listOf(android.R.id.selectAll,
                if (action == "剪切") android.R.id.cut else android.R.id.copy), editor.events)
        }
    }

    @Test fun `rejected select all never proceeds to copy or cut`() {
        for (action in listOf("复制", "剪切")) {
            val editor = Editor("保留内容", 2, 2, selectAllSupported = false)
            KeyboardShortcutActions.perform(editor.connection, action)
            assertEquals("保留内容", editor.text)
            assertEquals("unchanged", editor.clipboard)
            assertEquals(listOf(android.R.id.selectAll), editor.events)
        }
    }

    @Test fun `failed selection query does not proceed to a destructive action`() {
        val editor = Editor("保留内容", 1, 3, readThrows = true)
        assertTrue(runCatching { KeyboardShortcutActions.perform(editor.connection, "剪切") }.isFailure)
        assertEquals("保留内容", editor.text)
        assertTrue(editor.events.isEmpty())
    }

    @Test fun `select all cut paste round trip preserves unicode and keeps native editor operations`() {
        val editor = Editor("你好😀\n第二行", 2, 2)
        KeyboardShortcutActions.perform(editor.connection, "全选")
        assertEquals(0, editor.start)
        assertEquals(editor.text.length, editor.end)
        KeyboardShortcutActions.perform(editor.connection, "剪切")
        assertEquals("", editor.text)
        KeyboardShortcutActions.perform(editor.connection, "粘贴")
        assertEquals("你好😀\n第二行", editor.text)
        assertEquals(listOf(android.R.id.selectAll, android.R.id.cut, android.R.id.paste), editor.events)
    }

    @Test fun `empty editor and unknown actions leave clipboard and contents alone`() {
        val editor = Editor("", 0, 0)
        KeyboardShortcutActions.perform(editor.connection, "剪切")
        assertEquals("", editor.text)
        assertEquals("unchanged", editor.clipboard)
        editor.events.clear()
        KeyboardShortcutActions.perform(editor.connection, "未知动作")
        assertTrue(editor.events.isEmpty())
    }
}
