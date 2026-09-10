package com.captionglass.nativebridge

import com.k2fsa.sherpa.onnx.*
import java.io.Closeable
import java.io.File

data class Hypothesis(val text: String, val final: Boolean)

/** Access and release on the ASR worker only. Semantic commits never reset this stream. */
class LocalRecognizer(directory: File) : Closeable {
    private val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(directory, "encoder-480ms.onnx").path,
                decoder = File(directory, "decoder-480ms.onnx").path,
                joiner = File(directory, "joiner-480ms.onnx").path),
            tokens = File(directory, "tokens.txt").path,
            numThreads = 1, provider = "cpu", modelType = "zipformer2"),
        endpointConfig = EndpointConfig(rule2 = EndpointRule(true, 1.2f, 0f)),
        enableEndpoint = true, decodingMethod = "greedy_search"))
    private val stream = try { recognizer.createStream() }
        catch (e: Exception) { recognizer.release(); throw e }
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
        // Stop-only tail flush: two 480 ms chunks. No synthetic silence during capture.
        stream.acceptWaveform(FloatArray(sampleRate * 96 / 100), sampleRate)
        stream.inputFinished()
        return decode() + Hypothesis(recognizer.getResult(stream).text.trim(), true)
    }

    override fun close() { stream.release(); recognizer.release() }
}
