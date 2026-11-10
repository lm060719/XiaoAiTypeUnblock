package io.mo.xatype.update

/**
 * A small Markdown reader for release notes: headings, paragraphs, bullet and numbered lists,
 * quotes, code blocks and the inline styles bold, italic, strikethrough, code and links.
 * Plain data, so it is testable without Android; the settings app turns it into spans.
 */
object Markdown {
    data class Inline(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val url: String? = null,
    )

    sealed class Block {
        data class Heading(val level: Int, val content: List<Inline>) : Block()
        data class Paragraph(val content: List<Inline>) : Block()
        /** [marker] is "•" or a number such as "1.". */
        data class Item(val marker: String, val depth: Int, val content: List<Inline>) : Block()
        data class Quote(val content: List<Inline>) : Block()
        data class Code(val text: String) : Block()
        object Rule : Block()
    }

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    fun parse(markdown: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(inline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            index++
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flush()
                val fence = trimmed.take(3)
                val code = ArrayList<String>()
                while (index < lines.size && !lines[index].trim().startsWith(fence)) code += lines[index++]
                index++ // closing fence
                blocks += Block.Code(code.joinToString("\n"))
                continue
            }
            if (trimmed.isEmpty()) { flush(); continue }
            if (rule.matches(line)) { flush(); blocks += Block.Rule; continue }
            val headingMatch = heading.find(trimmed)
            if (headingMatch != null) {
                flush()
                blocks += Block.Heading(headingMatch.groupValues[1].length, inline(headingMatch.groupValues[2]))
                continue
            }
            val bulletMatch = bullet.find(line)
            if (bulletMatch != null) {
                flush()
                val text = bulletMatch.groupValues[2].trim()
                // GitHub's editor leaves empty "- " lines behind; they carry nothing.
                if (text.isNotEmpty()) blocks += Block.Item("•", depth(bulletMatch.groupValues[1]), inline(text))
                continue
            }
            val numberMatch = numbered.find(line)
            if (numberMatch != null) {
                flush()
                blocks += Block.Item("${numberMatch.groupValues[2]}.", depth(numberMatch.groupValues[1]),
                    inline(numberMatch.groupValues[3].trim()))
                continue
            }
            if (trimmed.startsWith(">")) {
                flush()
                val text = trimmed.trimStart('>', ' ')
                if (text.isNotEmpty()) blocks += Block.Quote(inline(text))
                continue
            }
            // Release notes keep their line breaks, as on GitHub.
            paragraph += trimmed
        }
        flush()
        return blocks
    }

    private fun depth(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(3)

    fun inline(text: String): List<Inline> {
        val result = ArrayList<Inline>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        fun add(value: String, code: Boolean = false, url: String? = null) {
            if (value.isEmpty()) return
            val next = Inline(value, bold, italic, strike, code, url)
            val last = result.lastOrNull()
            // Merge runs with identical plain styling so renderers see whole words.
            if (last != null && !code && url == null && last.copy(text = "") == next.copy(text = ""))
                result[result.lastIndex] = last.copy(text = last.text + value)
            else result += next
        }
        fun emit() { add(buffer.toString()); buffer.clear() }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_~[]()#+-.!>" -> {
                    buffer.append(text[i + 1]); i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { buffer.append(c); i++ } else {
                        emit()
                        add(text.substring(i + 1, end), code = true)
                        i = end + 1
                    }
                }
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close >= 0) text.indexOf(')', close + 2) else -1
                    if (close < 0 || end < 0) { buffer.append(c); i++ } else {
                        emit()
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, end).trim().substringBefore(' ')
                        inline(label).forEach { part -> add(part.text, url = url) }
                        i = end + 1
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val token = text.substring(i, i + 2)
                    if (bold || text.indexOf(token, i + 2) > i + 2) { emit(); bold = !bold } else buffer.append(token)
                    i += 2
                }
                text.startsWith("~~", i) -> {
                    if (strike || text.indexOf("~~", i + 2) > i + 2) { emit(); strike = !strike } else buffer.append("~~")
                    i += 2
                }
                c == '*' -> {
                    if (italic || text.indexOf('*', i + 1) > i + 1) { emit(); italic = !italic } else buffer.append(c)
                    i++
                }
                else -> { buffer.append(c); i++ }
            }
        }
        emit()
        return result
    }

    /** The notes as plain text, for previews such as a one-line summary. */
    fun plain(markdown: String): String = parse(markdown).joinToString("\n") { block ->
        when (block) {
            is Block.Heading -> block.content.joinToString("") { it.text }
            is Block.Paragraph -> block.content.joinToString("") { it.text }
            is Block.Item -> "${block.marker} " + block.content.joinToString("") { it.text }
            is Block.Quote -> block.content.joinToString("") { it.text }
            is Block.Code -> block.text
            Block.Rule -> ""
        }
    }.trim()
}
