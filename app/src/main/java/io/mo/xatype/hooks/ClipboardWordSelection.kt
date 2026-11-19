package io.mo.xatype.hooks

internal class ClipboardWordSelection(private val words: List<String>) {
    private val indices = sortedSetOf<Int>()

    val hasSelection: Boolean get() = indices.isNotEmpty()

    operator fun contains(index: Int): Boolean = index in indices

    fun toggle(index: Int): Boolean {
        if (indices.remove(index)) return false
        indices.add(index)
        return true
    }

    fun text(): String = indices.joinToString(separator = "") { words[it] }
}
