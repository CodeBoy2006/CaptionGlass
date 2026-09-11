package com.captionglass.nativebridge

import com.captionglass.engine.Language
import com.captionglass.engine.SpeechWindow
import com.k2fsa.sherpa.onnx.*
import java.io.Closeable

/** Utterance decoding on the same ASR worker, with one model and bounded PCM storage. */
internal class OfflineSpeechRecognizer(private val files: RecognizerFiles) : Closeable {
    private var recognizer: OfflineRecognizer? = null
    private var japanese: JapaneseZipformer? = null
    private var vad: Vad? = null
    private var resampler = 0L
    private var rate = 0
    private val window = SpeechWindow()
    private val frame = FloatArray(512)
    private var filled = 0

    init {
        try {
            vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = files.path("vad")),
                numThreads = 1, provider = "cpu"))
            if (files.adapter == "japanese-zipformer") japanese = JapaneseZipformer(files)
            else {
                val config = OfflineModelConfig(numThreads = 1, provider = "cpu")
                when (files.adapter) {
                    "qwen3-asr" -> config.qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = files.path("frontend"), encoder = files.path("encoder"),
                        decoder = files.path("decoder"), tokenizer = files.paths.getValue("vocab").parent!!,
                        maxTotalLen = 512, maxNewTokens = 256)
                    "offline-transducer" -> {
                        config.transducer = OfflineTransducerModelConfig(encoder = files.path("encoder"),
                            decoder = files.path("decoder"), joiner = files.path("joiner"))
                        config.modelType = "nemo_transducer"
                        config.tokens = files.path("tokens")
                    }
                    "offline-ctc" -> {
                        config.nemo = OfflineNemoEncDecCtcModelConfig(files.path("model"))
                        config.tokens = files.path("tokens")
                    }
                    else -> error("Unsupported ASR adapter")
                }
                recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = config))
            }
        } catch (e: Throwable) { close(); throw e }
    }

    fun accept(samples: FloatArray, sampleRate: Int): List<Hypothesis> {
        if (rate == 0) {
            rate = sampleRate
            if (rate == 48_000) resampler = AudioResample.create()
        }
        check(rate == sampleRate)
        return consume(if (resampler == 0L) samples else AudioResample.process(resampler, samples, false))
    }

    private fun consume(samples: FloatArray): List<Hypothesis> = buildList {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(512 - filled, samples.size - offset)
            samples.copyInto(frame, filled, offset, offset + count)
            offset += count; filled += count
            if (filled == 512) {
                val probability = checkNotNull(vad).compute(frame)
                window.accept(frame, probability >= 0.5f)?.let { add(decode(it)) }
                filled = 0
            }
        }
    }

    private fun decode(samples: FloatArray): Hypothesis {
        // Exact digital silence needs no inference; nonzero quiet audio is never discarded.
        if (samples.all { it == 0f }) return Hypothesis("", true)
        japanese?.let { return Hypothesis(it.decode(samples), true) }
        val model = checkNotNull(recognizer)
        val stream = model.createStream()
        return try {
            files.language?.let {
                stream.setOption("language", if (it == Language.TL) "Filipino" else it.promptName)
            }
            stream.acceptWaveform(samples, 16_000)
            model.decode(stream)
            val result = model.getResult(stream)
            check(files.adapter != "qwen3-asr" || stream.getOption("captionglass_complete") == "1") { "ASR did not reach end of generation" }
            Hypothesis(result.text.trim(), true)
        } finally { stream.release() }
    }

    fun finish(): List<Hypothesis> = buildList {
        if (resampler != 0L) addAll(consume(AudioResample.process(resampler, FloatArray(0), true)))
        if (filled > 0) {
            window.accept(frame.copyOf(filled), true)?.let { add(decode(it)) }
            filled = 0
        }
        window.finish()?.let { add(decode(it)) }
    }

    override fun close() {
        recognizer?.release(); recognizer = null
        japanese?.close(); japanese = null
        vad?.release(); vad = null
        if (resampler != 0L) AudioResample.release(resampler)
        resampler = 0
    }
}

internal object AudioResample {
    init { System.loadLibrary("captionglass") }
    external fun create(): Long
    external fun process(handle: Long, samples: FloatArray, flush: Boolean): FloatArray
    external fun release(handle: Long)
}
