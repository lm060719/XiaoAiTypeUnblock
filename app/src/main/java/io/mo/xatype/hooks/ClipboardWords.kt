package io.mo.xatype.hooks

import android.icu.text.BreakIterator
import java.util.Locale

internal fun clipboardWords(text: String): List<String> {
    val boundaries = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
    return buildList {
        for ((index, section) in text.splitToSequence('.').withIndex()) {
            if (index > 0) add(".")
            boundaries.setText(section)
            var start = boundaries.first()
            var end = boundaries.next()
            while (end != BreakIterator.DONE) {
                val word = section.substring(start, end)
                if (word.any {
                        !it.isWhitespace() && !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()
                    }
                ) {
                    add(word)
                }
                start = end
                end = boundaries.next()
            }
        }
    }
}
