package com.captionglass.nativebridge

import com.k2fsa.sherpa.onnx.*
import com.captionglass.engine.Language
import java.io.Closeable
import java.io.File

data class Hypothesis(val text: String, val final: Boolean)
data class RecognizerFiles(val encoder: File, val decoder: File, val joiner: File, val tokens: File,
                           val decodingMethod: String, val language: Language? = null) {
    init { require(decodingMethod in setOf("greedy_search", "modified_beam_search")) }
}

/** Access and release on the ASR worker only. Semantic commits never reset this stream. */
class LocalRecognizer(files: RecognizerFiles) : Closeable {
    private val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = files.encoder.path,
                decoder = files.decoder.path,
                joiner = files.joiner.path),
            tokens = files.tokens.path,
            numThreads = 1, provider = "cpu"),
        endpointConfig = EndpointConfig(rule2 = EndpointRule(true, 1.2f, 0f)),
        enableEndpoint = true, decodingMethod = files.decodingMethod, maxActivePaths = 4))
    // The pinned runtime detects Zipformer/NeMo and configures their own feature extraction.
    private val stream = try {
        recognizer.createStream().also { stream ->
            try { files.language?.let { stream.setOption("language", it.code) } }
            catch (e: Throwable) { stream.release(); throw e }
        }
    } catch (e: Throwable) { recognizer.release(); throw e }
    private var ended = false

    fun accept(samples: FloatArray, sampleRate: Int): List<Hypothesis> {
        check(!ended)
        // sherpa's stateful LinearResample handles a device's 48 kHz fallback.
        stream.acceptWaveform(samples, sampleRate)
        return decode()
    }

    private fun decode(): List<Hypothesis> = buildList {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
            val endpoint = recognizer.isEndpoint(stream)
            add(Hypothesis(recognizer.getResult(stream).text.trim(), endpoint))
            if (endpoint) recognizer.reset(stream)
        }
    }

    fun finish(sampleRate: Int): List<Hypothesis> {
        check(!ended)
        ended = true
        // Stop-only tail flush covers the pinned chunks. No silence during capture.
        stream.acceptWaveform(FloatArray(sampleRate * 96 / 100), sampleRate)
        stream.inputFinished()
        return decode() + Hypothesis(recognizer.getResult(stream).text.trim(), true)
    }

    override fun close() { stream.release(); recognizer.release() }
}
