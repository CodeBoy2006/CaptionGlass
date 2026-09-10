package com.captionglass.engine

data class SegmentKey(val sessionId: String, val sequence: Long, val revision: Int = 0) {
    init {
        require(sessionId.isNotBlank())
        require(sequence >= 0 && revision >= 0)
    }
}

/** Times are monotonic milliseconds since capture began, never source-media positions. */
data class Segment(val key: SegmentKey, val source: String, val startMs: Long, val endMs: Long) {
    init {
        require(source.isNotBlank() && source.length <= 8_192)
        require(startMs >= 0 && endMs >= startMs)
    }
}

enum class UntranslatedReason { BACKLOG, FAILED, TIMED_OUT, STOPPED }

data class Caption(
    val segment: Segment,
    val translation: String? = null,
    val untranslatedReason: UntranslatedReason? = null,
) {
    init {
        require((translation != null) xor (untranslatedReason != null))
        require(translation == null || (translation.isNotBlank() && translation.length <= 8_192))
    }
}

data class TranslationRequest(val segment: Segment, val context: List<String>)
