package io.mo.xatype.keyboard

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class ShortcutKeyChordTest {
    @Test fun `supported keys and modifier combinations round trip canonically`() {
        for (key in ShortcutKeyChord.keys.keys) for (mask in 0..7) {
            val chord = ShortcutKeyChord(key, mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            assertEquals(chord, ShortcutKeyChord.parse(chord.encoded))
            assertTrue(KeyboardShortcuts.isAction(chord.encoded))
            assertEquals(chord.label, KeyboardShortcuts.label(chord.encoded))
        }
    }

    @Test fun `native characters and malformed persisted chords never become synthetic actions`() {
        for (raw in listOf("A", "Z", "Ctrl+Z", "Ctrl", "按键:Ctrl", "按键:Ctrl+Ctrl+Z", "按键:Alt+Ctrl+Z",
            "按键:Ctrl+", "按键:Ctrl+Z,c=剪切", "按键:Ctrl+Z=Q", "按键:Meta+Z", "按键:ctrl+Z", "按键:F13", "按键:")) {
            assertNull(raw, ShortcutKeyChord.parse(raw))
            assertFalse(raw, KeyboardShortcuts.isAction(raw))
        }
    }

    @Test fun `main key carries modifiers and releases unwind in reverse order`() {
        val chord = ShortcutKeyChord("Left", ctrl = true, alt = true, shift = true)
        val strokes = chord.strokes()
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT), strokes.map { it.code })
        assertEquals(listOf(true, true, true, true, false, false, false, false), strokes.map { it.down })
        assertEquals(chord.metaState, strokes[3].meta)
        assertEquals(chord.metaState, strokes[4].meta)
        assertEquals(0, strokes.last().meta)
        val delivered = mutableListOf<ShortcutKeyChord.Stroke>()
        assertTrue(chord.dispatch { delivered.add(it); true })
        assertEquals(strokes, delivered)
    }

    @Test fun `rejected event stops sending and releases all possibly held keys`() {
        val chord = ShortcutKeyChord("Z", ctrl = true, shift = true)
        for (failureAt in chord.strokes().indices) {
            val delivered = mutableListOf<ShortcutKeyChord.Stroke>()
            assertFalse(chord.dispatch { stroke -> delivered.add(stroke); delivered.lastIndex != failureAt })
            val held = mutableSetOf<Int>()
            delivered.forEach { if (it.down) held.add(it.code) else held.remove(it.code) }
            assertTrue("Failure at $failureAt left $held", held.isEmpty())
            assertEquals(0, delivered.last().meta)
        }
    }

    @Test fun `throwing sender still attempts all releases and preserves original failure`() {
        val sent = mutableListOf<ShortcutKeyChord.Stroke>()
        val failure = IllegalStateException("editor closed")
        val chord = ShortcutKeyChord("Z", ctrl = true, alt = true)
        val result = runCatching {
            chord.dispatch { sent.add(it); if (it.code == KeyEvent.KEYCODE_Z) throw failure; true }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf(KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT),
            sent.takeLast(3).map { it.code })
        assertTrue(sent.takeLast(3).all { !it.down })
        assertEquals(0, sent.last().meta)
    }

    @Test fun `escape and undo redo chords cancel composition while navigation commits first`() {
        for (action in listOf("Esc", "撤销", "恢复", ShortcutKeyChord("Esc").encoded,
            ShortcutKeyChord("Z", ctrl = true).encoded, ShortcutKeyChord("Z", ctrl = true, shift = true).encoded,
            ShortcutKeyChord("Y", ctrl = true).encoded)) assertTrue(action, KeyboardShortcuts.cancelsPending(action))
        for (action in listOf("向左", "向右", "向上", "向下", "复制", "段首",
            ShortcutKeyChord("Left", alt = true).encoded, ShortcutKeyChord("Z", ctrl = true, alt = true).encoded))
            assertFalse(action, KeyboardShortcuts.cancelsPending(action))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, KeyboardShortcuts.chord("向上")!!.keyCode)
        assertEquals("Ctrl+Shift+Z", KeyboardShortcuts.chord("恢复")!!.label)
    }
}
