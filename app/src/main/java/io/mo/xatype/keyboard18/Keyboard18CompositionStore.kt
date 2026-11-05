package io.mo.xatype.keyboard18

import android.content.SharedPreferences

/**
 * Remembers word-by-word compositions of the same tap sequence. After the user builds the
 * same phrase by tapping candidates three times, it becomes a fixed single candidate.
 */
class Keyboard18CompositionStore(private val preferences: SharedPreferences) {
    data class Learned(val text: String, val spelling: String)

    fun candidates(rawKey: String): List<Learned> {
        val prefix = "$LEARNED\u241F$rawKey\u241F"
        return preferences.all.entries
            .filter { it.key.startsWith(prefix) && it.value is String && it.key.length > prefix.length }
            .map { Learned(it.key.removePrefix(prefix), it.value as String) }
            .sortedBy { it.text }
    }

    fun record(rawKey: String, parts: List<String>, spellings: List<String>) {
        if (parts.size < 2) return
        val text = parts.joinToString("")
        val countKey = "$COUNT\u241F$rawKey\u241F$text"
        val count = (preferences.getInt(countKey, 0) + 1).coerceAtMost(MAX_COUNT)
        val editor = preferences.edit().putInt(countKey, count)
        if (count >= REQUIRED_REPEATS) {
            editor.putString("$LEARNED\u241F$rawKey\u241F$text", spellings.joinToString(" "))
        }
        editor.apply()
    }

    private companion object {
        const val COUNT = "count"
        const val LEARNED = "learned"
        const val REQUIRED_REPEATS = 3
        const val MAX_COUNT = 1000
    }
}
