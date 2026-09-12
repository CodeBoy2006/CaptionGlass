package com.captionglass.engine

/** Confined to the session owner. At most one native translation may be in flight. */
class TranslationQueue(
    private val sessionId: String,
    private val capacity: Int = 1,
    private val maxAgeMs: Long = 8_000,
    private val contextCharacters: Int = 1_024,
) {
    init {
        require(sessionId.isNotBlank() && capacity > 0 && maxAgeMs > 0 && contextCharacters > 0)
    }

    private val pending = ArrayDeque<TranslationRequest>()
    private var context: Segment? = null
    private var active: TranslationRequest? = null
    private var activeReported = false
    private var lastSequence = -1L
    private var stopped = false
    val pendingCount: Int get() = pending.size
    val cancelledActiveKey: SegmentKey? get() = active?.segment?.key?.takeIf { activeReported }

    /** Return the displaced waiting segment explicitly; never cancel active work for a newer arrival. */
    fun submit(segment: Segment, nowMs: Long): Caption? {
        require(segment.key.sessionId == sessionId && segment.key.sequence > lastSequence && nowMs >= 0)
        lastSequence = segment.key.sequence
        val request = TranslationRequest(segment, listOfNotNull(context?.source), nowMs)
        // Character budget is a pre-tokenization memory bound, not a claimed model token budget.
        context = segment.takeIf { it.source.length <= contextCharacters }
        if (stopped) return Caption(segment, untranslatedReason = UntranslatedReason.STOPPED)
        // Keep live input fresh instead of spending the next call's deadline on an aging backlog.
        val displaced = if (pending.size >= capacity) pending.removeFirst() else null
        pending.addLast(request)
        return displaced?.let { Caption(it.segment, untranslatedReason = UntranslatedReason.BACKLOG) }
    }

    fun take(): TranslationRequest? {
        if (stopped || active != null || pending.isEmpty()) return null
        return pending.removeFirst().also { active = it; activeReported = false }
    }

    /** A stale session, duplicate completion or wrong revision cannot replace the active result. */
    fun complete(key: SegmentKey, translation: String?, nowMs: Long): Caption? {
        val request = active?.takeIf { it.segment.key == key } ?: return null
        require(nowMs >= request.submittedAtMs)
        active = null
        if (activeReported) { activeReported = false; return null }
        return when {
            nowMs - request.submittedAtMs >= maxAgeMs ->
                Caption(request.segment, untranslatedReason = UntranslatedReason.TIMED_OUT)
            translation.isNullOrBlank() || translation.length > 8_192 ->
                Caption(request.segment, untranslatedReason = UntranslatedReason.FAILED)
            else -> Caption(request.segment, translation.trim())
        }
    }

    /** Retain the active slot until worker completion, even after expiry or cancellation. */
    fun expire(nowMs: Long): List<Caption> {
        require(nowMs >= 0)
        val expired = mutableListOf<Caption>()
        active?.takeIf { !activeReported && nowMs - it.submittedAtMs >= maxAgeMs }?.let {
            expired += Caption(it.segment, untranslatedReason = UntranslatedReason.TIMED_OUT)
            activeReported = true
        }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (nowMs - request.submittedAtMs >= maxAgeMs) {
                expired += Caption(request.segment, untranslatedReason = UntranslatedReason.TIMED_OUT)
                iterator.remove()
            }
        }
        return expired
    }

    fun invalidate(keys: Set<SegmentKey>): List<Caption> {
        if (context?.key in keys) context = null
        val outcomes = mutableListOf<Caption>()
        active?.takeIf { !activeReported && it.segment.key in keys }?.let {
            activeReported = true
            outcomes += Caption(it.segment, untranslatedReason = UntranslatedReason.SUPERSEDED)
        }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.segment.key in keys) {
                outcomes += Caption(request.segment, untranslatedReason = UntranslatedReason.SUPERSEDED)
                iterator.remove()
            }
        }
        if (keys.isNotEmpty()) {
            val rebuilt = pending.map { it.copy(context = emptyList()) }
            pending.clear(); pending.addAll(rebuilt)
        }
        return outcomes
    }

    fun stop(): List<Caption> {
        stopped = true
        val remaining = listOfNotNull(active?.takeUnless { activeReported }) + pending.toList()
        activeReported = active != null
        pending.clear()
        context = null
        return remaining.map { Caption(it.segment, untranslatedReason = UntranslatedReason.STOPPED) }
    }
}
