package com.captionglass.engine

data class AudioWindow(val samples: FloatArray, val start: Long, val final: Boolean)

/** VAD suggests boundaries only: every sample, including quiet speech, reaches ASR. */
class SpeechWindow {
    private val samples = FloatArray(16_000 * 8)
    private var size = 0
    private var silence = 0
    private var start = 0L
    private var decodedEnd = 0L
    private var previewed = false

    fun accept(frame: FloatArray, speech: Boolean): AudioWindow? {
        require(frame.size <= 512 && size + frame.size <= samples.size)
        frame.copyInto(samples, size)
        size += frame.size
        silence = if (speech) 0 else silence + frame.size
        if (silence >= 19_200) return finish()
        if (size >= samples.size - 512) {
            val result = snapshot(false)
            // The next decode sees the seam again with two seconds of acoustic context.
            val retained = 16_000 * 2
            samples.copyInto(samples, 0, size - retained, size)
            start += size - retained
            size = retained
            previewed = true
            return result
        }
        if (!previewed && size >= 16_000 * 4) {
            previewed = true
            return snapshot(false)
        }
        return null
    }

    private fun snapshot(final: Boolean): AudioWindow {
        val result = AudioWindow(if (start + size > decodedEnd) samples.copyOf(size) else FloatArray(0), start, final)
        decodedEnd = start + size
        return result
    }

    fun finish(): AudioWindow? {
        if (size == 0) return null
        val result = snapshot(true)
        start += size
        size = 0; silence = 0; previewed = false
        return result
    }
}

/** CTC repeats are separated by blank, not by the preceding emitted token. */
fun decodeCtc(ids: IntArray, vocabulary: List<String>): String {
    var previous = -1
    return buildString {
        ids.forEach { id ->
            require(id in vocabulary.indices)
            if (id != 0 && id != previous) append(vocabulary[id])
            previous = id
        }
    }.replace('▁', ' ').trim()
}
