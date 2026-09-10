package com.captionglass.app

import android.os.SystemClock
import com.captionglass.engine.*
import com.captionglass.nativebridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class CaptureState(
    val active: Boolean = false,
    val stopping: Boolean = false,
    val chineseSource: Boolean = false,
    val message: String = "尚未开启字幕",
    val stable: String = "",
    val provisional: String = "",
    val page: CaptionPage? = null,
    val history: List<Caption> = emptyList(),
    val readingBehind: Int = 0,
    val translationBacklog: Int = 0,
    val confirmed: Int = 0,
    val outcomes: Int = 0,
    val audioMs: Long = 0,
)

internal data class PcmFrame(val samples: FloatArray, val sampleRate: Int, val endMs: Long)

/** All mutable pipeline state belongs to Main. Only the two dedicated workers enter JNI. */
internal class CaptionSession(
    private val scope: CoroutineScope,
    private val chineseSource: Boolean,
    private val paginate: (String, Boolean) -> List<String>,
    private val publish: (CaptureState) -> Unit,
) {
    private val asrWorker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mtWorker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // 100 x 100 ms PCM frames. Overflow is fatal and explicit, never drop-oldest audio.
    private val pcm = Channel<PcmFrame>(100)
    private val id = UUID.randomUUID().toString()
    private val queue = TranslationQueue(id, contextCharacters = 512)
    private val scheduler = CaptionScheduler()
    private var state = CaptureState(active = true, chineseSource = chineseSource, message = "正在加载本地语言包…")
    private var gate = SourceGate()
    private val utteranceKeys = mutableSetOf<SegmentKey>()
    private var revision = 0
    private var sequence = 0L
    private var segmentStart = 0L
    private var beganAt = 0L
    private var recognizer: LocalRecognizer? = null
    private var translator: LocalTranslator? = null
    private var asrJob: Job? = null
    private var mtJob: Job? = null
    private var ticker: Job? = null
    private var activeCall: NativeCall? = null
    private var stopped = false
    private var failure: Throwable? = null
    private var closed = false
    private fun now() = if (beganAt == 0L) 0L else SystemClock.elapsedRealtime() - beganAt
    private fun update(change: CaptureState.() -> CaptureState) { state = state.change(); publish(state) }

    suspend fun start(directory: File) {
        publish(state)
        recognizer = withContext(asrWorker) { LocalRecognizer(directory) }
        check(!stopped) { "字幕已停止" }
        val call = NativeCall(120_000)
        activeCall = call
        try { translator = withContext(mtWorker) { LocalTranslator(directory, call) } }
        finally { call.close(); activeCall = null }
        check(!stopped) { "字幕已停止" }
        beganAt = SystemClock.elapsedRealtime()
        update { copy(message = "等待可捕获的声音") }
        asrJob = scope.launch {
            var sampleRate = 16_000
            var endMs = 0L
            try {
                for (frame in pcm) {
                    sampleRate = frame.sampleRate; endMs = frame.endMs
                    val results = withContext(asrWorker) { checkNotNull(recognizer).accept(frame.samples, sampleRate) }
                    results.forEach { hypothesis(it, endMs) }
                    update { copy(audioMs = endMs) }
                }
                val tail = withContext(asrWorker) { checkNotNull(recognizer).finish(sampleRate) }
                tail.forEach { hypothesis(it, endMs) }
            } catch (e: Exception) { failure = e; stop("识别中断，请重新开启字幕") }
        }
        ticker = scope.launch {
            while (isActive) {
                queue.expire(now()).forEach(::outcome)
                if (queue.cancelledActiveKey != null) activeCall?.cancel()
                pump()
                val page = if (stopped) null else scheduler.tick(now())
                if (state.page != page) update { copy(page = page) }
                delay(80)
            }
        }
    }

    /** Called by the capture worker; Channel is the only shared audio state. */
    fun offer(frame: PcmFrame): Boolean {
        require(frame.sampleRate in listOf(16_000, 48_000) && frame.samples.size <= frame.sampleRate / 10)
        return pcm.trySend(frame).isSuccess
    }

    private fun hypothesis(result: Hypothesis, atMs: Long) {
        check(result.text.length <= 8_192) { "识别原文超过单段上限，请重新开始" }
        val source = gate.update(result.text, revision++, atMs, result.final)
        if (source.invalidatesCommitted) {
            queue.invalidate(utteranceKeys).forEach(::outcome)
            scheduler.invalidate(utteranceKeys)
            if (queue.cancelledActiveKey != null) activeCall?.cancel()
            update { copy(history = history.map { if (it.segment.key in utteranceKeys)
                Caption(it.segment, untranslatedReason = UntranslatedReason.SUPERSEDED) else it }, page = null) }
        }
        update { copy(stable = source.stable, provisional = source.provisional) }
        source.committed?.let { text ->
            val key = SegmentKey(id, sequence++, if (source.correctionRequired) 1 else 0)
            utteranceKeys += key
            val segment = Segment(key, text, segmentStart.coerceAtMost(atMs), atMs)
            segmentStart = atMs
            update { copy(confirmed = confirmed + 1) }
            queue.submit(segment)?.let(::outcome)
            pump()
        }
        if (result.final) {
            gate = SourceGate(); revision = 0; utteranceKeys.clear(); segmentStart = atMs
        }
    }

    private fun outcome(caption: Caption) {
        // Overflow is an immediate status/history outcome, not a future subtitle that skips
        // past older translations still in flight. Reading and inference overflow stay distinct.
        val show = caption.untranslatedReason !in listOf(UntranslatedReason.SUPERSEDED, UntranslatedReason.BACKLOG) && !stopped
        val accepted = !show || scheduler.offer(caption, paginate(caption.segment.source, false),
            caption.translation?.let { paginate(it, true) }.orEmpty())
        // ponytail: last 200 terminal outcomes in memory; durable history/export belongs to M2.
        update { copy(history = (history + caption).sortedBy { it.segment.key.sequence }.takeLast(200), outcomes = outcomes + 1,
            translationBacklog = translationBacklog + if (caption.untranslatedReason == UntranslatedReason.BACKLOG) 1 else 0,
            readingBehind = readingBehind + if (accepted) 0 else 1) }
    }

    private fun pump() {
        if (stopped || mtJob?.isActive == true || translator == null) return
        val request = queue.take() ?: return
        val call = NativeCall((8_000 - (now() - request.segment.endMs)).coerceAtLeast(1))
        activeCall = call
        mtJob = scope.launch {
            val translated = try {
                withContext(mtWorker) { checkNotNull(translator).translate(request.segment.source, request.context, chineseSource, call) }
            } catch (_: Exception) { null }
            finally { call.close(); activeCall = null }
            queue.complete(request.segment.key, translated, maxOf(now(), request.segment.endMs))?.let(::outcome)
        }
    }

    fun message(text: String) { if (!stopped) update { copy(message = text) } }
    fun reflow() { scheduler.reflow(paginate) }
    fun stop(message: String = "字幕已停止") {
        if (stopped) return
        stopped = true
        queue.stop().forEach(::outcome)
        activeCall?.cancel()
        pcm.close()
        update { copy(stopping = true, message = message, page = null) }
    }

    suspend fun close() {
        if (closed) return
        pcm.close()
        asrJob?.join()
        // A natural EOF drains confirmed MT work. Explicit stop already gave every job an outcome.
        while (!stopped && (queue.pendingCount > 0 || mtJob?.isActive == true)) { pump(); delay(80) }
        mtJob?.join()
        ticker?.cancelAndJoin()
        withContext(asrWorker) { recognizer?.close(); recognizer = null }
        withContext(mtWorker) { translator?.close(); translator = null }
        asrWorker.close(); mtWorker.close()
        closed = true
        update { copy(active = false, stopping = false, page = null, message = if (stopped) message else "本次字幕已结束") }
        failure?.let { throw it }
    }
}
