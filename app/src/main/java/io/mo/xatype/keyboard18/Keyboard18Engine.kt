package io.mo.xatype.keyboard18

import io.mo.xatype.keyboard.ShuangpinScheme

/**
 * [suggestion] marks associative completions that add letters the user has not typed;
 * [synthetic] marks stitched sentences containing Latin pieces; [learned] marks a phrase the
 * user composed word by word three times for exactly these taps. [code] holds the full reading
 * as typed under the active scheme, one space between syllables.
 */
data class Pinyin18Candidate(val text: String, val spelling: String, val consumed: Int, val score: Double,
    val suggestion: Boolean = false, val synthetic: Boolean = false, val learned: Boolean = false,
    val code: String = "")
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
    /** Words picked one by one for the current tap sequence. */
    private val composedParts = ArrayList<String>()
    private val composedSpellings = ArrayList<String>()
    /** Taps already consumed by [composedParts]; together with [raw] they form the whole sequence. */
    private val composedRaw = StringBuilder()
    /**
     * Syllable boundaries fixed by picking a reading, indexed by [raw] position: `lock[p]` true
     * requires a syllable boundary between `raw[p - 1]` and `raw[p]`, false forbids one.
     * Positions from `lock.size` on are free. Invariant: `lock.size <= raw.length + 1`.
     */
    private var lock = BooleanArray(0)
    /** A picked reading, so backspace can restore the taps it replaced. */
    private class Pick(val raw: String, val reading: String, val before: String, val beforeReading: String,
        val beforeLock: BooleanArray)
    private val picks = ArrayList<Pick>()
    val display: String get() = pinyinDisplay()
    /** Candidate bar shows the real pinyin of the leading candidate, not group tags. */
    private fun pinyinDisplay(): String {
        val top = candidates.firstOrNull() ?: return Keyboard18Keys.readable(raw)
        val spelling = top.spelling.substringBefore(" · ")
        val rest = raw.drop(top.consumed)
        if (rest.isEmpty()) return spelling
        return spelling + rest.map { Keyboard18Keys.shown(it) }.joinToString("")
    }
    val scheme get() = dictionary.scheme
    fun snapshot(consumed: Boolean = true, commit: String? = null) =
        Pinyin18Result(consumed, raw, display, candidates, commit)
    private fun resetComposition() { composedParts.clear(); composedSpellings.clear(); composedRaw.setLength(0) }
    private fun unlock() { lock = BooleanArray(0); picks.clear() }
    fun clear() { raw = ""; candidates = emptyList(); resetComposition(); unlock() }
    fun restore(value: String): Pinyin18Result {
        resetComposition(); unlock()
        if (value.length > MAX_INPUT || value.any { !dictionary.valid(it) }) return snapshot(false)
        raw = value; refresh(); return snapshot()
    }
    fun backspace(): Pinyin18Result {
        if (raw.isEmpty()) return snapshot(false)
        val pick = picks.lastOrNull()
        if (pick != null && (raw == pick.raw || raw == pick.reading)) {
            // Backspace first takes back a picked reading, restoring the taps it replaced;
            // once the taps typed after it are deleted, only the replaced taps come back.
            picks.removeAt(picks.lastIndex)
            raw = if (raw == pick.raw) pick.before else pick.beforeReading
            lock = pick.beforeLock
            trimLock()
            refresh(); return snapshot()
        }
        // Backspace only edits the unconsumed taps; picked words are already in the editor.
        raw = raw.dropLast(1)
        trimLock()
        if (raw.isEmpty()) resetComposition()
        refresh(); return snapshot()
    }
    private fun trimLock() {
        var size = minOf(lock.size, raw.length + 1)
        // A position inside a locked syllable that became the end is released: deleting the
        // n of a picked "hen" must still offer words for "he".
        if (size == raw.length + 1 && !lock[raw.length]) size = raw.length
        if (size != lock.size) lock = lock.copyOf(size)
    }
    private fun reading(candidate: Pinyin18Candidate) = candidate.spelling.substringBefore(" · ")
    /** Distinct pinyin readings for the current input, shown in the expanded candidate page. */
    fun pinyinVariants(): List<String> = candidates
        .map(::reading)
        .filter { it.isNotBlank() }
        .distinct()
        .take(20)

    /**
     * Picks reading [index] of [pinyinVariants]: the taps it covers become its exact letters with
     * fixed syllable boundaries, so the candidates are re-decoded for that pinyin only. An
     * abbreviated or completed reading is expanded to its full spelling.
     */
    fun choosePinyin(index: Int): Pinyin18Result {
        val reading = pinyinVariants().getOrNull(index) ?: return snapshot(false)
        // Among candidates sharing the reading, the one covering the most taps is the full-spelling
        // decoding; a single-tap abbreviation with the same reading would leave the other taps.
        val chosen = candidates.filter { it.code.isNotBlank() && reading(it) == reading }
            .maxByOrNull { it.consumed } ?: return snapshot(false)
        val syllables = chosen.code.split(' ').filter { it.isNotEmpty() }
        val letters = syllables.joinToString("")
        val rest = raw.drop(chosen.consumed)
        if (letters.isEmpty() || letters.length + rest.length > MAX_INPUT ||
            letters.any { it == '\'' || !dictionary.valid(it) }) return snapshot(false)
        val rules = BooleanArray(letters.length + 1)
        var offset = 0
        syllables.forEach { offset += it.length; rules[offset] = true }
        // Boundaries an earlier pick fixed past this reading carry over, shifted to the new letters.
        val shift = letters.length - chosen.consumed
        val merged = if (lock.size <= chosen.consumed + 1) rules else BooleanArray(lock.size + shift).also { next ->
            rules.copyInto(next)
            for (position in letters.length + 1 until next.size) next[position] = lock[position - shift]
        }
        val next = letters + rest
        if (next == raw && merged.contentEquals(lock)) return snapshot()
        picks.add(Pick(next, letters, raw, raw.take(chosen.consumed), lock))
        raw = next
        lock = merged
        trimLock()
        refresh()
        return snapshot()
    }

    fun select(index: Int): String? {
        val candidate = candidates.getOrNull(index) ?: return null
        val rawBefore = raw
        raw = raw.drop(candidate.consumed).trimStart('\'')
        // Picked words leave the editor's composition; the remaining locked boundaries shift with it.
        val removed = rawBefore.length - raw.length
        lock = if (lock.size > removed) lock.copyOfRange(removed, lock.size) else BooleanArray(0)
        picks.clear()
        learn(candidate.text)
        composedParts.add(candidate.text)
        composedSpellings.add(candidate.spelling.substringBefore(" · "))
        composedRaw.append(rawBefore, 0, rawBefore.length - raw.length)
        if (raw.isEmpty()) {
            // Only a phrase built from two or more picks is a composition worth fixing;
            // three identical repeats turn it into a single candidate next time.
            if (composedParts.size >= 2) {
                recordComposition(compositionKey(scheme, composedRaw.toString()),
                    composedParts.toList(), composedSpellings.toList())
                // The composed phrase itself joins the frequency memory as well.
                learn(composedParts.joinToString(""))
            }
            resetComposition()
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
    private data class Path(val text: String, val spelling: String, val code: String, val score: Double,
        val segments: Int = 0, val minimumWeight: Int = Int.MAX_VALUE, val latin: Boolean = false)
    private fun words(start: Int) = (dictionary.words(raw, start).map { (end, entry) -> Word(end, entry, false) } +
        dictionary.abbreviations(raw, start).map { (end, entry) -> Word(end, entry, true) })
        .filter { fits(start, it.end, it.entry.boundaries, it.abbreviated) }
    private fun score(word: Word) = word.entry.score - dictionary.maxScore + learning(word.entry.text) -
        if (word.abbreviated) INITIAL_PENALTY * word.entry.syllableCount else 0.0

    /**
     * Whether a word spanning `raw[start, end)` keeps the syllable boundaries of [lock].
     * [boundaries] are the word's internal syllable boundaries as letter offsets; an abbreviated
     * word has one per tap. An [open] word (a completion) continues past [end].
     */
    private fun fits(start: Int, end: Int, boundaries: Set<Int>, abbreviated: Boolean, open: Boolean = false): Boolean {
        if (start + 1 >= lock.size) return true
        var letters = 0
        for (position in start + 1..minOf(end, lock.size - 1)) {
            if (raw[position - 1] != '\'') letters++
            val boundary = abbreviated || (position == end && !open) || letters in boundaries
            if (boundary != lock[position]) return false
        }
        return true
    }

    /** A learned phrase survives a picked reading only when it spells exactly those letters and syllables. */
    private fun fitsLearned(code: String): Boolean {
        if (lock.isEmpty()) return true
        val letters = code.replace(" ", "")
        val taps = raw.filter { it != '\'' }
        if (letters.length != taps.length || letters.indices.any { !Keyboard18Keys.matches(taps[it], letters[it]) })
            return false
        val boundaries = buildSet { var count = 0; code.forEach { if (it == ' ') add(count) else count++ } }
        return fits(0, raw.length, boundaries, abbreviated = false)
    }

    /** Learned phrases store display pinyin; re-encode it for the active double-pinyin scheme. */
    private fun learnedCode(spelling: String): String = scheme?.let { scheme ->
        spelling.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { scheme.encodeSyllable(it) ?: it }
    } ?: spelling

    private fun refresh() {
        if (raw.isEmpty()) { candidates = emptyList(); return }
        val learned = compositionCandidates(compositionKey(scheme, raw)).map {
            Pinyin18Candidate(it.text, it.spelling, raw.length, 0.0, learned = true, code = learnedCode(it.spelling))
        }.filter { fitsLearned(it.code) }
        val paths = Array(raw.length + 1) { mutableListOf<Path>() }
        paths[0].add(Path("", "", "", 0.0))
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
                        (path.code + " " + entry.inputSpelling).trim(),
                        path.score + wordScore - if (repeated) REPETITION_PENALTY else 0.0,
                        path.segments + 1, minOf(path.minimumWeight, entry.weight), path.latin || entry.literal))
                }
                prune(paths[end])
            }
        }
        // Rare corpus entries remain individually selectable, but are not used to fabricate
        // low-frequency multiword sentences such as e + e + m for grouped WE/WE/M input.
        val whole = paths[raw.length].filter { it.segments == 1 || it.minimumWeight >= MIN_COMPOSITION_WEIGHT }
            .map { Pinyin18Candidate(it.text, it.spelling, raw.length,
                it.score + if (it.segments > 1) learning(it.text) else 0.0,
                synthetic = it.segments > 1 && it.latin, code = it.code) }
        val lexical = direct.map { word -> Pinyin18Candidate(word.entry.text,
            word.entry.spelling + if (word.abbreviated) " · 简拼" else "", word.end,
            score(word) + if (word.end == raw.length) COMPLETE_WORD_BONUS else 0.0, code = word.entry.inputSpelling) }
        // English completions are noise in Chinese mode: the ambiguous 18-key groups
        // can surface e.g. "equal" from WE+Q, which is neither the typed pinyin nor useful.
        val completion = dictionary.completions(raw, 0)
            .filter { !it.literal && fits(0, raw.length, it.boundaries, abbreviated = false, open = true) }
            .map { entry ->
                Pinyin18Candidate(entry.text, entry.spelling + " · 补全", raw.length,
                    entry.score - dictionary.maxScore - 3.0 + learning(entry.text), suggestion = true,
                    code = entry.inputSpelling)
            }
        val taps = raw.filter { it != '\'' }
        val picks = HashMap<String, Int>()
        fun picked(text: String) = picks.getOrPut(text) { frequency(text).let { if (it >= LEARNED_THRESHOLD) it else 0 } }
        candidates = (learned + lexical + whole + completion)
            .filterNot { it.synthetic || latinNoise(it.text, taps) }
            .sortedWith(
                // A learned composition for exactly these taps always wins.
                compareByDescending<Pinyin18Candidate> { it.learned }
                    // Associative completions always come after candidates for the typed input.
                    .thenBy { it.suggestion }
                    // Candidates covering every tap commit the whole input in one pick.
                    .thenByDescending { it.consumed == raw.length }
                    // Within the same coverage, words picked three times or more solidify.
                    .thenByDescending { picked(it.text) }
                    .thenByDescending { it.score - PREFIX_PENALTY * (raw.length - it.consumed) }
            ).distinctBy { it.text to it.consumed }.take(MAX_CANDIDATES)
    }

    /**
     * Pure Latin candidates are kept only when they are real dictionary terms rather than an
     * echo of the tapped key letters (sd, rt, uuru, to, am...). Chinese and mixed text always stays.
     */
    private fun latinNoise(text: String, taps: String): Boolean {
        if (!text.all { it in 'a'..'z' || it in 'A'..'Z' }) return false
        val lower = text.lowercase()
        if (lower in KEEP_LATIN) return false
        if (!dictionary.isLiteral(text) || text.length == 1 || lower in Keyboard18Keys.groups) return true
        // Two-letter rows below this weight are key abbreviations or pinyin fragments.
        if (text.length == 2 && dictionary.literalWeight(text) < TWO_LETTER_WORD_MIN_WEIGHT) return true
        return echoesTaps(text, taps)
    }

    /**
     * True when [text] can be split so that each piece comes from the key group of one tap, in
     * order: the candidate only spells back the keys. Case-sensitive, so cased terms such as
     * iPhone are kept. Failed states are memoized; repeated groups cannot blow up exponentially.
     */
    private fun echoesTaps(text: String, taps: String): Boolean {
        if (taps.isEmpty()) return false
        val failed = HashSet<Int>()
        fun match(tap: Int, char: Int): Boolean {
            // Prefix candidates may consume only the leading taps.
            if (char == text.length) return tap > 0
            if (tap == taps.length) return false
            val state = tap * (text.length + 1) + char
            if (state in failed) return false
            val allowed = Keyboard18Keys.group(taps[tap].code) ?: taps[tap].toString()
            for (width in 1..allowed.length) {
                if (char + width > text.length) break
                if ((char until char + width).all { text[it] in allowed } && match(tap + 1, char + width)) return true
            }
            failed += state
            return false
        }
        return match(0, 0)
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
        private const val MAX_INPUT = 64
        private const val BEAM = 8
        private const val WORD_PENALTY = 0.7
        private const val INITIAL_PENALTY = 1.2
        private const val COMPLETE_WORD_BONUS = 4.0
        private const val PREFIX_PENALTY = 1.5
        /** Two-letter key abbreviations and pinyin fragments sit below real English words. */
        private const val TWO_LETTER_WORD_MIN_WEIGHT = 10_000
        /** Common Latin terms kept even though they spell back the tapped keys. */
        private val KEEP_LATIN = setOf("ai", "ok", "qq", "app", "api", "wx", "vx")
        private const val REPETITION_PENALTY = 6.0
        private const val MIN_COMPOSITION_WEIGHT = 32
        private const val MAX_CANDIDATES = 160
        private const val LEARNED_THRESHOLD = 3

        /**
         * Storage key of a learned composition: the scheme plus the projected taps, so the same
         * taps learned under full pinyin are not offered after switching to a shuangpin scheme.
         */
        internal fun compositionKey(scheme: ShuangpinScheme?, taps: String): String =
            (scheme?.name ?: "QUANPIN") + ":" +
                taps.map { if (it == '\'') '|' else Keyboard18Keys.projected(it) }.joinToString("")
    }
}
