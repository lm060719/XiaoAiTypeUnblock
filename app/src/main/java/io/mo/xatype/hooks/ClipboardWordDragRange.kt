package io.mo.xatype.hooks

internal class ClipboardWordDragRange {
    private var range = IntRange.EMPTY

    fun move(anchor: Int, target: Int, toggle: (Int) -> Unit) {
        val next = if (target == anchor) IntRange.EMPTY else minOf(anchor, target)..maxOf(anchor, target)
        if (next == range) return
        // Leaving the range reverses only the changes made by this gesture.
        for (index in range) if (index !in next) toggle(index)
        for (index in next) if (index !in range) toggle(index)
        range = next
    }

    fun finish() {
        range = IntRange.EMPTY
    }
}
