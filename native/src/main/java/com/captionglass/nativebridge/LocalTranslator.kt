package com.captionglass.nativebridge

import android.content.Context
import java.io.Closeable
import java.io.File
import com.captionglass.engine.LanguagePair
import com.captionglass.engine.TranslationFormat

/** The native context cannot be reused; its owner must finish the session and release it. */
class TranslationUnavailableException(message: String) : IllegalStateException(message)

class TranslationBackendException(message: String) : IllegalStateException(message)
class TranslationModelUnsupportedException(message: String) : IllegalStateException(message)

/** Stable persisted/JNI IDs; ASR always uses its own CPU configuration. */
enum class TranslationBackend(val id: String) {
    VULKAN("vulkan"), CPU("cpu"), HEXAGON("hexagon");
    companion object {
        fun fromId(id: String?) = entries.find { it.id == id }
    }
}

/** Created before dispatch; cancel never resets a flag or frees an in-flight callback. */
class NativeCall(timeoutMs: Long) : Closeable {
    internal var handle = NativeBindings.newCall(timeoutMs.also { require(it > 0) })
        private set
    @Synchronized fun cancel() { if (handle != 0L) NativeBindings.cancel(handle) }
    /** Only the worker that returned from JNI may close its token. */
    @Synchronized override fun close() {
        if (handle != 0L) NativeBindings.freeCall(handle)
        handle = 0
    }
}

/** One owner worker; close only after translation has returned, including cancellation. */
class LocalTranslator(context: Context, model: File, private val format: TranslationFormat, call: NativeCall,
                      backend: TranslationBackend) : Closeable {
    private var handle = NativeBindings.load(
        model.path.toByteArray(), format.prefix.toByteArray(), call.handle, backend.id,
        if (backend == TranslationBackend.HEXAGON) hexagonDirectory(context) else "")

    companion object {
        /** Driver/architecture discovery only. Opening a DSP session is deferred to the MT worker. */
        fun hexagonAvailable(): Boolean = NativeBindings.hexagonAvailable()

        private fun hexagonDirectory(context: Context): String {
            // APK-owned DSP code only. Refresh atomically after an app update; never download code.
            val directory = File(context.noBackupFilesDir, "hexagon").apply { mkdirs() }
            for (arch in listOf(73, 75, 79, 81)) {
                val name = "libggml-htp-v$arch.so"
                val pending = File(directory, "$name.tmp")
                try {
                    context.assets.open("hexagon/$name").use { input ->
                        pending.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                    }
                    check(pending.renameTo(File(directory, name)))
                } catch (e: Exception) {
                    pending.delete()
                    throw TranslationBackendException("hexagon_runtime_unavailable")
                }
            }
            return directory.absolutePath
        }
    }

    /** Developer acceptance timings; call on the same worker after translation returns. */
    fun timings(): String { check(handle != 0L); return NativeBindings.timings(handle) }

    fun translate(source: String, context: List<String>, pair: LanguagePair,
                  call: NativeCall, maxTokens: Int = 256, onProgress: ((String) -> Unit)? = null): String {
        require(maxTokens in 1..256)
        check(handle != 0L)
        val prompt = format.prompt(source, context, pair)
        val sampling = when (format) {
            TranslationFormat.HY_MT2 -> 0
            TranslationFormat.MURASAKI -> 2
            else -> 1
        }
        return format.result(NativeBindings.translate(handle, prompt.prefix.toByteArray(), prompt.text.toByteArray(),
            prompt.suffix.toByteArray(), prompt.background.toByteArray(), sampling,
            call.handle, maxTokens, onProgress?.let { progress -> { bytes: ByteArray ->
                // A tokenizer piece may end halfway through a UTF-8 character.
                val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                    catch (_: java.nio.charset.CharacterCodingException) { null }
                text?.let { progress(format.preview(it)) }
            } }).decodeToString())
    }
    override fun close() { if (handle != 0L) NativeBindings.unload(handle); handle = 0 }
}

internal object NativeBindings {
    init { System.loadLibrary("captionglass") }
    external fun newCall(timeoutMs: Long): Long
    external fun cancel(call: Long)
    external fun freeCall(call: Long)
    external fun hexagonAvailable(): Boolean
    external fun load(path: ByteArray, prefix: ByteArray, call: Long, backend: String, hexagonDirectory: String): Long
    external fun unload(model: Long)
    external fun timings(model: Long): String
    external fun translate(model: Long, prefix: ByteArray, prompt: ByteArray, suffix: ByteArray, background: ByteArray, sampling: Int, call: Long, maxTokens: Int, progress: ((ByteArray) -> Unit)?): ByteArray
}
