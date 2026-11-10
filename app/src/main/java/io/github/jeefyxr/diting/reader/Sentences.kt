package io.github.jeefyxr.diting.reader

/** Cuts text into sentence-sized pieces for speech. */
object Sentences {
    private const val ENDERS = "。！？!?；;…\n"
    private const val CLOSERS = "”’」』）)】》\"'"
    private const val SOFT_BREAKS = "，,、：: "
    const val MAX_LENGTH = 120

    /** Index just past the sentence that begins at [start]. */
    fun end(text: CharSequence, start: Int): Int {
        val limit = minOf(text.length, start + MAX_LENGTH)
        var i = start
        while (i < limit) {
            val c = text[i++]
            // A Latin full stop only ends a sentence before whitespace, so "3.14" stays whole.
            val latinStop = c == '.' && (i == text.length || text[i].isWhitespace())
            if (c in ENDERS || latinStop) {
                // Keep trailing punctuation and closing quotes with the sentence.
                while (i < text.length && text[i] != '\n' && (text[i] in ENDERS || text[i] in CLOSERS)) i++
                return i
            }
        }
        if (limit == text.length) return limit
        // Overlong sentence: prefer to break after a comma in its second half.
        for (j in limit - 1 downTo start + MAX_LENGTH / 2) if (text[j] in SOFT_BREAKS) return j + 1
        return limit
    }

    fun isSpeakable(s: CharSequence) = s.any { it.isLetterOrDigit() }
}
