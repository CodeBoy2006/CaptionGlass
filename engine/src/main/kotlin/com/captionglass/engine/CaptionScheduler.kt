package com.captionglass.engine

data class CaptionPage(val caption: Caption, val source: String, val translation: String?, val index: Int, val total: Int)

/** Android supplies measured two-line pages; history retains the full caption. */
class CaptionScheduler(
    private val capacity: Int = 4,
    private val minDwellMs: Long = 1_600,
    private val maxDwellMs: Long = 6_000,
    private val millisecondsPerLatinCodePoint: Long = 50,
    private val millisecondsPerCjkCodePoint: Long = 100,
) {
    init { require(capacity > 0 && minDwellMs > 0 && maxDwellMs >= minDwellMs &&
        millisecondsPerLatinCodePoint > 0 && millisecondsPerCjkCodePoint > 0) }
    private val pending = ArrayDeque<List<CaptionPage>>()
    private val pages = ArrayDeque<CaptionPage>()
    private var current: CaptionPage? = null
    private var showUntilMs = 0L
    private var lastTickMs = 0L
    private var lastSequence = -1L

    /** False means reading overflow or an out-of-order result; neither may rewind the overlay. */
    fun offer(caption: Caption, sourcePages: List<String> = listOf(caption.segment.source),
              translationPages: List<String> = listOfNotNull(caption.translation)): Boolean {
        if (pending.size >= capacity || caption.segment.key.sequence <= lastSequence) return false
        require(sourcePages.isNotEmpty())
        lastSequence = caption.segment.key.sequence
        val count = maxOf(sourcePages.size, translationPages.size)
        pending.addLast(List(count) { i -> CaptionPage(caption, sourcePages.getOrNull(i).orEmpty(),
            translationPages.getOrNull(i), i, count) })
        return true
    }

    fun invalidate(keys: Set<SegmentKey>) {
        pending.removeAll { it.first().caption.segment.key in keys }
        pages.removeAll { it.caption.segment.key in keys }
        if (current?.caption?.segment?.key in keys) current = null
    }

    /** Re-measure after font/display changes without losing pending text. */
    fun reflow(paginate: (String, Boolean) -> List<String>) {
        val captions = (listOfNotNull(current?.caption, pages.firstOrNull()?.caption) +
            pending.map { it.first().caption }).distinctBy { it.segment.key }
        current = null; pages.clear(); pending.clear()
        captions.forEach { caption ->
            val source = paginate(caption.segment.source, false)
            val translation = caption.translation?.let { paginate(it, true) }.orEmpty()
            val count = maxOf(source.size, translation.size)
            pending.addLast(List(count) { i -> CaptionPage(caption, source.getOrNull(i).orEmpty(),
                translation.getOrNull(i), i, count) })
        }
    }

    fun tick(nowMs: Long): CaptionPage? {
        require(nowMs >= lastTickMs)
        lastTickMs = nowMs
        if (current != null && nowMs < showUntilMs) return current
        if (pages.isEmpty() && pending.isNotEmpty()) pages.addAll(pending.removeFirst())
        current = pages.removeFirstOrNull()
        current?.let {
            // ponytail: pace parallel bilingual text by the slower side; calibrate with reader trials.
            val dwell = maxOf(readingTime(it.source), readingTime(it.translation.orEmpty()))
            showUntilMs = nowMs + dwell.coerceIn(minDwellMs, maxDwellMs)
        }
        return current
    }

    private fun readingTime(text: String): Long = text.codePoints().mapToLong {
        when (Character.UnicodeScript.of(it)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> millisecondsPerCjkCodePoint
            else -> millisecondsPerLatinCodePoint
        }
    }.sum()
}
