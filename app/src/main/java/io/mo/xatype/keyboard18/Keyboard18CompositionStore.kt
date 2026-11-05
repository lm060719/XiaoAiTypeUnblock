package io.mo.xatype.keyboard18

import android.content.SharedPreferences

/**
 * Remembers phrases picked word by word for the same tap sequence. After the user builds the
 * same phrase three times it becomes a fixed single candidate for those taps.
 *
 * Lookups run on every keystroke, so the preferences are read once into memory; writes happen
 * only when a composition is recorded, and pending (not yet learned) counts are capped.
 */
class Keyboard18CompositionStore(private val preferences: SharedPreferences) {
    data class Learned(val text: String, val spelling: String)

    private var learned: HashMap<String, MutableList<Learned>>? = null
    /** "key␟text" -> repeats so far, for compositions not learned yet. */
    private var pending: HashMap<String, Int>? = null

    @Synchronized
    fun candidates(key: String): List<Learned> = learned().get(key)?.toList().orEmpty()

    @Synchronized
    fun record(key: String, parts: List<String>, spellings: List<String>) {
        if (parts.size < 2) return
        val learned = learned()
        val pending = pending!!
        val text = parts.joinToString("")
        if (learned[key]?.any { it.text == text } == true) return
        val id = key + SEPARATOR + text
        val count = (pending[id] ?: 0) + 1
        val editor = preferences.edit()
        if (count >= REQUIRED_REPEATS) {
            val spelling = spellings.joinToString(" ")
            pending.remove(id)
            editor.remove(COUNT + SEPARATOR + id).putString(LEARNED + SEPARATOR + id, spelling)
            learned.getOrPut(key) { ArrayList() }.apply { add(Learned(text, spelling)); sortBy { it.text } }
        } else {
            pending[id] = count
            editor.putInt(COUNT + SEPARATOR + id, count)
            if (pending.size > MAX_PENDING) {
                // Forget the least repeated one-off compositions first, never the one just typed.
                pending.entries.filter { it.key != id }.sortedBy { it.value }.take(pending.size - MAX_PENDING * 3 / 4)
                    .map { it.key }.forEach { stale -> pending.remove(stale); editor.remove(COUNT + SEPARATOR + stale) }
            }
        }
        editor.apply()
    }

    private fun learned(): HashMap<String, MutableList<Learned>> {
        learned?.let { return it }
        val learned = HashMap<String, MutableList<Learned>>()
        val pending = HashMap<String, Int>()
        for ((name, value) in preferences.all) {
            val kind = name.substringBefore(SEPARATOR)
            val id = name.substringAfter(SEPARATOR, "")
            val key = id.substringBefore(SEPARATOR)
            val text = id.substringAfter(SEPARATOR, "")
            if (key.isEmpty() || text.isEmpty()) continue
            when {
                kind == LEARNED && value is String -> learned.getOrPut(key) { ArrayList() }.add(Learned(text, value))
                kind == COUNT && value is Int -> pending[id] = value
            }
        }
        learned.values.forEach { list -> list.sortBy { it.text } }
        this.pending = pending
        this.learned = learned
        return learned
    }

    private companion object {
        /** U+241F is valid in the preferences XML, unlike raw control characters. */
        const val SEPARATOR = "␟"
        const val COUNT = "count"
        const val LEARNED = "learned"
        const val REQUIRED_REPEATS = 3
        const val MAX_PENDING = 512
    }
}
