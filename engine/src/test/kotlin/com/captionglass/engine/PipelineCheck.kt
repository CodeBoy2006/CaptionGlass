package com.captionglass.engine

fun main() {
    val gate = SourceGate(maxWaitMs = 5_000)
    check(gate.update("I don't think he will", 0, 0).committed == null)
    val stable = gate.update("I don't think he will", 1, 500)
    check(stable.stable.isNotEmpty() && stable.committed == null)
    check(gate.update("I don't think he will come.", 2, 1_000).committed == null)
    val final = gate.update("I don't think he will come.", 3, 1_500, isFinal = true)
    check(final.committed == "I don't think he will come.")
    check(gate.update("I don't think he will come.", 3, 1_500, true).committed == null)

    val longSentence = SourceGate(maxWaitMs = 5_000)
    longSentence.update("We can keep going", 0, 0)
    check(longSentence.update("We can keep going", 1, 5_000).committed != null)
    check(longSentence.update("We cannot keep going", 2, 5_100).correctionRequired)
    val words = SourceGate()
    words.update("we can", 0, 0)
    check(words.update("we can't", 1, 500).stable == "we ")
    val chinese = SourceGate()
    chinese.update("这是原文", 0, 0)
    check(chinese.update("这是原文内容", 1, 500).stable == "这是原文")

    fun segment(sequence: Long, session: String = "one") =
        Segment(SegmentKey(session, sequence), "Source $sequence", sequence * 100, sequence * 100 + 50)

    val queue = TranslationQueue("one", capacity = 1, maxAgeMs = 1_000, contextCharacters = 20)
    check(queue.submit(segment(0)) == null)
    val first = checkNotNull(queue.take())
    check(queue.take() == null)
    check(queue.submit(segment(1)) == null)
    check(queue.submit(segment(2))?.untranslatedReason == UntranslatedReason.BACKLOG)
    check(queue.complete(first.segment.key.copy(sessionId = "old"), "old", 500) == null)
    check(queue.complete(first.segment.key.copy(revision = 1), "wrong", 500) == null)
    check(queue.complete(first.segment.key, "你好", 500)?.translation == "你好")
    check(queue.complete(first.segment.key, "duplicate", 500) == null)
    check(queue.take()?.context == listOf("Source 0"))
    check(queue.expire(1_150).single().untranslatedReason == UntranslatedReason.TIMED_OUT)
    check(queue.submit(segment(3)) == null)
    check(queue.stop().single().untranslatedReason == UntranslatedReason.STOPPED)
    check(queue.complete(segment(3).key, "late", 1_200) == null)
    check(queue.submit(segment(4))?.untranslatedReason == UntranslatedReason.STOPPED)

    val stress = TranslationQueue("one", capacity = 3)
    val accounted = mutableSetOf<SegmentKey>()
    repeat(1_000) { i -> stress.submit(segment(i.toLong()))?.let { accounted += it.segment.key } }
    check(stress.pendingCount == 3)
    stress.stop().forEach { accounted += it.segment.key }
    check(accounted.size == 1_000)

    val contextQueue = TranslationQueue("one", contextCharacters = 4)
    contextQueue.submit(Segment(SegmentKey("one", 0), "Long source 😀", 0, 1))
    contextQueue.submit(segment(1))
    contextQueue.take()?.let { contextQueue.complete(it.segment.key, "ok", 100) }
    check(contextQueue.take()?.context?.isEmpty() == true)

    val scheduler = CaptionScheduler(capacity = 1, minDwellMs = 1_600)
    val a = Caption(segment(0), "第一句")
    val b = Caption(segment(1), "第二句")
    check(scheduler.offer(a))
    check(scheduler.tick(0) == a)
    check(scheduler.offer(b))
    check(!scheduler.offer(b))
    check(scheduler.tick(1_599) == a)
    check(scheduler.tick(1_600) == b)
    check(scheduler.tick(3_200) == null)
    println("PASS: source/semantic gates, corrections, queue bounds, stale results, timeout, stop, completeness, reading dwell")
}
