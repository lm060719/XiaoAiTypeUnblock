package io.mo.xatype.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardWordDragRangeTest {
    private val words = "abcdefghij"
    private val selection = ClipboardWordSelection(words.map(Char::toString))
    private val drag = ClipboardWordDragRange()

    @Test fun draggingIntoTheSecondRowStopsAtEveryTargetWord() {
        for (target in 5..9) {
            drag.move(anchor = 0, target = target, toggle = selection::toggle)
            assertEquals(words.substring(0, target + 1), selection.text())
        }
        for (target in 8 downTo 5) {
            drag.move(anchor = 0, target = target, toggle = selection::toggle)
            assertEquals(words.substring(0, target + 1), selection.text())
        }
    }

    @Test fun diagonalDragWithNoIntermediateEventsStopsAtTheTargetWord() {
        drag.move(anchor = 0, target = 6, toggle = selection::toggle)
        assertEquals("abcdefg", selection.text())
    }

    @Test fun reversingInTheSameRowRestoresTheStartingSelection() {
        selection.toggle(2)
        drag.move(anchor = 0, target = 4, toggle = selection::toggle)
        assertEquals("abde", selection.text())
        drag.move(anchor = 0, target = 1, toggle = selection::toggle)
        assertEquals("abc", selection.text())
        drag.move(anchor = 0, target = 0, toggle = selection::toggle)
        assertEquals("c", selection.text())
    }

    @Test fun draggingBackToTheFirstRowRemovesTheSecondRow() {
        drag.move(anchor = 0, target = 6, toggle = selection::toggle)
        drag.move(anchor = 0, target = 3, toggle = selection::toggle)
        assertEquals("abcd", selection.text())
    }

    @Test fun draggingUpAlsoStopsAtTheTargetWord() {
        drag.move(anchor = 7, target = 2, toggle = selection::toggle)
        assertEquals("cdefgh", selection.text())
    }

    @Test fun liftingTheFingerKeepsTheRangeForTheNextGesture() {
        drag.move(anchor = 7, target = 2, toggle = selection::toggle)
        drag.finish()
        drag.move(anchor = 1, target = 4, toggle = selection::toggle)
        assertEquals("bfgh", selection.text())
    }
}
