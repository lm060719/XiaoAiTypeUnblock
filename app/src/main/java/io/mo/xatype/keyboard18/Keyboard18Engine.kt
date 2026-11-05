package io.mo.xatype.keyboard18


data class Pinyin18Candidate(val text: String, val spelling: String, val consumed: Int, val score: Double,
    val complete: Boolean = false, val suggestion: Boolean = false, val synthetic: Boolean = false)
data class Pinyin18Result(val consumed: Boolean, val raw: String, val display: String,
    val candidates: List<Pinyin18Candidate>, val commit: String? = null)

/** Bounded word lattice with sentence composition, prefix selection and exact-letter constraints. */
class Keyboard18Engine(private val dictionary: Keyboard18Dictionary,
    private val frequency: (String) -> Int = { 0 }, private val learn: (String) -> Unit = {},
    private val compositionCandidates: (String) -> List<Keyboard18CompositionStore.Learned> = { emptyList() },
    private val recordComposition: (String, List<String>, List<String>) -> Unit = { _, _, _ -> }) {
    var raw: String = ""
        private set
    var candidates: List<Pinyin18Candidate> = emptyList()
        private set
    private val composedParts = ArrayList<String>()
    private val composedSpellings = ArrayList<String>()
    private var compositionStartRaw = ""
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
    fun clear() {
        raw = ""; candidates = emptyList()
        composedParts.clear(); composedSpellings.clear(); compositionStartRaw = ""
    }
    fun restore(value: String): Pinyin18Result {
        composedParts.clear(); composedSpellings.clear(); compositionStartRaw = ""
        if (value.length > MAX_INPUT || value.any { !dictionary.valid(it) }) return snapshot(false)
        raw = value; refresh(); return snapshot()
    }
    fun backspace(): Pinyin18Result {
        if (raw.isEmpty()) return snapshot(false)
        raw = raw.dropLast(1)
        if (raw.isEmpty()) { composedParts.clear(); composedSpellings.clear(); compositionStartRaw = "" }
        refresh(); return snapshot()
    }
    /** Distinct pinyin readings for the current input, shown in the expanded candidate page. */
    fun pinyinVariants(): List<String> = candidates
        .map { it.spelling.substringBefore(" · ") }
        .filter { it.isNotBlank() }
        .distinct()
        .take(20)

    fun select(index: Int): String? {
        val candidate = candidates.getOrNull(index) ?: return null
        if (composedParts.isEmpty()) compositionStartRaw = raw
        val rawBefore = raw
        raw = raw.drop(candidate.consumed).trimStart('\'')
        learn(candidate.text)
        composedParts.add(candidate.text)
        composedSpellings.add(candidate.spelling.substringBefore(" · "))
        if (raw.isEmpty()) {
            // Only a phrase built from two or more taps is a composition worth fixing;
            // three identical repeats turn it into a single candidate next time.
            if (composedParts.size >= 2) {
                recordComposition(rawKey(compositionStartRaw.ifEmpty { rawBefore }), composedParts, composedSpellings)
                // The composed phrase itself joins the frequency memory, so after three
                // repetitions it is also solidified as a first-class candidate.
                learn(composedParts.joinToString(""))
            }
            composedParts.clear(); composedSpellings.clear(); compositionStartRaw = ""
        }
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
            raw += token
            if (composedParts.isNotEmpty()) compositionStartRaw += token
            refresh(); return snapshot(commit = commit)
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
        val segments: Int = 0, val minimumWeight: Int = Int.MAX_VALUE,
        val literal: Boolean = true, val latin: Boolean = false)
    private fun words(start: Int) = dictionary.words(raw, start).map { (end, entry) -> Word(end, entry, false) } +
        dictionary.abbreviations(raw, start).map { (end, entry) -> Word(end, entry, true) }
    private fun score(word: Word) = word.entry.score - dictionary.maxScore + learning(word.entry.text) -
        if (word.abbreviated) INITIAL_PENALTY * word.entry.syllableCount else 0.0
    private fun rawKey(value: String): String = value.map { if (it == '\'') '|' else Keyboard18Keys.projected(it) }.joinToString("")
    private fun refresh() {
        if (raw.isEmpty()) { candidates = emptyList(); return }
        val rawKey = rawKey(raw)
        val learned = compositionCandidates(rawKey).map {
            Pinyin18Candidate(it.text, it.spelling, raw.length, LEARNED_COMPOSITION_SCORE, complete = true)
        }
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
                        path.segments + 1, minOf(path.minimumWeight, entry.weight),
                        path.literal && entry.literal, path.latin || entry.literal))
                }
                prune(paths[end])
            }
        }
        // Rare corpus entries remain individually selectable, but are not used to fabricate
        // low-frequency multiword sentences such as e + e + m for grouped WE/WE/M input.
        val whole = paths[raw.length].filter { it.segments == 1 || it.minimumWeight >= MIN_COMPOSITION_WEIGHT }
            .map { Pinyin18Candidate(it.text, it.spelling, raw.length,
                it.score + if (it.segments > 1) learning(it.text) else 0.0, complete = true,
                synthetic = it.segments > 1 && it.latin) }
        val lexical = direct.map { word -> Pinyin18Candidate(word.entry.text,
            word.entry.spelling + if (word.abbreviated) " · 简拼" else "", word.end,
            score(word) + if (word.end == raw.length) COMPLETE_WORD_BONUS else 0.0,
            complete = word.end == raw.length) }
        // English completions are noise in Chinese mode: the ambiguous 18-key groups
        // can surface e.g. "equal" from WE+Q, which is neither the typed pinyin nor useful.
        val completion = dictionary.completions(raw, 0).filterNot { it.literal }.map { entry ->
            Pinyin18Candidate(entry.text, entry.spelling + " · 补全", raw.length,
                entry.score - dictionary.maxScore - 3.0 + learning(entry.text), suggestion = true) }
        // A candidate whose full spelling equals the typed sequence is a better fit than
        // alternative decodings from the same 18-key groups. Do not pin the raw letters
        // themselves: uuru should surface words, not a Latin "uuru" candidate.
        val typed = raw.filter { it != '\'' }
            .map { token -> Keyboard18Keys.group(token.code)?.first() ?: token }
            .joinToString("")
        fun spelledAsTyped(candidate: Pinyin18Candidate) = typed.length > 1 &&
            candidate.spelling.substringBefore(" · ").replace(" ", "").replace("'", "") == typed
        // A candidate made only of the letters the user just tapped is the pinyin echo,
        // not a word. This covers single+single, single+double, double+single and
        // double+double key combinations. A few genuine terms are exempted.
        fun keyLetterEcho(candidate: Pinyin18Candidate): Boolean {
            if (candidate.text.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return false
            val lower = candidate.text.lowercase()
            if (lower in KEEP_LATIN) return false
            if (lower in Keyboard18Keys.groups) return true
            val tokens = raw.filter { it != '\'' }
            if (tokens.isEmpty()) return false
            // Each tap contributes one to three letters from its key group. A candidate
            // that can be partitioned this way is the pinyin echo of the keys, not a word.
            fun match(tokenIndex: Int, charIndex: Int): Boolean {
                // Prefix candidates are allowed to consume only the leading taps.
                if (charIndex == candidate.text.length) return tokenIndex > 0
                if (tokenIndex == tokens.length) return false
                val token = tokens[tokenIndex]
                val allowed = Keyboard18Keys.group(token.code) ?: token.toString()
                for (width in 1..allowed.length) {
                    if (charIndex + width > candidate.text.length) break
                    if (candidate.text.substring(charIndex, charIndex + width).all { it in allowed } &&
                        match(tokenIndex + 1, charIndex + width)) return true
                }
                return false
            }
            return match(0, 0)
        }
        // Rank corpus words, valid sentences and partial selections together. A fabricated
        // complete path no longer beats every real word merely because it consumes all taps.
        candidates = (learned + lexical + whole + completion)
            .filterNot { it.synthetic }
            // Drop Latin letter strings that only echo the typed pinyin: they are neither
            // English nor Chinese words. Real English entries such as "ok" stay.
            .filterNot { candidate ->
                candidate.text.all { it in 'a'..'z' || it in 'A'..'Z' } &&
                    !dictionary.hasText(candidate.text)
            }
            // A single Latin letter is not a word either.
            .filterNot { candidate ->
                candidate.text.length == 1 && candidate.text[0] in 'a'..'z' &&
                    candidate.text !in setOf("a", "i")
            }
            // English-table rows that are exactly a Chinese pinyin code or a bare
            // pinyin final (ai, ui, ...) are the pinyin itself, not a candidate word.
            .filterNot { candidate ->
                candidate.text.length == 2 && candidate.text.all { it in 'a'..'z' || it in 'A'..'Z' } &&
                    candidate.text.lowercase() !in KEEP_LATIN &&
                    dictionary.literalWeight(candidate.text) < TWO_LETTER_WORD_MIN_WEIGHT
            }
            .filterNot(::keyLetterEcho)
            .sortedWith(
            // Real candidates covering the entire input come first so one tap commits the
            // whole sentence instead of leaving the user to pick word by word. Prefix
            // completions are not "complete": they add characters the user did not type.
            // A learned composition for these exact taps always wins.
            // Typed-letter fallback and learned compositions lead.
            compareByDescending<Pinyin18Candidate> { it.score >= LEARNED_COMPOSITION_SCORE }
                // Associative completions always come after candidates for the typed input.
                .thenBy { it.suggestion }
                // Words covering the whole typed sequence beat shorter prefix words,
                // even when a single character was picked repeatedly before.
                .thenByDescending { it.consumed == raw.length }
                .thenByDescending { spelledAsTyped(it) }
                .thenByDescending { it.complete }
                // Within the same coverage, repeated picks solidify.
                .thenByDescending { frequency(it.text) >= LEARNED_THRESHOLD }
                .thenByDescending { c -> frequency(c.text).let { if (it >= LEARNED_THRESHOLD) it else 0 } }
                .thenByDescending { it.score - PREFIX_PENALTY * (raw.length - it.consumed) }
        ).distinctBy { it.text to it.consumed }.take(MAX_CANDIDATES)
    }
    private fun learning(text: String): Double {
        val count = frequency(text)
        return kotlin.math.ln(1.0 + count.coerceIn(0, 10_000)) * 0.6
    }
    private fun prune(paths: MutableList<Path>) {
        val top = paths.sortedByDescending { it.score }.distinctBy { it.text }.take(BEAM)
        paths.clear(); paths.addAll(top)
    }
    companion object {
        private const val LEARNED_COMPOSITION_SCORE = 1_000_000.0
const val MAX_INPUT = 64
        private const val BEAM = 8
        private const val WORD_PENALTY = 0.7
        private const val INITIAL_PENALTY = 1.2
        private const val COMPLETE_WORD_BONUS = 4.0
        private const val PREFIX_PENALTY = 1.5
        /** Two-letter key abbreviations and pinyin fragments sit below real English words. */
        private const val TWO_LETTER_WORD_MIN_WEIGHT = 10_000
        private val KEEP_LATIN = setOf("ai", "ok", "qq", "app", "api", "wx", "vx")
        private const val REPETITION_PENALTY = 6.0
        private const val MIN_COMPOSITION_WEIGHT = 32
        private const val MAX_CANDIDATES = 160
        private const val LEARNED_THRESHOLD = 3
    }
}
