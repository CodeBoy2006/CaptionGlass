package com.captionglass.app

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.captionglass.engine.Caption
import com.captionglass.engine.CaptionScheduler
import com.captionglass.engine.Segment
import com.captionglass.engine.SegmentKey
import com.captionglass.engine.SourceGate
import com.captionglass.engine.TranslationQueue
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ReplayState(
    val sessionId: String? = null,
    val running: Boolean = false,
    val stable: String = "",
    val provisional: String = "",
    val caption: Caption? = null,
    val history: List<Caption> = emptyList(),
    val readingBehind: Boolean = false,
)

/** Synthetic text only. These fixed pairs are a UI fixture, never an inference backend. */
class ReplayViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ReplayState())
    val state = mutableState.asStateFlow()
    private var replay: Job? = null

    fun start(chineseSource: Boolean) {
        stop()
        val sessionId = UUID.randomUUID().toString()
        mutableState.value = ReplayState(sessionId = sessionId, running = true)
        replay = viewModelScope.launch {
            val epoch = SystemClock.elapsedRealtime()
            fun now() = SystemClock.elapsedRealtime() - epoch
            val queue = TranslationQueue(sessionId)
            val scheduler = CaptionScheduler()
            val pairs = listOf(
                "Good subtitles give you time to read." to "好的字幕，会留出阅读的时间。",
                "I don't think we should translate every word immediately." to "我认为，我们不必立刻翻译每一个词。",
                "Keep the meaning clear, one thought at a time." to "一次讲清一个意思，让内容更易理解。",
            ).map { if (chineseSource) it.second to it.first else it }

            fun publish(caption: Caption) {
                val visible = scheduler.offer(caption)
                mutableState.update {
                    it.copy(history = it.history + caption, readingBehind = it.readingBehind || !visible)
                }
            }

            val worker = launch {
                while (isActive) {
                    val request = queue.take()
                    if (request == null) {
                        delay(50)
                    } else {
                        delay(600)
                        val translated = pairs.firstOrNull { it.first == request.segment.source }?.second
                        queue.complete(request.segment.key, translated, now())?.let(::publish)
                    }
                }
            }
            val display = launch {
                while (isActive) {
                    val caption = scheduler.tick(now())
                    mutableState.update { it.copy(caption = caption) }
                    delay(80)
                }
            }
            try {
                pairs.forEachIndexed { index, (source, _) ->
                    val startMs = now()
                    val gate = SourceGate()
                    val prefix = source.take(source.length / 2)
                    listOf(prefix, prefix, source, source).forEachIndexed { revision, text ->
                        delay(450)
                        val update = gate.update(text, revision, now(), isFinal = revision == 3)
                        mutableState.update { it.copy(stable = update.stable, provisional = update.provisional) }
                        update.committed?.let {
                            queue.submit(Segment(SegmentKey(sessionId, index.toLong()), it, startMs, now()))
                                ?.let(::publish)
                        }
                    }
                    delay(1_500)
                }
                delay(6_500)
                mutableState.update { it.copy(running = false, stable = "", provisional = "", caption = null) }
            } finally {
                worker.cancel()
                display.cancel()
                val stopped = queue.stop()
                mutableState.update {
                    if (it.sessionId == sessionId) it.copy(history = it.history + stopped) else it
                }
            }
        }
    }

    fun stop() {
        replay?.cancel()
        replay = null
        mutableState.update { it.copy(running = false, stable = "", provisional = "", caption = null) }
    }
}
