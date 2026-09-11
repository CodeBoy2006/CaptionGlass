package com.captionglass.nativebridge

import java.io.Closeable
import java.io.File
import com.captionglass.engine.Language

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
class LocalTranslator(model: File, call: NativeCall) : Closeable {
    private var handle = NativeBindings.load(
        model.path.toByteArray(), call.handle)

    /** Developer acceptance timings; call on the same worker after translation returns. */
    fun timings(): String { check(handle != 0L); return NativeBindings.timings(handle) }

    fun translate(source: String, context: List<String>, target: Language,
                  call: NativeCall, maxTokens: Int = 256): String {
        require(source.isNotBlank() && source.length <= 8_192 && maxTokens in 1..256)
        require(source.any(Char::isLetterOrDigit)) { "No translatable source content" }
        check(handle != 0L)
        val language = target.promptName
        val instruction = if (context.isEmpty())
            "Translate the following text into $language. Note that you should only output the translated result without any additional explanation:\n\n$source"
        else "Translate only [Source Text] into $language. Use the background only for context. " +
            "Output only the translated result without any additional explanation.\n\n[Source Text]\n$source"
        return NativeBindings.translate(handle, instruction.toByteArray(), context.joinToString("\n").toByteArray(), call.handle, maxTokens).decodeToString()
    }
    override fun close() { if (handle != 0L) NativeBindings.unload(handle); handle = 0 }
}

internal object NativeBindings {
    init { System.loadLibrary("captionglass") }
    external fun newCall(timeoutMs: Long): Long
    external fun cancel(call: Long)
    external fun freeCall(call: Long)
    external fun load(path: ByteArray, call: Long): Long
    external fun unload(model: Long)
    external fun timings(model: Long): String
    external fun translate(model: Long, prompt: ByteArray, background: ByteArray, call: Long, maxTokens: Int): ByteArray
}
