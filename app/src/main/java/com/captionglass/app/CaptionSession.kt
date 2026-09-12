package com.captionglass.app

import android.os.SystemClock
import android.content.Context
import android.util.Log
import com.captionglass.engine.*
import com.captionglass.nativebridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Observed capture condition. The first five describe a live session; the rest explain how one ended. */
enum class CaptureStatus {
    IDLE, PREPARING, WAITING, HEARING, SILENT,
    STOPPED, ENDED, CONSENT_ENDED, PERMISSION_LOST, PACK_INVALID, LOAD_FAILED, BACKEND_UNAVAILABLE, BACKEND_MODEL_UNSUPPORTED,
    AUDIO_UNSUPPORTED, CAPTURE_INTERRUPTED, OVERRUN, RECOGNITION_FAILED, TRANSLATION_FAILED, START_FAILED,
}

/** A stop cause the UI can explain without parsing exception text. */
internal class CaptureFailure(val status: CaptureStatus) : Exception(status.name)

data class CaptureState(
    val active: Boolean = false,
    val stopping: Boolean = false,
    val selection: ModelSelection = ModelSelection(),
    val status: CaptureStatus = CaptureStatus.IDLE,
    val stable: String = "",
    val provisional: String = "",
    val lines: List<CaptionLine> = emptyList(),
    val history: List<Caption> = emptyList(),
    val translationBacklog: Int = 0,
    val confirmed: Int = 0,
    val outcomes: Int = 0,
    val audioMs: Long = 0,
)

internal data class PcmFrame(val samples: FloatArray, val sampleRate: Int, val endMs: Long)

/** All mutable pipeline state belongs to Main. Only the two dedicated workers enter JNI. */
internal class CaptionSession(
    private val scope: CoroutineScope,
    private val selection: ModelSelection,
    private val publish: (CaptureState) -> Unit,
) {
    private val asrWorker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mtWorker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // 100 x 100 ms PCM frames. Overflow is fatal and explicit, never drop-oldest audio.
    private val pcm = Channel<PcmFrame>(100)
    private val id = UUID.randomUUID().toString()
    private val queue = TranslationQueue(id, contextCharacters = 512)
    private val feed = CaptionFeed()
    private var state = CaptureState(active = true, selection = selection, status = CaptureStatus.PREPARING)
    private var gate = SourceGate(language = selection.languages.source)
    private val utteranceKeys = mutableMapOf<SegmentKey, Long>()
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

    suspend fun start(context: Context, asrFiles: RecognizerFiles, translationModel: File, format: TranslationFormat) {
        publish(state)
        recognizer = withContext(asrWorker) { LocalRecognizer(asrFiles) }
        check(!stopped) { "字幕已停止" }
        val call = NativeCall(120_000)
        activeCall = call
        try { translator = withContext(mtWorker) { LocalTranslator(context, translationModel, format, call, selection.backend) } }
        finally { call.close(); activeCall = null }
        check(!stopped) { "字幕已停止" }
        beganAt = SystemClock.elapsedRealtime()
        update { copy(status = CaptureStatus.WAITING) }
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
            } catch (e: Exception) { failure = e; stop(CaptureStatus.RECOGNITION_FAILED) }
        }
        ticker = scope.launch {
            while (isActive) {
                queue.expire(now()).forEach(::outcome)
                if (queue.cancelledActiveKey != null) activeCall?.cancel()
                pump()
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
        val source = gate.update(result.text, revision++, atMs, result.final, result.textOffset)
        utteranceKeys.entries.removeAll { it.value <= source.retainedFrom }
        if (source.invalidatesCommitted) {
            feed.invalidate(utteranceKeys.keys)
            queue.invalidate(utteranceKeys.keys).forEach(::outcome)
            if (queue.cancelledActiveKey != null) activeCall?.cancel()
            update { copy(history = history.map { if (it.segment.key in utteranceKeys)
                Caption(it.segment, untranslatedReason = UntranslatedReason.SUPERSEDED) else it }, lines = feed.lines) }
            utteranceKeys.clear()
        }
        update { copy(stable = source.pendingStable, provisional = source.provisional) }
        source.committed?.let { text ->
            val key = SegmentKey(id, sequence++, if (source.correctionRequired) 1 else 0)
            utteranceKeys[key] = source.committedEnd
            val segment = Segment(key, text, segmentStart.coerceAtMost(atMs), atMs)
            segmentStart = atMs
            feed.submit(segment)
            update { copy(confirmed = confirmed + 1, lines = feed.lines) }
            queue.submit(segment, now())?.let(::outcome)
            pump()
        }
        if (result.final) {
            gate = SourceGate(language = selection.languages.source); revision = 0; utteranceKeys.clear(); segmentStart = atMs
        }
    }

    private fun outcome(caption: Caption) {
        feed.complete(caption)
        Log.i("CaptionGlassMT", "session=$id sequence=${caption.segment.key.sequence} revision=${caption.segment.key.revision} " +
            "result=${caption.untranslatedReason ?: "TRANSLATED"}")
        // ponytail: last 200 terminal outcomes in memory; durable history belongs to M2.
        update { copy(history = (history + caption).sortedBy { it.segment.key.sequence }.takeLast(200), outcomes = outcomes + 1,
            lines = feed.lines,
            translationBacklog = translationBacklog + if (caption.untranslatedReason in listOf(
                UntranslatedReason.BACKLOG, UntranslatedReason.TIMED_OUT, UntranslatedReason.FAILED)) 1 else 0) }
    }

    private fun pump() {
        if (stopped || mtJob?.isActive == true || translator == null || queue.pendingCount == 0) return
        mtJob = scope.launch {
            // Drain on completion, without waiting for the display ticker between requests.
            while (!stopped) {
                queue.expire(now()).forEach(::outcome)
                val request = queue.take() ?: break
                val started = now()
                val waited = started - request.submittedAtMs
                val call = NativeCall((8_000 - waited).coerceAtLeast(1))
                activeCall = call
                var unavailable = false
                var firstPreviewMs = -1L
                val translated = try {
                    withContext(mtWorker) { checkNotNull(translator).translate(request.segment.source, request.context, selection.languages, call,
                        onProgress = { text -> scope.launch {
                            if (!stopped && activeCall === call && queue.cancelledActiveKey != request.segment.key &&
                                feed.progress(request.segment.key, text)) {
                                if (firstPreviewMs < 0 && text.isNotBlank()) firstPreviewMs = now() - started
                                update { copy(lines = feed.lines) }
                            }
                        } }) }
                } catch (e: Exception) {
                    unavailable = e is TranslationUnavailableException
                    Log.w("CaptionGlassMT", "session=$id sequence=${request.segment.key.sequence} error=${e.javaClass.simpleName}")
                    null
                }
                finally { call.close(); activeCall = null }
                Log.i("CaptionGlassMT", "session=$id sequence=${request.segment.key.sequence} queue_ms=$waited " +
                    "first_preview_ms=$firstPreviewMs run_ms=${now() - started}")
                queue.complete(request.segment.key, translated, now())?.let(::outcome)
                if (unavailable) stop(CaptureStatus.TRANSLATION_FAILED)
            }
        }
    }

    fun status(value: CaptureStatus) { if (!stopped && state.status != value) update { copy(status = value) } }
    fun stop(reason: CaptureStatus = CaptureStatus.STOPPED) {
        if (stopped) return
        stopped = true
        queue.stop().forEach(::outcome)
        activeCall?.cancel()
        pcm.close()
        update { copy(stopping = true, status = reason) }
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
        update { copy(active = false, stopping = false, status = if (stopped) status else CaptureStatus.ENDED) }
        failure?.let { throw it }
    }
}
