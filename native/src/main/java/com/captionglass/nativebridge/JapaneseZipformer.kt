package com.captionglass.nativebridge

import com.captionglass.engine.decodeCtc
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Matched LiteRT raw-waveform export; CPU only, with the upstream padding and mask recipe. */
internal class JapaneseZipformer(files: RecognizerFiles) : Closeable {
    private val interpreter = Interpreter(files.paths.getValue("model"), Interpreter.Options().setNumThreads(1).setUseXNNPACK(true))
    private val vocabulary: List<String>
    private val inputs: Array<Any>
    private val output = ByteBuffer.allocateDirect(799 * 3004 * 4).order(ByteOrder.nativeOrder())

    init {
        try {
            val vocab = JSONObject(files.paths.getValue("vocab").readText())
            vocabulary = MutableList(vocab.length()) { "" }.also { list ->
                vocab.keys().forEach { token -> list[vocab.getInt(token)] = token }
            }
            check(vocabulary.size == 3004 && vocabulary.all { it.isNotEmpty() })
            check(interpreter.inputTensorCount == 5 && interpreter.outputTensorCount == 1)
            check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 799, 3004)))
            inputs = Array(5) { i ->
                val shape = interpreter.getInputTensor(i).shape()
                check(shape.size == 2 && shape[0] == 1 && shape[1] in listOf(256000, 799, 400, 200, 100))
                ByteBuffer.allocateDirect(shape[1] * 4).order(ByteOrder.nativeOrder())
            }
        } catch (e: Throwable) { interpreter.close(); throw e }
    }

    fun decode(samples: FloatArray): String {
        require(samples.size <= 240_000)
        var valid = samples.size + 16_000
        for ((kernel, stride) in listOf(10 to 5, 3 to 2, 3 to 2, 3 to 2, 3 to 2, 2 to 2, 2 to 2))
            valid = (valid - kernel) / stride + 1
        inputs.forEach { value ->
            val buffer = value as ByteBuffer
            val count = buffer.capacity() / 4
            buffer.clear()
            if (count == 256_000) repeat(count) { i -> buffer.putFloat(if (i in 8_000 until samples.size + 8_000) samples[i - 8_000] else 0f) }
            else {
                val stride = (799 + count - 1) / count
                repeat(count) { i -> buffer.putFloat(if (i * stride < valid) 0f else -1000f) }
            }
            buffer.rewind()
        }
        output.clear()
        interpreter.runForMultipleInputsOutputs(inputs, mutableMapOf<Int, Any>(0 to output))
        output.rewind()
        val logits = output.asFloatBuffer()
        val ids = IntArray(valid) { frame ->
            var best = 0
            for (token in 1 until 3004) if (logits[frame * 3004 + token] > logits[frame * 3004 + best]) best = token
            best
        }
        return decodeCtc(ids, vocabulary)
    }

    override fun close() = interpreter.close()
}
