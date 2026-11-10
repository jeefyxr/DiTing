package io.github.jeefyxr.diting.reader

/** Cuts text into sentence-sized pieces for speech. */
object Sentences {
    private const val ENDERS = "。！？!?…\n"
    private const val CLOSERS = "”’」』）)】》\"'"
    // A semicolon joins clauses of one sentence: splitting there breaks the intonation, so it is
    // only used, like a comma, to cut a sentence that is too long to synthesize in one go.
    private const val SOFT_BREAKS = "；;，,、：: "
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

    /** Start of the speakable sentence before the one that contains [position]. */
    fun previousStart(text: CharSequence, position: Int): Int {
        var previous = 0
        var current = 0
        var start = 0
        while (start < text.length && start <= position) {
            val e = end(text, start)
            if (isSpeakable(text.subSequence(start, e))) {
                previous = current
                current = start
            }
            start = e
        }
        return previous
    }

    /**
     * The offset to resume listening at: the [saved] chapter and offset, but only while that spot
     * is on the page now shown ([pageStart] until [pageEnd] of [chapter]); otherwise null.
     */
    fun resumeOffset(saved: Pair<Int, Int>?, chapter: Int, pageStart: Int, pageEnd: Int): Int? =
        saved?.takeIf { it.first == chapter && it.second >= pageStart && it.second < pageEnd }?.second

    fun isSpeakable(s: CharSequence) = s.any { it.isLetterOrDigit() }
}
