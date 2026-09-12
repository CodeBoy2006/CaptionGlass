package com.captionglass.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import com.captionglass.nativebridge.*
import com.captionglass.engine.Language
import com.captionglass.engine.LanguagePair
import com.captionglass.engine.TranslationFormat
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit, model-dependent acceptance; never runs as part of ordinary CI. */
class DeviceChecks : Instrumentation() {
    private var mode = "all"
    private var requestedModel: String? = null
    private var requestedTranslator: String? = null
    private var repetitions = 4
    private var backend = TranslationBackend.VULKAN
    private val translation get() = catalog.translator(ModelSelection())
    private val catalog by lazy { ModelCatalog(targetContext) }
    private val baseline get() = catalog.recognizer(ModelSelection())
    private val multilingual get() = catalog.recognizers.single { it.id == "pengcheng-8lang-int8" }
    private val nemotron get() = catalog.recognizers.single { it.id == "nemotron-3.5-560ms-int8" }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "all"
        requestedModel = arguments?.getString("model"); requestedTranslator = arguments?.getString("mt")
        backend = checkNotNull(TranslationBackend.fromId(arguments?.getString("backend") ?: "vulkan"))
        repetitions = (arguments?.getString("repetitions")?.toInt() ?: 4).also { require(it in 4..512) }
        start()
    }
    private fun report(text: String) { sendStatus(0, Bundle().apply { putString("stream", "$text\n") }) }
    override fun onStart() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            if (mode == "display") {
                captionDisplayChecks()
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: display\n") }); return
            }
            if (mode == "export") {
                recordExportChecks(activity)
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: export\n") }); return
            }
            check(mode in setOf("all", "catalog", "verify", "native", "no-vulkan", "backend", "multilingual", "replay", "fallback", "reading", "asr-ja", "nemotron", "models", "model-network", "adapter", "pipeline-model", "continuity", "mt-profile"))
            report("Verifying pinned model files")
            catalogChecks()
            if (mode == "catalog") {
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: catalog\n") }); return
            }
            if (mode == "backend") {
                runBlocking { backendChecks() }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: backend ${backend.id}\n") }); return
            }
            if (mode == "mt-profile") {
                val model = catalog.models.single { it.id == requestedModel && it.kind == "mt" }
                ModelPack.verify(targetContext, model)
                runBlocking { translationProfile(model) }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: mt-profile\n") }); return
            }
            if (mode in listOf("models", "model-network")) {
                runBlocking { modelManagerChecks(mode == "model-network") }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: $mode\n") }); return
            }
            if (mode in listOf("pipeline-model", "continuity")) {
                val model = catalog.models.single { it.id == requestedModel }
                val mt = catalog.models.single { it.id == (requestedTranslator ?: ModelSelection().translatorId) }
                val source = if (mode == "continuity" && Language.EN in model.languages && Language.EN in mt.languages) Language.EN else Language.JA
                val selected = ModelSelection(LanguagePair(source, Language.ZH), model.id,
                    mt.id)
                check(catalog.valid(selected))
                catalog.required(selected).forEach { ModelPack.verify(targetContext, it) }
                runBlocking {
                    replay(source.code, Language.ZH, recognizerId = selected.recognizerId, translatorId = selected.translatorId,
                        repetitions = if (mode == "continuity") repetitions else 1)
                    replay(source.code, Language.ZH, stopAtMs = if (mode == "continuity") 24_000 else 4500,
                        recognizerId = selected.recognizerId, translatorId = selected.translatorId,
                        repetitions = if (mode == "continuity") repetitions else 1)
                }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: $mode\n") }); return
            }
            if (mode == "adapter") {
                val model = catalog.models.single { it.id == requestedModel }
                ModelPack.verify(targetContext, model)
                runBlocking {
                    if (model.kind == "asr") {
                        if (Language.JA in model.languages) japaneseAsrCheck(model) else fallbackCheck(model)
                        if (model.adapter == "qwen3-asr") qwenCompletionCheck(model)
                    } else withContext(Dispatchers.Default) {
                        NativeCall(120_000).use { load ->
                            LocalTranslator(targetContext, model.file(targetContext, "model"), model.translationFormat, load, backend).use { mt ->
                                NativeCall(60_000).use { call ->
                                    val text = mt.translate("今日はいい天気です。", emptyList(), LanguagePair(Language.JA, Language.ZH), call)
                                    check(text.any { it in '\u4e00'..'\u9fff' })
                                    report("MT ${model.id}: $text ${mt.timings()}")
                                }
                                prefixCacheCheck(mt, model.translationFormat)
                                if (model.translationFormat == TranslationFormat.MURASAKI) {
                                    var failures = 0
                                    for (source in listOf("今日はいい天気です", "今日はいい天気です。",
                                        "今日はいい天気です公園を散歩しましょうこのアプリは日本語の音声を翻訳します。",
                                        "この薬は飲んではいけません", "明日は公園に行きません",
                                        "会議は午後三時に始まります", "田中さんはまだ来ていません",
                                        "公園を散歩しましょう。", "公園を散歩しましょう",
                                        "今日はいい天気です公園を散歩しましょうこのアプリは日本語の音声を翻訳します今日はいい天気ですを散歩しましょうこのアプリは日本語の音声を翻訳します。")) {
                                        var partial = ""
                                        NativeCall(8_000).use { call ->
                                            val result = runCatching {
                                                mt.translate(source, emptyList(), LanguagePair(Language.JA, Language.ZH), call,
                                                    maxTokens = 64, onProgress = { partial = it }).also { text ->
                                                    check(text.any { it in '\u4e00'..'\u9fff' } && !text.startsWith("["))
                                                    if ("ません" in source) check(Regex("不|没|未|勿|禁止|别").containsMatchIn(text))
                                                    if ("三時" in source) check(Regex("三|3").containsMatchIn(text))
                                                    if ("田中" in source) check("田中" in text)
                                                    if (Regex("天気").findAll(source).count() == 2)
                                                        check(Regex("天气").findAll(text).count() == 2) { "Repeated source was compressed: $text" }
                                                }
                                            }
                                            report("ASR EDGE source=$source result=${result.getOrNull()} error=${result.exceptionOrNull()?.message} partial=$partial ${mt.timings()}")
                                            if (result.isFailure) failures++
                                        }
                                    }
                                    check(failures == 0) { "Murasaki failed $failures ASR-shaped inputs" }
                                }
                            }
                        }
                    }
                }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: adapter ${model.id}\n") }); return
            }
            listOf(baseline, nemotron, multilingual, translation).forEach { ModelPack.verify(targetContext, it) }
            if (mode == "all") {
                val tokens = File(ModelPack.directory(targetContext, baseline), "tokens.txt")
                val original = tokens.readBytes()
                try {
                    val changed = original.copyOf()
                    changed[0] = (changed[0].toInt() xor 1).toByte()
                    tokens.writeBytes(changed)
                    check(runCatching { ModelPack.verify(targetContext, baseline) }.isFailure) { "Accepted a same-size corrupt model file" }
                } finally { tokens.writeBytes(original) }
                ModelPack.verify(targetContext, baseline)
                report("PASS: same-size model corruption rejected; original restored and verified")
                val pack = ModelPack.directory(targetContext, baseline)
                val backup = File(targetContext.filesDir, "models/${baseline.id}-backup")
                check(!backup.exists() && pack.renameTo(backup))
                check(ModelPack.directory(targetContext, baseline).isDirectory && !backup.exists())
                check(ModelPack.ready(targetContext, baseline))
                report("PASS: interrupted pack activation restores previous pack")
            }
            runBlocking {
                if (mode == "no-vulkan") {
                    // Backend registration is process-wide; run this mode in its own instrumentation process.
                    android.system.Os.setenv("GGML_DISABLE_VULKAN", "1", true)
                    NativeCall(30_000).use { call ->
                        val error = runCatching { LocalTranslator(targetContext, translation.file(targetContext, "model"), translation.translationFormat, call, TranslationBackend.VULKAN).close() }.exceptionOrNull()
                        check(error is TranslationBackendException && error.message == "vulkan_device_unavailable") { "Unexpected missing-GPU result: $error" }
                    }
                    report("PASS: missing Vulkan device rejected without CPU-only fallback")
                }
                if (mode == "asr-ja") japaneseAsrCheck(multilingual)
                if (mode in listOf("all", "nemotron")) {
                    japaneseAsrCheck(nemotron)
                    replay("ja", Language.ZH, recognizerId = nemotron.id)
                    replay("ja", Language.EN, stopAtMs = 8500, recognizerId = nemotron.id)
                    replay("en", Language.JA, recognizerId = nemotron.id)
                    fallbackCheck(nemotron)
                }
                if (mode in listOf("all", "native", "multilingual")) nativeChecks()
                if (mode in listOf("all", "replay")) {
                    replay("en", Language.ZH)
                    replay("en", Language.ZH, stopAtMs = 4500)
                    replay("zh", Language.EN)
                }
                if (mode in listOf("all", "multilingual")) {
                    replay("ja", Language.ZH)
                    replay("ja", Language.EN)
                    replay("ja", Language.ZH, stopAtMs = 8500)
                    replay("en", Language.JA, recognizerId = multilingual.id)
                }
                if (mode in listOf("all", "fallback")) fallbackCheck()
                if (mode == "reading") {
                    replay("en", Language.ZH, repetitions = 2)
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: $mode\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${e.stackTraceToString()}\n") })
        } finally {
            runOnMainSync { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
    private suspend fun backendChecks() = withContext(Dispatchers.Default) {
        val spec = catalog.translators.single { it.id == (requestedModel ?: translation.id) }
        ModelPack.verify(targetContext, spec)
        val npu = LocalTranslator.hexagonAvailable()
        report("Hexagon driver/architecture available=$npu; requested=${backend.id}")
        if (backend == TranslationBackend.CPU) {
            // CPU must work without initializing either GPU backend.
            for (gpu in listOf(TranslationBackend.VULKAN, TranslationBackend.OPENCL)) {
                android.system.Os.setenv("GGML_DISABLE_${gpu.id.uppercase()}", "1", true)
                NativeCall(120_000).use { call ->
                    val failure = runCatching { LocalTranslator(targetContext, spec.file(targetContext, "model"),
                        spec.translationFormat, call, gpu).close() }.exceptionOrNull()
                    check(failure is TranslationBackendException && failure.message == "${gpu.id}_device_unavailable")
                }
            }
            report("PASS: unavailable Vulkan/OpenCL explicitly rejected before CPU translation")
        }
        if (backend == TranslationBackend.HEXAGON && (spec.id != "hy-mt2-1.8b-q8-0" || !npu)) {
            NativeCall(120_000).use { call ->
                val failure = runCatching { LocalTranslator(targetContext, spec.file(targetContext, "model"),
                    spec.translationFormat, call, backend).close() }.exceptionOrNull()
                if (spec.id != "hy-mt2-1.8b-q8-0") check(failure is TranslationModelUnsupportedException)
                else check(failure is TranslationBackendException)
                report("PASS: explicit Hexagon rejection ${failure?.message}; no CPU fallback")
            }
            return@withContext
        }
        repeat(2) {
            NativeCall(120_000).use { load ->
                LocalTranslator(targetContext, spec.file(targetContext, "model"), spec.translationFormat, load, backend).use { mt ->
                    var streamed = false
                    NativeCall(8_000).use { call ->
                        val result = mt.translate("The meeting starts at three o'clock.", emptyList(), LanguagePair(), call,
                            onProgress = { if (it.isNotBlank()) streamed = true })
                        check(streamed && result.any { it in '\u4e00'..'\u9fff' } && Regex("三|3").containsMatchIn(result))
                        check("backend=${backend.id} " in mt.timings())
                        report("PASS: ${backend.id} load/translate/close cycle=$it $result ${mt.timings()}")
                    }
                    NativeCall(60_000).use { call ->
                        var partial = false
                        check(runCatching {
                            mt.translate("Good subtitles give you time to read.", emptyList(), LanguagePair(), call,
                                onProgress = { partial = true; call.cancel() })
                        }.isFailure)
                        check(partial)
                    }
                    prefixCacheCheck(mt, spec.translationFormat)
                    NativeCall(8_000).use { call ->
                        val result = mt.translate("明日は公園に行きません。", emptyList(), LanguagePair(Language.JA, Language.ZH), call)
                        check("公园" in result && Regex("不|没|未").containsMatchIn(result))
                        report("PASS: ${backend.id} negation after cancellation/recovery $result ${mt.timings()}")
                    }
                }
            }
        }
    }
    /** Fixed native workload for before/after latency comparisons, not a speech-quality benchmark. */
    private suspend fun translationProfile(model: ModelSpec) = withContext(Dispatchers.Default) {
        val sources = listOf("今日はいい天気です。", "明日は公園に行きません。", "会議は午後三時に始まります。", "このアプリは音声を翻訳します。")
        val history = mutableListOf<String>()
        val power = targetContext.getSystemService(android.os.PowerManager::class.java)
        val began = SystemClock.elapsedRealtime()
        NativeCall(120_000).use { load ->
            LocalTranslator(targetContext, model.file(targetContext, "model"), model.translationFormat, load, backend).use { mt ->
                report("PREPARE model=${model.id} ready_ms=${SystemClock.elapsedRealtime() - began}")
                repeat(12) { index ->
                    NativeCall(30_000).use { call ->
                        val started = SystemClock.elapsedRealtime()
                        var first = -1L
                        val source = sources[index % sources.size]
                        val text = mt.translate(source, history.takeLast(4), LanguagePair(Language.JA, Language.ZH), call,
                            onProgress = { if (first < 0 && it.isNotBlank()) first = SystemClock.elapsedRealtime() - started })
                        check(text.any { it in '\u4e00'..'\u9fff' } && first >= 0)
                        report("PROFILE model=${model.id} index=$index first_ms=$first total_ms=${SystemClock.elapsedRealtime() - started} thermal=${power.currentThermalStatus} ${mt.timings()}: $text")
                        check(when (index % sources.size) {
                            0 -> "天气" in text && "音" !in text
                            1 -> "公园" in text && "天气" !in text && Regex("不|没|未").containsMatchIn(text)
                            2 -> "会" in text && "公园" !in text && Regex("三|3").containsMatchIn(text)
                            else -> "音" in text && "会" !in text
                        }) { "Profile translated background or lost source information: $source => $text" }
                        history += source
                    }
                }
            }
        }
    }

    private fun prefixCacheCheck(mt: LocalTranslator, format: TranslationFormat) {
        val pair = LanguagePair(Language.JA, Language.ZH)
        fun metric(name: String) = Regex("(?:^| )$name=([^ ]+)").find(mt.timings())!!.groupValues[1].toDouble()
        fun translated(source: String): String = NativeCall(30_000).use { mt.translate(source, emptyList(), pair, it) }
        val first = translated("今日はいい天気です。")
        check(translated("今日はいい天気です。") == first)
        val prefixSize = metric("cached_tokens")
        if (format != TranslationFormat.MILMMT) check(prefixSize > 0)
        translated("明日は公園に行きません。")
        check(translated("今日はいい天気です。") == first) { "Earlier source or generated text contaminated the retained prefix" }
        NativeCall(30_000).use { call ->
            var previewed = false
            check(runCatching { mt.translate("今日はいい天気です。", emptyList(), pair, call, onProgress = {
                if (it.isNotBlank()) { previewed = true; call.cancel() }
            }) }.isFailure)
            check(previewed)
        }
        check(translated("今日はいい天気です。") == first)
        check(metric("cached_tokens") == prefixSize) { "Normal cancellation discarded the prepared fixed prefix" }
        NativeCall(30_000).use { call ->
            check(runCatching { mt.translate("今日はいい天気です。", emptyList(), pair, call, maxTokens = 1) }.isFailure)
        }
        check(translated("今日はいい天気です。") == first)
        check(metric("cached_tokens") == 0.0) { "Failed call retained a reusable prefix" }
        check(translated("今日はいい天気です。") == first && metric("cached_tokens") == prefixSize)
        report("PASS: prefix reuse, unrelated source isolation, cancellation retention and failure cold recovery")
    }
    /** Diagnostic uses the same real-time PCM without SourceGate, MT, or UI state. */
    private suspend fun japaneseAsrCheck(model: ModelSpec) = withContext(Dispatchers.Default) {
        val samples = readWave(File(targetContext.filesDir, "fixtures/ja.wav"))
        report("ASR model=${model.id} forced_language=${model.languagePrompt}")
        LocalRecognizer(model.recognizerFiles(targetContext, Language.JA)).use { asr ->
            val began = SystemClock.elapsedRealtime()
            var previous = ""
            val finalTexts = mutableListOf<String>()
            for (position in samples.indices step 1600) {
                val end = minOf(position + 1600, samples.size)
                delay((began + end * 1000L / 16000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                asr.accept(samples.copyOfRange(position, end), 16000).forEach {
                    if (it.text != previous || it.final) report("ASR ja at_ms=${end * 1000L / 16000} final=${it.final}: ${it.text}")
                    previous = it.text
                    if (it.final) finalTexts += it.text
                }
            }
            asr.finish(16000).forEach { report("ASR ja tail final=${it.final}: ${it.text}"); if (it.final) finalTexts += it.text }
            check(finalTexts.joinToString("").contains("天気")) { "Expected weather sentence missing: $finalTexts" }
        }
    }
    private suspend fun qwenCompletionCheck(model: ModelSpec) = withContext(Dispatchers.Default) {
        val files = model.recognizerFiles(targetContext, Language.JA)
        val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(numThreads = 1, provider = "cpu",
                qwen3Asr = com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig(convFrontend = files.path("frontend"),
                    encoder = files.path("encoder"), decoder = files.path("decoder"),
                    tokenizer = files.paths.getValue("vocab").parent!!, maxNewTokens = 1))))
        try {
            val stream = recognizer.createStream()
            try {
                stream.setOption("language", "Japanese")
                stream.acceptWaveform(readWave(File(targetContext.filesDir, "fixtures/ja.wav")), 16_000)
                recognizer.decode(stream)
                check(stream.getOption("captionglass_complete") == "0") { "Qwen accepted truncated generation as complete" }
                report("PASS: Qwen output-budget termination is explicitly incomplete")
            } finally { stream.release() }
        } finally { recognizer.release() }
    }

    private fun catalogChecks() {
        val initial = ModelSelection()
        check(TranslationBackend.entries.map { it.id }.toSet() == setOf("vulkan", "opencl", "cpu", "hexagon"))
        check(TranslationBackend.fromId("unknown") == null)
        TranslationBackend.entries.forEach { selected ->
            check(TranslationBackend.fromId(selected.id) == selected)
            check(catalog.withLanguages(initial.copy(backend = selected), LanguagePair(Language.JA, Language.ZH)).backend == selected)
        }
        check(catalog.valid(initial))
        val japanese = catalog.withLanguages(initial, LanguagePair(Language.JA, Language.ZH))
        check(japanese.recognizerId == nemotron.id && catalog.valid(japanese))
        check(!catalog.valid(japanese.copy(recognizerId = baseline.id)))
        check(Language.FR in catalog.sources && Language.FR in translation.languages)
        check(Language.HE !in catalog.sources && Language.HE in translation.languages)
        check(Language.TH !in nemotron.languages && Language.ID !in nemotron.languages)
        check(nemotron.recognizerFiles(targetContext, Language.JA).language == Language.JA)
        check(multilingual.recognizerFiles(targetContext, Language.JA).language == null)
        check(runCatching { nemotron.recognizerFiles(targetContext, Language.TH) }.isFailure)
        check(catalog.withLanguages(initial, LanguagePair(Language.FR, Language.ZH)).recognizerId == nemotron.id)
        check(catalog.withLanguages(japanese, LanguagePair(Language.TH, Language.ZH)).recognizerId == multilingual.id)
        check(catalog.withLanguages(japanese.copy(recognizerId = multilingual.id), japanese.languages).recognizerId == multilingual.id)
        check(catalog.withLanguages(japanese, japanese.languages.swapped()).languages.target == Language.JA)
        check(catalog.required(initial).last() == catalog.required(japanese).last())
        check(baseline.file(targetContext, "tokens") != multilingual.file(targetContext, "tokens"))
        check(runCatching { catalog.withLanguages(initial, LanguagePair(Language.HE, Language.ZH)) }.isFailure)
        val revised = initial.copy(translatorId = "hy-mt2-streamrevise-v4-q4-k-m")
        check(catalog.valid(revised) && catalog.required(revised).last().id == revised.translatorId)
        check(!catalog.valid(revised.copy(languages = LanguagePair(Language.EN, Language.FR))))
        check(catalog.withLanguages(revised, LanguagePair(Language.EN, Language.FR)).translatorId == initial.translatorId)
        check(catalog.families.getValue("NVIDIA Parakeet").size == 3)
        check(catalog.families.getValue("Hy-MT2").size == 3)
        check(catalog.recognizers.filter { it.segmented }.all { model -> model.files.any { it.role == "vad" } })
        report("PASS: catalog pins, family grouping, independent ASR/MT selection and language compatibility")
    }
    private suspend fun nativeChecks() = withContext(Dispatchers.Default) {
        val began = SystemClock.elapsedRealtime()
        val load = NativeCall(120_000)
        val model = try { LocalTranslator(targetContext, translation.file(targetContext, "model"), translation.translationFormat, load, backend) } finally { load.close() }
        report("MT load_ms=${SystemClock.elapsedRealtime() - began}")
        try {
            fun translate(source: String, target: Language, sourceLanguage: Language = Language.EN): String {
                val call = NativeCall(30_000)
                val start = SystemClock.elapsedRealtime()
                return try { model.translate(source, emptyList(), LanguagePair(sourceLanguage, target), call).also {
                    report("MT to-${target.code} ms=${SystemClock.elapsedRealtime() - start}: $source => $it")
                    report("MT ${model.timings()}")
                } } finally { call.close() }
            }
            NativeCall(30_000).use { call ->
                val previews = mutableListOf<String>()
                val result = model.translate("Good subtitles give you time to read.", emptyList(), LanguagePair(Language.EN, Language.ZH), call,
                    onProgress = { previews += it })
                check(previews.any { it.isNotBlank() } && previews.all { '\ufffd' !in it && result.startsWith(it) })
                report("PASS: actual ${backend.id} translation streamed ${previews.size} UTF-8 previews before completion: $result")
            }
            prefixCacheCheck(model, translation.translationFormat)
            check(translate("今天天气很好，我们去公园散步。", Language.EN, Language.ZH).contains("park", ignoreCase = true))
            check(translate("今日はいい天気です。", Language.ZH, Language.JA).contains("天气"))
            check(translate("今日はいい天気です。", Language.EN, Language.JA).contains("weather", ignoreCase = true))
            check(translate("Thank you very much.", Language.JA).any { it in '\u3040'..'\u30ff' })
            check(translate("非常感谢。", Language.JA, Language.ZH).any { it in '\u3040'..'\u30ff' })
            check(translate("Thank you very much.", Language.FR).contains("merci", ignoreCase = true))
            check(translate("Thank you very much.", Language.KO).any { it in '\uac00'..'\ud7af' })
            check(translate("Thank you very much.", Language.AR).any { it in '\u0600'..'\u06ff' })
            NativeCall(10_000).use { call ->
                call.cancel()
                val start = SystemClock.elapsedRealtime()
                check(runCatching { model.translate("Hello", emptyList(), LanguagePair(Language.EN, Language.ZH), call) }.isFailure)
                check(SystemClock.elapsedRealtime() - start < 1000)
            }
            NativeCall(30_000).use { call ->
                var streamed = false
                check(runCatching { model.translate("Good subtitles give you time to read.", emptyList(),
                    LanguagePair(Language.EN, Language.ZH), call, onProgress = {
                        if (it.isNotBlank()) { streamed = true; call.cancel() }
                    }) }.isFailure)
                check(streamed)
                report("PASS: cancellation during streamed output rejects partial completion")
            }
            NativeCall(200).use { call ->
                val start = SystemClock.elapsedRealtime()
                check(runCatching { model.translate("Please explain the full history of technology in great detail.", emptyList(), LanguagePair(Language.EN, Language.ZH), call) }.isFailure)
                report("MT deadline_return_ms=${SystemClock.elapsedRealtime() - start}")
                check(SystemClock.elapsedRealtime() - start < 5000)
            }
            NativeCall(30_000).use { call ->
                val start = SystemClock.elapsedRealtime()
                // Keep all model calls on this worker; only the token crosses threads.
                val canceller = Thread { Thread.sleep(150); call.cancel() }.apply { start() }
                try {
                    val error = runCatching { model.translate("Please translate this sentence carefully. ".repeat(180),
                        emptyList(), LanguagePair(Language.EN, Language.ZH), call) }.exceptionOrNull()
                    check(error is IllegalStateException && error.message == "cancelled_or_deadline") { "Unexpected active-cancel result: $error" }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    report("MT active_cancel_return_ms=$elapsed")
                    check(elapsed < 5000) { "GPU cancellation did not return within the acceptance budget" }
                } finally { canceller.join() }
            }
            NativeCall(30_000).use { call ->
                check(runCatching { model.translate("We are testing subtitles on this phone.", emptyList(), LanguagePair(Language.EN, Language.ZH), call, maxTokens = 1) }.isFailure)
            }
            check(translate("Thank you.", Language.ZH).isNotBlank())
            NativeCall(30_000).use { call ->
                val translated = model.translate("Thank you.", listOf("We are testing local speech recognition and translation.",
                    "Good subtitles give you time to read."), LanguagePair(Language.EN, Language.ZH), call)
                check(translated.contains("谢") && !translated.contains("字幕") && !translated.contains("背景")) { "Translated context instead of source: $translated" }
                report("PASS: short source with background: $translated")
            }
            NativeCall(30_000).use { call ->
                model.translate("Thank you.", listOf("Long optional history. ".repeat(100)), LanguagePair(Language.EN, Language.ZH), call)
                check("history_tokens=0 " in model.timings()) { "Oversized history was sliced rather than omitted" }
            }
            report("PASS: pre-dispatch/active cancellation, deadline, output budget, reuse after abort")
        } finally { model.close() }
    }
    private suspend fun replay(name: String, target: Language, stopAtMs: Long? = null, repetitions: Int = 1,
                               recognizerId: String = if (name == "ja") multilingual.id else baseline.id,
                               translatorId: String = ModelSelection().translatorId) = withContext(Dispatchers.Main) {
        val samples = withContext(Dispatchers.IO) {
            val original = readWave(File(targetContext.filesDir, "fixtures/$name.wav"))
            // Test-only concatenation removes fixture padding, not production audio.
            val input = if (mode == "continuity") {
                val first = original.indexOfFirst { kotlin.math.abs(it) > 0.0003f }
                val last = original.indexOfLast { kotlin.math.abs(it) > 0.0003f }
                original.copyOfRange(first, last + 1)
            } else original
            // Explicit test silence lets ASR confirm the final tail after repeated speech.
            if (repetitions == 1) input else FloatArray(input.size * repetitions + 16_000 * 2) {
                if (it < input.size * repetitions) input[it % input.size] else 0f
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val overlay = SubtitleOverlay(targetContext)
        var latest = CaptureState()
        val reported = linkedMapOf<Long, com.captionglass.engine.Caption>()
        var firstSource = 0L
        var firstTranslation = 0L
        var began = 0L
        var stopping = false
        val selection = ModelSelection(LanguagePair(checkNotNull(Language.fromCode(name)), target), recognizerId, translatorId, backend)
        val session = CaptionSession(scope, selection) { state ->
            val elapsed = SystemClock.elapsedRealtime() - began
            if (mode == "reading" && state.confirmed > latest.confirmed)
                report("COMMIT $name sequence=${state.confirmed - 1} at_ms=$elapsed")
            if (mode == "continuity" && (state.stable != latest.stable || state.provisional != latest.provisional))
                report("SOURCE $name at_ms=$elapsed: ${state.stable}|${state.provisional}")
            latest = state
            if (firstSource == 0L && (state.stable.isNotBlank() || state.provisional.isNotBlank() || state.lines.isNotEmpty())) firstSource = SystemClock.elapsedRealtime() - began
            state.history.forEach { caption ->
                if (reported.put(caption.segment.key.sequence, caption) == null) {
                    if (caption.translation != null && firstTranslation == 0L) firstTranslation = SystemClock.elapsedRealtime() - began
                    report("REPLAY $name end_ms=${caption.segment.endMs} result_ms=${SystemClock.elapsedRealtime() - began}: ${caption.segment.source} => ${caption.translation ?: caption.untranslatedReason}")
                }
            }
            if (firstTranslation == 0L && state.lines.any { it.translation.isNotEmpty() }) firstTranslation = elapsed
            if (mode == "reading") overlay.render(state)

        }
        try {
            if (mode == "reading") overlay.show()
            session.start(targetContext, catalog.recognizer(selection).recognizerFiles(targetContext, selection.languages.source), catalog.translator(selection).file(targetContext, "model"), catalog.translator(selection).translationFormat)
            val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            report("REPLAY $name loaded_pss_kb=${memory.totalPss}")
            began = SystemClock.elapsedRealtime()
            var position = 0
            var lastThermalReport = 0L
            val power = targetContext.getSystemService(android.os.PowerManager::class.java)
            while (position < samples.size) {
                val end = minOf(position + 1600, samples.size)
                val due = began + end * 1000L / 16000
                delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                check(session.offer(PcmFrame(samples.copyOfRange(position, end), 16000, end * 1000L / 16000)))
                position = end
                val now = SystemClock.elapsedRealtime()
                if (mode == "continuity" && now - lastThermalReport >= 10_000) {
                    lastThermalReport = now
                    val headroom = if (android.os.Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(0) else Float.NaN
                    report("THERMAL elapsed_ms=${now - began} status=${power.currentThermalStatus} headroom=$headroom confirmed=${latest.confirmed} outcomes=${latest.outcomes}")
                }
                if (stopAtMs != null && end * 1000L / 16000 >= stopAtMs) {
                    stopping = true
                    session.stop()
                    break
                }
            }
            session.close()
            check(latest.confirmed == latest.outcomes && latest.confirmed > 0)
            check(latest.status in listOf(CaptureStatus.ENDED, CaptureStatus.STOPPED)) { "Unexpected stop: ${latest.status}" }
            if (stopAtMs == null) check(latest.history.any { it.translation != null }) { "No successful translation: ${latest.history}" }
            else check(latest.history.any { it.untranslatedReason == com.captionglass.engine.UntranslatedReason.STOPPED })
            val source = reported.values.filter { it.untranslatedReason != com.captionglass.engine.UntranslatedReason.SUPERSEDED }
                .joinToString(" ") { it.segment.source }
            report("REPLAY $name stop_at_ms=$stopAtMs audio_ms=${position * 1000L / 16000} elapsed_ms=${SystemClock.elapsedRealtime() - began} first_source_ms=$firstSource first_translation_ms=$firstTranslation confirmed=${latest.confirmed} outcomes=${latest.outcomes}")
            report("OUTCOMES ${reported.values.groupingBy { it.untranslatedReason?.name ?: "TRANSLATED" }.eachCount()}")
            if (mode == "continuity" && stopAtMs == null) {
                val anchor = if (name == "ja") "天気" else "good subtitles"
                val count = Regex(anchor, RegexOption.IGNORE_CASE).findAll(source).count()
                check(count == repetitions) { "Repeated speech coverage $count/$repetitions: $source" }
            }
            check(when (name) { "zh" -> source.contains("公园"); "ja" -> source.contains("天気"); else -> source.contains("subtitles", ignoreCase = true) }) { "Unexpected ASR: $source" }
            if (mode == "reading") {
                check(latest.lines.size == latest.history.count { it.untranslatedReason != com.captionglass.engine.UntranslatedReason.SUPERSEDED })
                readingViewportCheck(overlay.captions, latest)
                val retained = latest.lines
                report("READING HOLD: the overlay collapses after inactivity while session records remain")
                delay(30_000)
                check(latest.lines == retained && retained.isNotEmpty())
                check(!overlay.captions.isShown)
                report("PASS: idle overlay collapsed; completed records retained for 30 seconds")
            }

        } catch (e: Throwable) {
            session.stop()
            runCatching { session.close() }
            throw e
        } finally { overlay.close(); scope.cancel() }
    }

    private suspend fun readingViewportCheck(view: CaptionTranscriptView, state: CaptureState) {
        check(state.lines.size >= 4)
        // Reading-position checks cover the scrollable display; compact displays never scroll.
        view.display = CaptionDisplay.SCROLL
        val height = view.maximumHeight
        view.maximumHeight = (120 * targetContext.resources.displayMetrics.density).toInt()
        view.requestLayout()
        view.render(state.copy(lines = state.lines.take(3)))
        delay(150)
        check(view.scrollY > 0)
        fun gestureScroll(bottom: Boolean) {
            val time = SystemClock.uptimeMillis()
            android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, 20f, 20f, 0).let {
                view.dispatchTouchEvent(it); it.recycle()
            }
            view.scrollTo(0, if (bottom) view.getChildAt(0).height else 0)
            android.view.MotionEvent.obtain(time, time + 50, android.view.MotionEvent.ACTION_UP, 20f, 20f, 0).let {
                view.dispatchTouchEvent(it); it.recycle()
            }
        }
        gestureScroll(false)
        view.render(state)
        delay(150)
        check(view.scrollY == 0) { "New text moved the reader away from an earlier row" }
        gestureScroll(true)
        view.render(state.copy(lines = state.lines.take(3)))
        delay(150)
        view.render(state)
        delay(150)
        check(!view.canScrollVertically(1)) { "Returning to the bottom did not restore following" }
        check(view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        delay(400)
        val readPosition = view.scrollY
        check(view.canScrollVertically(1))
        view.render(state)
        delay(150)
        check(view.scrollY == readPosition) { "A streamed update reset accessibility scrolling" }
        val source = state.lines.last().segment.source
        view.render(state.copy(stable = "", provisional = source))
        delay(150)
        view.render(state.copy(stable = source, provisional = ""))
        delay(150)
        val content = view.getChildAt(0) as android.widget.LinearLayout
        val draft = content.getChildAt(content.childCount - 1) as android.widget.TextView
        val styled = draft.text as android.text.Spanned
        check(styled.getSpans(0, styled.length, android.text.style.ForegroundColorSpan::class.java)
            .all { styled.getSpanStart(it) >= source.length }) { "Unchanged text retained a stale provisional color" }
        view.maximumHeight = height
        view.requestLayout()
        view.render(state)
        report("PASS: scrolling back holds the reading position; returning to the bottom resumes following")
    }

    private suspend fun fallbackCheck(model: ModelSpec = baseline) = withContext(Dispatchers.Default) {
        val original = readWave(File(targetContext.filesDir, "fixtures/en.wav"))
        // Remove the fixture's padded silence: finish() must preserve the last spoken word.
        val samples = original.copyOf(original.size - 25_600)
        LocalRecognizer(model.recognizerFiles(targetContext, Language.EN)).use { asr ->
            val finals = mutableListOf<String>()
            var position = 0
            while (position < samples.size) {
                val end = minOf(position + 1600, samples.size)
                val upsampled = FloatArray((end - position) * 3) { samples[position + it / 3] }
                asr.accept(upsampled, 48_000).filter { it.final }.forEach { finals += it.text }
                position = end
            }
            asr.finish(48_000).filter { it.final }.forEach { finals += it.text }
            val text = finals.joinToString(" ")
            check(text.contains("translation", ignoreCase = true)) { "48 kHz/stop tail lost final word: $text" }
            report("PASS: 48 kHz resampler and stop-only tail: $text")
        }
    }


    /** Tiny isolated packs exercise the real install path without touching a user's models. */
    private suspend fun modelManagerChecks(network: Boolean) {
        while (ModelPack.busy) delay(20)
        val payload = ByteArray(32_768) { (it % 251).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val model = baseline.copy(id = "manager-check", files = listOf(ModelFile("tokens", "tokens.txt", payload.size.toLong(), digest,
            nemotron.files.single { it.role == "tokens" }.url)), manifest = "manager-check-v1")
        suspend fun install(bytes: ByteArray) {
            val job = withContext(Dispatchers.Main) {
                checkNotNull(ModelPack.install(targetContext, model, PackPhase.IMPORTING) { bytes.inputStream() })
            }
            job.join()
        }
        try {
            install(payload)
            check(ModelPack.ready(targetContext, model))
            val original = model.file(targetContext, "tokens").readBytes()
            install(payload.copyOf().apply { this[0] = 127 })
            check(ModelPack.state.value.failed && ModelPack.ready(targetContext, model))
            check(model.file(targetContext, "tokens").readBytes().contentEquals(original))
            install(payload.copyOf(100))
            check(ModelPack.state.value.failed && ModelPack.ready(targetContext, model))
            install(payload + byteArrayOf(1))
            check(ModelPack.state.value.failed && ModelPack.ready(targetContext, model))
            report("PASS: successful install, corrupt/truncated/oversized input rejected; previous pack retained")
            val job = withContext(Dispatchers.Main) {
                checkNotNull(ModelPack.install(targetContext, model, PackPhase.IMPORTING) {
                    object : java.io.ByteArrayInputStream(payload) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            Thread.sleep(40)
                            return super.read(buffer, offset, minOf(length, 256))
                        }
                    }
                }).also {
                    check(ModelPack.remove(targetContext, model) == null) { "Concurrent model deletion was accepted" }
                }
            }
            delay(100)
            withContext(Dispatchers.Main) { ModelPack.cancel() }
            job.join()
            check(!ModelPack.busy && ModelPack.ready(targetContext, model))
            check(!File(targetContext.filesDir, "models/manager-check-staging").exists())
            report("PASS: cancel cleans staging and preserves original; concurrent operation rejected")
            check(runCatching { ModelPack.verify(targetContext, model) { throw CancellationException() } }.isFailure)
            check(!ModelPack.ready(targetContext, model)) { "Cancelled recheck retained an unchecked marker" }
            withContext(Dispatchers.Main) { checkNotNull(ModelPack.recheck(targetContext, model)) }.join()
            check(ModelPack.ready(targetContext, model))
            report("PASS: cancelled verification requires a complete recheck before use")
            model.file(targetContext, "tokens").writeBytes(payload.copyOf().apply { this[0] = 127 })
            withContext(Dispatchers.Main) { checkNotNull(ModelPack.recheck(targetContext, model)) }.join()
            check(ModelPack.state.value.failed && !ModelPack.ready(targetContext, model))
            check(!File(ModelPack.directory(targetContext, model), "verified").exists())
            install(payload)
            val dir = ModelPack.directory(targetContext, model)
            val backup = File(targetContext.filesDir, "models/manager-check-backup")
            check(dir.renameTo(backup))
            check(ModelPack.ready(targetContext, model) && !backup.exists())
            report("PASS: recheck invalidates corrupt marker; interrupted activation restores backup")
            if (network) {
                val token = nemotron.files.single { it.role == "tokens" }
                val downloaded = model.copy(files = listOf(token), manifest = "manager-network-v1")
                withContext(Dispatchers.Main) { checkNotNull(ModelPack.download(targetContext, downloaded)) }.join()
                check(ModelPack.ready(targetContext, downloaded)) { "HTTPS model download: ${ModelPack.state.value}" }
                val missing = downloaded.copy(files = listOf(token.copy(url = token.url + ".missing")))
                withContext(Dispatchers.Main) { checkNotNull(ModelPack.download(targetContext, missing)) }.join()
                check(ModelPack.state.value.failed && ModelPack.ready(targetContext, downloaded))
                report("PASS: actual pinned HTTPS download verified; HTTP error keeps installed model")
            }
            withContext(Dispatchers.Main) { checkNotNull(ModelPack.remove(targetContext, model)) }.join()
            check(!ModelPack.ready(targetContext, model) && ModelPack.installed(targetContext, model).bytes == 0L)
            report("PASS: explicit model deletion clears only the isolated pack")
        } finally {
            withContext(Dispatchers.Main) { ModelPack.cancel() }
            while (ModelPack.busy) delay(20)
            listOf("", "-backup", "-staging", "-deleting").forEach { File(targetContext.filesDir, "models/manager-check$it").deleteRecursively() }
            withContext(Dispatchers.Main) { ModelPack.dismiss() }
        }
    }

    private fun readWave(file: File): FloatArray {
        val bytes = file.readBytes()
        check(bytes.size in 44..2_000_000)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        var offset = 12
        var format = false
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            check(size >= 0 && offset.toLong() + 8 + size <= bytes.size)
            if (id == "fmt ") {
                check(size >= 16 && buffer.getShort(offset + 8).toInt() == 1 && buffer.getShort(offset + 10).toInt() == 1)
                check(buffer.getInt(offset + 12) == 16000 && buffer.getShort(offset + 22).toInt() == 16)
                format = true
            }
            if (id == "data") {
                check(format && size % 2 == 0)
                return FloatArray(size / 2) { buffer.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + size + size % 2
        }
        error("Missing PCM data")
    }
}
