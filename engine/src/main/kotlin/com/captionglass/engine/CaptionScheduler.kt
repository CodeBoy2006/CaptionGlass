package com.captionglass.engine

/** Reading backlog is distinct from inference backlog. The caller retains every history record. */
class CaptionScheduler(
    private val capacity: Int = 4,
    private val minDwellMs: Long = 1_600,
    private val maxDwellMs: Long = 6_000,
    private val millisecondsPerCodePoint: Long = 100,
) {
    init {
        require(capacity > 0 && minDwellMs > 0 && maxDwellMs >= minDwellMs && millisecondsPerCodePoint > 0)
    }

    private val pending = ArrayDeque<Caption>()
    private var current: Caption? = null
    private var showUntilMs = 0L
    private var lastTickMs = 0L

    /** False means reading overflow: show an indicator and retain the caption in history. */
    fun offer(caption: Caption): Boolean {
        if (pending.size >= capacity) return false
        pending.addLast(caption)
        return true
    }

    fun tick(nowMs: Long): Caption? {
        require(nowMs >= lastTickMs)
        lastTickMs = nowMs
        if (current != null && nowMs < showUntilMs) return current
        current = pending.removeFirstOrNull()
        current?.let {
            val text = it.translation ?: it.segment.source
            val count = text.codePointCount(0, text.length).toLong()
            showUntilMs = nowMs + (count * millisecondsPerCodePoint).coerceIn(minDwellMs, maxDwellMs)
        }
        return current
    }
}
