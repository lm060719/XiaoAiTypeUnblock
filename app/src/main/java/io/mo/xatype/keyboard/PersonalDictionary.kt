package io.mo.xatype.keyboard

import org.json.JSONArray
import java.util.Locale

/** Immutable, ordered shortcuts. Lookup never performs I/O or scans the full dictionary. */
class PersonalDictionary private constructor(val entries: List<Entry>) {
    data class Entry(val shortcut: String, val phrase: String)

    private val words = entries.groupBy { it.shortcut }.mapValues { (_, rows) -> rows.map { it.phrase } }

    fun lookup(raw: String): List<String> {
        // The native decoder returns syllable boundaries (xm becomes x'm). Only remove
        // its ASCII boundaries; selected Chinese prefixes, digits and suffixes must remain unmatched.
        if (raw.length !in 1..MAX_SHORTCUT * 3 || raw.any {
                it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' && it != ' '
            }) return emptyList()
        val code = raw.filterNot { it == '\'' || it == ' ' }.lowercase(Locale.ROOT)
        if (code.length !in 1..MAX_SHORTCUT) return emptyList()
        return words[code].orEmpty()
    }

    fun encode(): String = JSONArray().apply {
        entries.forEach { put(JSONArray().put(it.shortcut).put(it.phrase)) }
    }.toString()

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_SHORTCUT = 32
        const val MAX_PHRASE = 200
        // Keep the entire settings Binder response well below its transaction limit.
        private const val MAX_ENCODED = 150_000
        val EMPTY = PersonalDictionary(emptyList())

        fun entry(shortcut: String, phrase: String): Entry {
            val code = shortcut.trim().lowercase(Locale.ROOT)
            val text = phrase.trim()
            require(code.length in 1..MAX_SHORTCUT && code.all { it in 'a'..'z' }) {
                "快捷码须为 1–$MAX_SHORTCUT 个英文字母"
            }
            require(text.isNotEmpty() && text.length <= MAX_PHRASE && '\u0000' !in text) {
                "短语须为 1–$MAX_PHRASE 个字符"
            }
            return Entry(code, text)
        }

        fun of(entries: List<Entry>): PersonalDictionary {
            require(entries.size <= MAX_ENTRIES) { "最多保存 $MAX_ENTRIES 条词条" }
            val normalized = entries.map { entry(it.shortcut, it.phrase) }
            require(normalized.distinct().size == normalized.size) { "词条已存在" }
            return PersonalDictionary(normalized).also {
                require(it.encode().length <= MAX_ENCODED) { "词条内容过多" }
            }
        }

        fun decode(stored: String?): PersonalDictionary {
            if (stored.isNullOrBlank() || stored.length > MAX_ENCODED) return EMPTY
            return runCatching {
                val rows = JSONArray(stored)
                require(rows.length() <= MAX_ENTRIES)
                of((0 until rows.length()).map { index ->
                    val row = rows.getJSONArray(index)
                    require(row.length() == 2 && row.get(0) is String && row.get(1) is String)
                    entry(row.getString(0), row.getString(1))
                })
            }.getOrDefault(EMPTY)
        }
    }
}
