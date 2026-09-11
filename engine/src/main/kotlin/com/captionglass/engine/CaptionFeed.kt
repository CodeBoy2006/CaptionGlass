package com.captionglass.engine

data class CaptionLine(val segment: Segment, val translation: String = "", val outcome: Caption? = null)

/** A stable ordered transcript. Time never removes text; only completion or correction changes a row. */
class CaptionFeed(private val capacity: Int = 200) {
    init { require(capacity > 0) }
    private val content = linkedMapOf<SegmentKey, CaptionLine>()
    val lines: List<CaptionLine> get() = content.values.toList()

    fun submit(segment: Segment) {
        check(segment.key !in content)
        content[segment.key] = CaptionLine(segment)
        while (content.size > capacity) content.remove(content.keys.first())
    }

    fun progress(key: SegmentKey, translation: String): Boolean {
        val line = content[key] ?: return false
        if (line.outcome != null || translation == line.translation || !translation.startsWith(line.translation)) return false
        content[key] = line.copy(translation = translation)
        return true
    }

    fun complete(caption: Caption) {
        val line = content[caption.segment.key] ?: return
        if (line.outcome != null) return
        // An interrupted preview remains readable, but is never promoted to a completed translation.
        content[caption.segment.key] = line.copy(translation = caption.translation ?: line.translation, outcome = caption)
    }

    fun invalidate(keys: Set<SegmentKey>) { keys.forEach(content::remove) }
}
