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
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit, model-dependent acceptance; never runs as part of ordinary CI. */
class DeviceChecks : Instrumentation() {
    private var mode = "all"
    private val catalog by lazy { ModelCatalog(targetContext) }
    private val baseline get() = catalog.recognizer(ModelSelection())
    private val multilingual get() = catalog.recognizers.single { Language.JA in it.languages }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode") ?: "all"; start() }
    private fun report(text: String) { sendStatus(0, Bundle().apply { putString("stream", "$text\n") }) }
    override fun onStart() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            check(mode in setOf("all", "catalog", "verify", "native", "multilingual", "replay", "fallback", "latency", "asr-ja"))
            report("Verifying pinned model files")
            catalogChecks()
            if (mode == "catalog") {
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: catalog\n") }); return
            }
            catalog.models.forEach { ModelPack.verify(targetContext, it) }
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
                if (mode == "asr-ja") japaneseAsrCheck()
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
                if (mode == "latency") {
                    replay("en", Language.ZH, repetitions = 4)
                    replay("zh", Language.EN, repetitions = 4)
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: $mode\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${e.stackTraceToString()}\n") })
        } finally {
            runOnMainSync { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
    /** Diagnostic uses the same real-time PCM without SourceGate, MT, or UI state. */
    private suspend fun japaneseAsrCheck() = withContext(Dispatchers.Default) {
        val samples = readWave(File(targetContext.filesDir, "fixtures/ja.wav"))
        LocalRecognizer(multilingual.recognizerFiles(targetContext)).use { asr ->
            val began = SystemClock.elapsedRealtime()
            var previous = ""
            for (position in samples.indices step 1600) {
                val end = minOf(position + 1600, samples.size)
                delay((began + end * 1000L / 16000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                asr.accept(samples.copyOfRange(position, end), 16000).forEach {
                    if (it.text != previous || it.final) report("ASR ja at_ms=${end * 1000L / 16000} final=${it.final}: ${it.text}")
                    previous = it.text
                }
            }
            asr.finish(16000).forEach { report("ASR ja tail final=${it.final}: ${it.text}") }
        }
    }
    private fun catalogChecks() {
        val initial = ModelSelection()
        check(catalog.valid(initial))
        val japanese = catalog.withLanguages(initial, LanguagePair(Language.JA, Language.ZH))
        check(japanese.recognizerId == multilingual.id && catalog.valid(japanese))
        check(!catalog.valid(japanese.copy(recognizerId = baseline.id)))
        check(Language.FR !in catalog.sources && Language.FR in catalog.translator.languages)
        check(catalog.withLanguages(japanese, japanese.languages.swapped()).languages.target == Language.JA)
        check(catalog.required(initial).last() == catalog.required(japanese).last())
        check(baseline.file(targetContext, "tokens") != multilingual.file(targetContext, "tokens"))
        check(runCatching { catalog.withLanguages(initial, LanguagePair(Language.FR, Language.ZH)) }.isFailure)
        report("PASS: catalog pins, source/target capabilities, compatible selection, shared translation model")
    }
    private suspend fun nativeChecks() = withContext(Dispatchers.Default) {
        val began = SystemClock.elapsedRealtime()
        val load = NativeCall(120_000)
        val model = try { LocalTranslator(catalog.translator.file(targetContext, "model"), load) } finally { load.close() }
        report("MT load_ms=${SystemClock.elapsedRealtime() - began}")
        try {
            fun translate(source: String, target: Language): String {
                val call = NativeCall(30_000)
                val start = SystemClock.elapsedRealtime()
                return try { model.translate(source, emptyList(), target, call).also {
                    report("MT to-${target.code} ms=${SystemClock.elapsedRealtime() - start}: $source => $it")
                } } finally { call.close() }
            }
            check(translate("Good subtitles give you time to read.", Language.ZH).any { it in '\u4e00'..'\u9fff' })
            check(translate("今天天气很好，我们去公园散步。", Language.EN).contains("park", ignoreCase = true))
            check(translate("今日はいい天気です。", Language.ZH).contains("天气"))
            check(translate("今日はいい天気です。", Language.EN).contains("weather", ignoreCase = true))
            check(translate("Thank you very much.", Language.JA).any { it in '\u3040'..'\u30ff' })
            check(translate("非常感谢。", Language.JA).any { it in '\u3040'..'\u30ff' })
            check(translate("Thank you very much.", Language.FR).contains("merci", ignoreCase = true))
            check(translate("Thank you very much.", Language.KO).any { it in '\uac00'..'\ud7af' })
            check(translate("Thank you very much.", Language.AR).any { it in '\u0600'..'\u06ff' })
            NativeCall(10_000).use { call ->
                call.cancel()
                val start = SystemClock.elapsedRealtime()
                check(runCatching { model.translate("Hello", emptyList(), Language.ZH, call) }.isFailure)
                check(SystemClock.elapsedRealtime() - start < 1000)
            }
            NativeCall(200).use { call ->
                val start = SystemClock.elapsedRealtime()
                check(runCatching { model.translate("Please explain the full history of technology in great detail.", emptyList(), Language.ZH, call) }.isFailure)
                report("MT deadline_return_ms=${SystemClock.elapsedRealtime() - start}")
                check(SystemClock.elapsedRealtime() - start < 5000)
            }
            NativeCall(30_000).use { call ->
                check(runCatching { model.translate("We are testing subtitles on this phone.", emptyList(), Language.ZH, call, maxTokens = 1) }.isFailure)
            }
            check(translate("Thank you.", Language.ZH).isNotBlank())
            NativeCall(30_000).use { call ->
                val translated = model.translate("Thank you.", listOf("We are testing local speech recognition and translation.",
                    "Good subtitles give you time to read."), Language.ZH, call)
                check(translated.contains("谢") && !translated.contains("字幕") && !translated.contains("背景")) { "Translated context instead of source: $translated" }
                report("PASS: short source with background: $translated")
            }
            report("PASS: pre-dispatch cancellation, deadline, output budget, reuse after abort")
        } finally { model.close() }
    }
    private suspend fun replay(name: String, target: Language, stopAtMs: Long? = null, repetitions: Int = 1,
                               recognizerId: String = if (name == "ja") multilingual.id else baseline.id) = withContext(Dispatchers.Main) {
        val samples = withContext(Dispatchers.IO) {
            val input = readWave(File(targetContext.filesDir, "fixtures/$name.wav"))
            // Explicit test silence lets all reading pages finish after the repeated speech.
            if (repetitions == 1) input else FloatArray(input.size * repetitions + 16_000 * 45) {
                if (it < input.size * repetitions) input[it % input.size] else 0f
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val overlay = SubtitleOverlay(targetContext)
        var latest = CaptureState()
        val reported = mutableSetOf<Long>()
        val readyAt = mutableMapOf<Long, Long>()
        val displayed = mutableSetOf<Pair<Long, Int>>()
        val displayWaits = mutableListOf<Long>()
        var firstSource = 0L
        var firstTranslation = 0L
        var began = 0L
        var stopping = false
        val selection = ModelSelection(LanguagePair(checkNotNull(Language.fromCode(name)), target), recognizerId)
        val session = CaptionSession(scope, selection, overlay::pages) { state ->
            if (stopping) check(state.page == null) { "Stop restored a subtitle" }
            val elapsed = SystemClock.elapsedRealtime() - began
            if (mode == "latency" && state.confirmed > latest.confirmed)
                report("COMMIT $name sequence=${state.confirmed - 1} at_ms=$elapsed")
            latest = state
            if (firstSource == 0L && state.stable.isNotBlank()) firstSource = SystemClock.elapsedRealtime() - began
            state.history.forEach { caption ->
                if (reported.add(caption.segment.key.sequence)) {
                    readyAt[caption.segment.key.sequence] = elapsed
                    if (caption.translation != null && firstTranslation == 0L) firstTranslation = SystemClock.elapsedRealtime() - began
                    report("REPLAY $name end_ms=${caption.segment.endMs} result_ms=${SystemClock.elapsedRealtime() - began}: ${caption.segment.source} => ${caption.translation ?: caption.untranslatedReason}")
                }
            }
            if (mode == "latency") {
                overlay.render(state)
                state.page?.let { page ->
                    val sequence = page.caption.segment.key.sequence
                    if (displayed.add(sequence to page.index)) {
                        val wait = elapsed - checkNotNull(readyAt[sequence])
                        displayWaits += wait
                        report("DISPLAY $name sequence=$sequence page=${page.index + 1}/${page.total} at_ms=$elapsed ready_wait_ms=$wait")
                    }
                }
            }
        }
        try {
            if (mode == "latency") overlay.show()
            session.start(catalog.recognizer(selection).recognizerFiles(targetContext), catalog.translator.file(targetContext, "model"))
            val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            report("REPLAY $name loaded_pss_kb=${memory.totalPss}")
            began = SystemClock.elapsedRealtime()
            var position = 0
            while (position < samples.size) {
                val end = minOf(position + 1600, samples.size)
                val due = began + end * 1000L / 16000
                delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                check(session.offer(PcmFrame(samples.copyOfRange(position, end), 16000, end * 1000L / 16000)))
                position = end
                if (stopAtMs != null && end * 1000L / 16000 >= stopAtMs) {
                    stopping = true
                    session.stop()
                    break
                }
            }
            if (mode == "latency") check(latest.page == null) { "Reading has not drained after the trailing test silence" }
            session.close()
            check(latest.confirmed == latest.outcomes && latest.confirmed > 0)
            if (stopAtMs == null) check(latest.history.any { it.translation != null }) { "No successful translation: ${latest.history}" }
            else check(latest.history.any { it.untranslatedReason == com.captionglass.engine.UntranslatedReason.STOPPED })
            val source = latest.history.joinToString(" ") { it.segment.source }
            check(when (name) { "zh" -> source.contains("公园"); "ja" -> source.contains("天気"); else -> source.contains("subtitles", ignoreCase = true) }) { "Unexpected ASR: $source" }
            report("REPLAY $name stop_at_ms=$stopAtMs audio_ms=${position * 1000L / 16000} elapsed_ms=${SystemClock.elapsedRealtime() - began} first_source_ms=$firstSource first_translation_ms=$firstTranslation confirmed=${latest.confirmed} outcomes=${latest.outcomes}")
            if (mode == "latency") {
                check(latest.history.all { it.translation != null }) { "Untranslated benchmark segment: ${latest.history}" }
                val expectedPages = latest.history.sumOf { maxOf(overlay.pages(it.segment.source, false).size,
                    overlay.pages(checkNotNull(it.translation), true).size) }
                check(displayed.size == expectedPages && latest.readingBehind == 0) { "Reading coverage ${displayed.size}/$expectedPages, overflow=${latest.readingBehind}" }
                report("LATENCY $name pages=${displayed.size} ready_wait_mean_ms=${displayWaits.average().toLong()} ready_wait_max_ms=${displayWaits.maxOrNull()}")
            }
            val longText = "Long subtitles must be paged completely. 很长的字幕需要完整分页。".repeat(30)
            check(overlay.pages(longText, true).joinToString("") == longText)
        } catch (e: Throwable) {
            session.stop()
            runCatching { session.close() }
            throw e
        } finally { overlay.close(); scope.cancel() }
    }

    private suspend fun fallbackCheck() = withContext(Dispatchers.Default) {
        val original = readWave(File(targetContext.filesDir, "fixtures/en.wav"))
        // Remove the fixture's padded silence: finish() must preserve the last spoken word.
        val samples = original.copyOf(original.size - 25_600)
        LocalRecognizer(baseline.recognizerFiles(targetContext)).use { asr ->
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
