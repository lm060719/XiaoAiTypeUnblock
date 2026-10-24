package io.mo.xatype.keyboard18

data class Pinyin18Candidate(val text: String, val spelling: String, val consumed: Int, val score: Double)
data class Pinyin18Result(val consumed: Boolean, val raw: String, val display: String,
    val candidates: List<Pinyin18Candidate>, val commit: String? = null)

/** Bounded word lattice with sentence composition, prefix selection and exact-letter constraints. */
class Keyboard18Engine(private val dictionary: Keyboard18Dictionary,
    private val frequency: (String) -> Int = { 0 }, private val learn: (String) -> Unit = {}) {
    var raw: String = ""
        private set
    var candidates: List<Pinyin18Candidate> = emptyList()
        private set
    val display: String get() = pinyinDisplay()
    /** Candidate bar shows the real pinyin of the leading candidate, not group tags. */
    private fun pinyinDisplay(): String {
        val top = candidates.firstOrNull() ?: return Keyboard18Keys.readable(raw)
        val spelling = top.spelling.substringBefore(" · ")
        val rest = raw.drop(top.consumed)
        if (rest.isEmpty()) return spelling
        return spelling + rest.map { Keyboard18Keys.projected(it) }.joinToString("")
    }
    val scheme get() = dictionary.scheme
    fun snapshot(consumed: Boolean = true, commit: String? = null) =
        Pinyin18Result(consumed, raw, display, candidates, commit)
    fun clear() { raw = ""; candidates = emptyList() }
    fun restore(value: String): Pinyin18Result {
        if (value.length > MAX_INPUT || value.any { !dictionary.valid(it) }) return snapshot(false)
        raw = value; refresh(); return snapshot()
    }
    fun backspace(): Pinyin18Result {
        if (raw.isEmpty()) return snapshot(false)
        raw = raw.dropLast(1); refresh(); return snapshot()
    }
    /** Distinct pinyin readings for the current input, shown in the expanded candidate page. */
    fun pinyinVariants(): List<String> = candidates
        .map { it.spelling.substringBefore(" · ") }
        .filter { it.isNotBlank() }
        .distinct()
        .take(20)

    fun select(index: Int): String? {
        val candidate = candidates.getOrNull(index) ?: return null
        raw = raw.drop(candidate.consumed).trimStart('\'')
        learn(candidate.text)
        refresh()
        return candidate.text
    }
    fun commitFirst(): String? {
        if (raw.isEmpty()) return null
        // Space commits the highest ranked candidate; a prefix selection keeps the suffix composing.
        if (candidates.isNotEmpty()) return select(0)
        val literal = Keyboard18Keys.readable(raw)
        clear()
        return literal
    }
    fun commitAll(): String? {
        if (raw.isEmpty()) return null
        val result = StringBuilder()
        while (raw.isNotEmpty()) result.append(commitFirst().orEmpty())
        return result.toString()
    }
    fun input(token: Char): Pinyin18Result {
        if (token == '\b') return backspace()
        if (token == ' ') return snapshot(raw.isNotEmpty(), commitFirst())
        if (token == '\n') return snapshot(raw.isNotEmpty(), commitAll())
        if (dictionary.valid(token)) {
            if (token == '\'' && (raw.isEmpty() || raw.last() == '\'')) return snapshot()
            var commit: String? = null
            if (raw.length == MAX_INPUT) commit = commitAll()
            raw += token; refresh(); return snapshot(commit = commit)
        }
        if (raw.isEmpty()) return snapshot(false)
        val text = commitAll().orEmpty() + token
        return snapshot(commit = text)
    }
    fun inputText(text: String): Pinyin18Result {
        val commits = StringBuilder()
        text.forEach { token ->
            val result = input(token)
            result.commit?.let(commits::append)
            if (!result.consumed) commits.append(token)
        }
        return snapshot(text.isNotEmpty(), commits.toString().ifEmpty { null })
    }

    private data class Word(val end: Int, val entry: Pinyin18Entry, val abbreviated: Boolean)
    private data class Path(val text: String, val spelling: String, val score: Double,
        val segments: Int = 0, val minimumWeight: Int = Int.MAX_VALUE)
    private fun words(start: Int) = dictionary.words(raw, start).map { (end, entry) -> Word(end, entry, false) } +
        dictionary.abbreviations(raw, start).map { (end, entry) -> Word(end, entry, true) }
    private fun score(word: Word) = word.entry.score - dictionary.maxScore + learning(word.entry.text) -
        if (word.abbreviated) INITIAL_PENALTY * word.entry.syllableCount else 0.0
    private fun refresh() {
        if (raw.isEmpty()) { candidates = emptyList(); return }
        val paths = Array(raw.length + 1) { mutableListOf<Path>() }
        paths[0].add(Path("", "", 0.0))
        val direct = words(0)
        for (start in raw.indices) {
            if (paths[start].isEmpty()) continue
            if (raw[start] == '\'') {
                paths[start + 1].addAll(paths[start]); prune(paths[start + 1]); continue
            }
            val matches = if (start == 0) direct else words(start)
            for (word in matches) {
                val end = word.end
                val entry = word.entry
                val wordScore = score(word) - WORD_PENALTY
                paths[start].forEach { path ->
                    val repeated = path.segments > 0 && entry.text.length == 1 && path.text.endsWith(entry.text)
                    paths[end].add(Path(path.text + entry.text,
                        (path.spelling + " " + entry.spelling).trim(),
                        path.score + wordScore - if (repeated) REPETITION_PENALTY else 0.0,
                        path.segments + 1, minOf(path.minimumWeight, entry.weight)))
                }
                prune(paths[end])
            }
        }
        // Rare corpus entries remain individually selectable, but are not used to fabricate
        // low-frequency multiword sentences such as e + e + m for grouped WE/WE/M input.
        val whole = paths[raw.length].filter { it.segments == 1 || it.minimumWeight >= MIN_COMPOSITION_WEIGHT }
            .map { Pinyin18Candidate(it.text, it.spelling, raw.length,
                it.score + if (it.segments > 1) learning(it.text) else 0.0) }
        val lexical = direct.map { word -> Pinyin18Candidate(word.entry.text,
            word.entry.spelling + if (word.abbreviated) " · 简拼" else "", word.end,
            score(word) + if (word.end == raw.length) COMPLETE_WORD_BONUS else 0.0) }
        val completion = dictionary.completions(raw, 0).map { entry ->
            Pinyin18Candidate(entry.text, entry.spelling + " · 补全", raw.length,
                entry.score - dictionary.maxScore - 3.0 + learning(entry.text)) }
        // Rank corpus words, valid sentences and partial selections together. A fabricated
        // complete path no longer beats every real word merely because it consumes all taps.
        candidates = (lexical + whole + completion).sortedWith(
            compareByDescending<Pinyin18Candidate> { frequency(it.text) >= LEARNED_THRESHOLD }
                .thenByDescending { it.score - PREFIX_PENALTY * (raw.length - it.consumed) }
        ).distinctBy { it.text to it.consumed }.take(MAX_CANDIDATES)
    }
    private fun learning(text: String): Double {
        val count = frequency(text)
        return kotlin.math.ln(1.0 + count.coerceIn(0, 10_000)) * 0.6 +
            if (count >= LEARNED_THRESHOLD) LEARNED_BOOST else 0.0
    }
    private fun prune(paths: MutableList<Path>) {
        val top = paths.sortedByDescending { it.score }.distinctBy { it.text }.take(BEAM)
        paths.clear(); paths.addAll(top)
    }
    companion object {
        private const val MAX_INPUT = 64
        private const val BEAM = 8
        private const val WORD_PENALTY = 0.7
        private const val INITIAL_PENALTY = 1.2
        private const val COMPLETE_WORD_BONUS = 4.0
        private const val PREFIX_PENALTY = 1.5
        private const val REPETITION_PENALTY = 6.0
        private const val MIN_COMPOSITION_WEIGHT = 32
        private const val MAX_CANDIDATES = 160
        private const val LEARNED_THRESHOLD = 3
        private const val LEARNED_BOOST = 4.0
    }
}
