package com.captionglass.engine

/** A replaceable text window; offset counts UTF-16 characters within the acoustic utterance. */
data class TextWindow(val text: String, val offset: Long)

/** Keeps one decode window. Retired text is owned by the downstream semantic gate. */
class WindowTranscript {
    private var previous = ""
    private var audioStart = -1L
    private var audioEnd = 0L
    private var offset = 0L
    private var tokenStarts = emptyList<Pair<Int, Long>>()

    fun update(text: String, start: Long, end: Long, times: List<Pair<Int, Long>> = emptyList()): TextWindow {
        require(start >= audioStart && end > start && end >= audioEnd)
        if (start == audioStart || audioStart < 0) {
            previous = text; audioStart = start; audioEnd = end; tokenStarts = times
            return TextWindow(text, offset)
        }
        val overlap = (audioEnd - start).coerceAtLeast(0).toDouble() / (audioEnd - audioStart)
        val prefix = previous.take(overlapStart(previous, text, overlap, start))
        val separator = if (prefix.lastOrNull()?.isLetterOrDigit() == true && text.firstOrNull()?.isLetterOrDigit() == true &&
            prefix.last().code < 128 && text.first().code < 128) " " else ""
        val sealed = prefix + separator
        val result = TextWindow(sealed + text, offset)
        offset += sealed.length
        previous = text; audioStart = start; audioEnd = end; tokenStarts = times
        return result
    }

    fun finish(): TextWindow = TextWindow(previous, offset).also {
        previous = ""; audioStart = -1; audioEnd = 0; offset = 0; tokenStarts = emptyList()
    }

    private data class Unit(val key: String, val start: Int)
    private fun units(text: String) = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]|[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")
        .findAll(text).map { Unit(it.value.lowercase(), it.range.first) }.toList()

    private fun overlapStart(left: String, right: String, overlap: Double, start: Long): Int {
        if (left.isEmpty()) return 0
        if (overlap == 0.0) return left.length
        val all = units(left)
        val a = all.takeLast(96)
        val b = units(right).take(96)
        fun timedCut() = tokenStarts.firstOrNull { it.second >= start }?.first ?: left.length
        if (a.isEmpty() || b.isEmpty()) return if (tokenStarts.isEmpty()) left.length else timedCut()
        // Semi-global alignment: skip old leading text, but match a suffix against the new prefix.
        // ponytail: bounded lexical alignment; timestamped forced alignment would resolve ambiguous repeats.
        val scores = Array(a.size + 1) { IntArray(b.size + 1) }
        val origins = Array(a.size + 1) { IntArray(b.size + 1) }
        val matches = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) origins[i][0] = i
        for (j in 1..b.size) scores[0][j] = -10_000
        for (i in 1..a.size) for (j in 1..b.size) {
            val equal = a[i - 1].key == b[j - 1].key
            // Anchor the new prefix before permitting revisions near the old right edge.
            val diagonal = scores[i - 1][j - 1] + if (equal) 4 else if (j <= 3) -10_000 else -6
            val deletion = if (j <= 3) -10_000 else scores[i - 1][j] - 6
            val insertion = if (j <= 3) -10_000 else scores[i][j - 1] - 6
            when {
                diagonal >= deletion && diagonal >= insertion -> {
                    scores[i][j] = diagonal; origins[i][j] = origins[i - 1][j - 1]
                    matches[i][j] = matches[i - 1][j - 1] + if (equal) 1 else 0
                }
                deletion >= insertion -> {
                    scores[i][j] = deletion; origins[i][j] = origins[i - 1][j]; matches[i][j] = matches[i - 1][j]
                }
                else -> {
                    scores[i][j] = insertion; origins[i][j] = origins[i][j - 1]; matches[i][j] = matches[i][j - 1]
                }
            }
        }
        fun timeAt(index: Int) = tokenStarts.lastOrNull { it.first <= a[index].start }?.second
        val candidates = (1..b.size).filter {
            val origin = origins[a.size][it]
            matches[a.size][it] >= 3 && scores[a.size][it] >= 6 && origin < a.size &&
                (timeAt(origin)?.let { time -> kotlin.math.abs(time - start) <= 16_000 } ?: true)
        }
        if (candidates.isEmpty()) return if (tokenStarts.isEmpty()) left.length else timedCut()
        fun confidence(j: Int) = scores[a.size][j].toDouble() / (4 * maxOf(a.size - origins[a.size][j], j))
        val best = candidates.maxOf(::confidence)
        // Duration is only a tie-breaker for repeated lexical anchors, not a fabricated token timestamp.
        val expected = all.size * overlap
        val end = candidates.filter { confidence(it) >= best - 0.05 }
            .minBy { j -> timeAt(origins[a.size][j])?.let { kotlin.math.abs(it - start).toDouble() }
                ?: kotlin.math.abs(a.size - origins[a.size][j] - expected) }
        val begin = origins[a.size][end]
        val count = matches[a.size][end]
        // A coincidental short word is not evidence that audio was repeated.
        return if (count >= 3 && scores[a.size][end] >= 6 && begin < a.size) a[begin].start else left.length
    }
}
