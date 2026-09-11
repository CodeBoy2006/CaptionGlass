package com.captionglass.engine

class SpeechWindowLimit : IllegalStateException("Continuous speech exceeds this model’s input window")

/** VAD suggests boundaries only: every sample, including quiet speech, reaches ASR. */
class SpeechWindow {
    private val samples = FloatArray(16_000 * 14)
    private var size = 0
    private var silence = 0

    fun accept(frame: FloatArray, speech: Boolean): FloatArray? {
        require(frame.size <= 512 && size + frame.size <= samples.size)
        frame.copyInto(samples, size)
        size += frame.size
        silence = if (speech) 0 else silence + frame.size
        if (silence >= 19_200) return finish()
        // Never label a forcibly cut word/negation as a completed utterance.
        if (size >= samples.size - 512) throw SpeechWindowLimit()
        return null
    }

    fun finish(): FloatArray? {
        val result = if (size == 0) null else samples.copyOf(size)
        size = 0; silence = 0
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
