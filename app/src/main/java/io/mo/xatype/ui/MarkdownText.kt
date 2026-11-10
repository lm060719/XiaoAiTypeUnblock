package io.mo.xatype.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.widget.TextView
import io.mo.xatype.R
import io.mo.xatype.update.Markdown

/** Renders [Markdown] release notes into a TextView with the settings palette. */
internal object MarkdownText {

    fun view(kit: SettingsKit, markdown: String): TextView = kit.label(render(kit, markdown), 14f, R.color.text_primary).apply {
        setLineSpacing(kit.dp(3).toFloat(), 1f)
        movementMethod = LinkMovementMethod.getInstance()
        setLinkTextColor(kit.color(R.color.primary))
    }

    fun render(kit: SettingsKit, markdown: String): CharSequence {
        val out = SpannableStringBuilder()
        val blocks = Markdown.parse(markdown)
        if (blocks.isEmpty()) return "此版本没有填写更新日志。"
        var previous: Markdown.Block? = null
        for (block in blocks) {
            if (out.isNotEmpty()) {
                // Consecutive list items sit tight; other blocks get a blank line between them.
                val tight = block is Markdown.Block.Item && previous is Markdown.Block.Item
                out.append(if (tight) "\n" else "\n\n")
            }
            val start = out.length
            when (block) {
                is Markdown.Block.Heading -> {
                    inline(kit, out, block.content)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val size = when (block.level) { 1 -> 1.25f; 2 -> 1.15f; else -> 1.05f }
                    out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Block.Paragraph -> inline(kit, out, block.content)
                is Markdown.Block.Item -> {
                    out.append(block.marker).append(' ')
                    inline(kit, out, block.content)
                    val indent = kit.dp(12) * block.depth
                    val hanging = kit.dp(if (block.marker == "•") 12 else 18)
                    out.setSpan(LeadingMarginSpan.Standard(indent, indent + hanging), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Block.Quote -> {
                    inline(kit, out, block.content)
                    out.setSpan(ForegroundColorSpan(kit.color(R.color.text_secondary)), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(LeadingMarginSpan.Standard(kit.dp(12)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Markdown.Block.Code -> {
                    out.append(block.text)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.9f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Markdown.Block.Rule -> {
                    out.append("—————")
                    out.setSpan(ForegroundColorSpan(kit.color(R.color.card_stroke)), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            previous = block
        }
        return out
    }

    private fun inline(kit: SettingsKit, out: SpannableStringBuilder, parts: List<Markdown.Inline>) {
        for (part in parts) {
            val start = out.length
            out.append(part.text)
            val end = out.length
            fun span(what: Any) = out.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when {
                part.bold && part.italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                part.bold -> span(StyleSpan(Typeface.BOLD))
                part.italic -> span(StyleSpan(Typeface.ITALIC))
            }
            if (part.strike) span(StrikethroughSpan())
            if (part.code) {
                span(TypefaceSpan("monospace"))
                span(BackgroundColorSpan(kit.color(R.color.primary_container)))
            }
            // Only web links are clickable; anything else stays plain text.
            part.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { span(URLSpan(it)) }
        }
    }
}
