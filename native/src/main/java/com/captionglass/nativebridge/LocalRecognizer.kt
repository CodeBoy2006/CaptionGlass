package com.captionglass.nativebridge

import com.k2fsa.sherpa.onnx.*
import com.captionglass.engine.Language
import java.io.Closeable
import java.io.File

data class Hypothesis(val text: String, val final: Boolean, val textOffset: Long = 0)
data class RecognizerFiles(val paths: Map<String, File>, val adapter: String,
                           val decodingMethod: String, val language: Language? = null) {
    init { require(decodingMethod in setOf("greedy_search", "modified_beam_search")) }
    fun path(role: String) = paths.getValue(role).path
}

/** Access and release on the ASR worker only. Semantic commits never reset this stream. */
class LocalRecognizer(files: RecognizerFiles) : Closeable {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var offline: OfflineSpeechRecognizer? = null
    private var ended = false

    init {
        try {
            if (files.adapter != "online-transducer") offline = OfflineSpeechRecognizer(files)
            else {
                val online = OnlineRecognizer(config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(encoder = files.path("encoder"),
                            decoder = files.path("decoder"), joiner = files.path("joiner")),
                        tokens = files.path("tokens"), numThreads = 1, provider = "cpu"),
                    // Override the library's duration-only 20 s rule: endpoints require a pause.
                    endpointConfig = EndpointConfig(rule2 = EndpointRule(true, 1.2f, 0f),
                        rule3 = EndpointRule(true, 1.2f, 0f)),
                    enableEndpoint = true, decodingMethod = files.decodingMethod, maxActivePaths = 4))
                recognizer = online
                stream = online.createStream()
                files.language?.let { checkNotNull(stream).setOption("language", it.code) }
            }
        } catch (e: Throwable) { close(); throw e }
    }

    fun accept(samples: FloatArray, sampleRate: Int): List<Hypothesis> {
        check(!ended)
        require(sampleRate in listOf(16_000, 48_000) && samples.size <= sampleRate / 10)
        offline?.let { return it.accept(samples, sampleRate) }
        checkNotNull(stream).acceptWaveform(samples, sampleRate)
        return decode()
    }

    private fun decode(): List<Hypothesis> = buildList {
        val online = checkNotNull(recognizer)
        val audio = checkNotNull(stream)
        while (online.isReady(audio)) {
            online.decode(audio)
            val endpoint = online.isEndpoint(audio)
            add(hypothesis(online.getResult(audio).text.trim(), endpoint))
            if (endpoint) online.reset(audio)
        }
    }

    fun finish(sampleRate: Int): List<Hypothesis> {
        check(!ended)
        ended = true
        offline?.let { return it.finish() }
        val audio = checkNotNull(stream)
        // Stop-only tail flush covers the pinned streaming chunks.
        audio.acceptWaveform(FloatArray(sampleRate * 96 / 100), sampleRate)
        audio.inputFinished()
        return decode() + hypothesis(checkNotNull(recognizer).getResult(audio).text.trim(), true)
    }

    private fun hypothesis(text: String, final: Boolean): Hypothesis {
        var offset = (text.length - 4_096).coerceAtLeast(0)
        if (offset > 0 && text[offset].isLowSurrogate()) offset++
        return Hypothesis(text.substring(offset), final, offset.toLong())
    }

    override fun close() {
        stream?.release(); stream = null
        recognizer?.release(); recognizer = null
        offline?.close(); offline = null
    }
}
