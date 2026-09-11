package com.captionglass.nativebridge

import java.io.Closeable
import java.io.File
import com.captionglass.engine.LanguagePair
import com.captionglass.engine.TranslationFormat

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
class LocalTranslator(model: File, private val format: TranslationFormat, call: NativeCall) : Closeable {
    private var handle = NativeBindings.load(
        model.path.toByteArray(), call.handle)

    /** Developer acceptance timings; call on the same worker after translation returns. */
    fun timings(): String { check(handle != 0L); return NativeBindings.timings(handle) }

    fun translate(source: String, context: List<String>, pair: LanguagePair,
                  call: NativeCall, maxTokens: Int = 256): String {
        require(maxTokens in 1..256)
        check(handle != 0L)
        val prompt = format.prompt(source, context, pair)
        return format.result(NativeBindings.translate(handle, prompt.prefix.toByteArray(), prompt.text.toByteArray(),
            prompt.suffix.toByteArray(), prompt.background.toByteArray(), format != TranslationFormat.HY_MT2,
            call.handle, maxTokens).decodeToString())
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
    external fun translate(model: Long, prefix: ByteArray, prompt: ByteArray, suffix: ByteArray, background: ByteArray, greedy: Boolean, call: Long, maxTokens: Int): ByteArray
}
