package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardWordSelectionTest {
    @Test fun selectedWordsUseSourceOrderAcrossPages() {
        val words = List(92) { index ->
            when (index) {
                5 -> "文字"
                33 -> "123"
                91 -> "。"
                else -> "跳过"
            }
        }
        val selection = ClipboardWordSelection(words)
        selection.toggle(91)
        selection.toggle(5)
        selection.toggle(33)
        assertEquals("文字123。", selection.text())
    }

    @Test fun identicalWordsCanBeSelectedAndClearedIndependently() {
        val selection = ClipboardWordSelection(listOf("/", "链接", "/"))
        assertTrue(selection.toggle(0))
        assertTrue(selection.toggle(2))
        assertEquals("//", selection.text())
        assertFalse(selection.toggle(0))
        assertTrue(2 in selection)
        assertEquals("/", selection.text())
        assertFalse(selection.toggle(2))
        assertFalse(selection.hasSelection)
        assertEquals("", selection.text())
    }
}
