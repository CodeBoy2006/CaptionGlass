package com.captionglass.engine

data class SourceUpdate(
    val stable: String,
    val provisional: String,
    val committed: String? = null,
    val correctionRequired: Boolean = false,
    val invalidatesCommitted: Boolean = false,
)

/** One utterance per instance. Feed cumulative hypotheses from distinct new-audio revisions. */
class SourceGate(private val maxWaitMs: Long = 5_500, private val language: Language = Language.EN) {
    init { require(maxWaitMs > 0) }

    private var previous = ""
    private var committedPrefix = ""
    private var lastRevision = -1
    private var lastTimeMs = 0L
    private var pendingSinceMs: Long? = null
    private var finished = false
    private var correctionRequired = false
    private var lastUpdate = SourceUpdate("", "")

    fun update(text: String, revision: Int, atMs: Long, isFinal: Boolean = false): SourceUpdate {
        require(text.length <= 8_192 && revision >= 0 && atMs >= 0)
        // A duplicate callback must not count as another agreement or submit a second segment.
        if (revision <= lastRevision || finished) return lastUpdate.copy(committed = null, invalidatesCommitted = false)
        require(atMs >= lastTimeMs)
        lastRevision = revision
        lastTimeMs = atMs
        if (pendingSinceMs == null && text.isNotBlank()) pendingSinceMs = atMs

        var stable = if (isFinal) text else previous.commonPrefixWith(text)
        // Avoid treating a partial Latin word as stable ("can" -> "can't", for example).
        if (!isFinal && stable.length < text.length && stable.lastOrNull()?.isAsciiWord() == true) {
            stable = stable.dropLastWhile { it.isAsciiWord() || it == '\'' || it == '’' }
        }
        val invalidates = !correctionRequired && !text.startsWith(committedPrefix)
        correctionRequired = correctionRequired || invalidates
        val remainder = stable.removePrefix(committedPrefix)
        val waited = atMs - (pendingSinceMs ?: atMs)
        val boundary = semanticBoundary(remainder)
        val commit = when {
            // Retract old segments immediately, then commit one corrected endpoint result.
            correctionRequired && isFinal -> text.trim().ifBlank { null }
            correctionRequired || !stable.startsWith(committedPrefix) -> null
            isFinal -> remainder.trim().ifBlank { null }
            boundary > 0 -> remainder.take(boundary).trim()
            // Japanese predicates and negation often arrive last. Until separately calibrated,
            // commit at sentence punctuation or the acoustic endpoint, never a timed prefix.
            language == Language.JA -> null
            waited < maxWaitMs + 1_500 && Regex("(?i).*\\b(and|or|but|because|if|when|to|the|a|an|will|would|can|could|not|don't|doesn't|isn't)\\s*$")
                .matches(remainder) -> null
            waited >= maxWaitMs -> remainder.trim().ifBlank { null }
            else -> null
        }
        if (commit != null) {
            committedPrefix = if (correctionRequired && isFinal) text
                else committedPrefix + remainder.take(if (isFinal || boundary == 0) remainder.length else boundary)
            pendingSinceMs = atMs
        }
        previous = text
        finished = isFinal
        // A late full stop completes the previous source; it is not a new translation unit.
        val semanticCommit = commit?.takeIf { it.any(Char::isLetterOrDigit) }
        return SourceUpdate(stable, text.removePrefix(stable), semanticCommit, correctionRequired, invalidates)
            .also { lastUpdate = it }
    }

    private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun semanticBoundary(text: String): Int {
        // ponytail: conservative punctuation + maximum wait; learned boundaries are M2.
        val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "e.g", "i.e")
        for (i in text.indices.reversed()) {
            val c = text[i]
            if (c in "!?。！？；;") return i + 1
            if (c != '.') continue
            if (text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true) continue
            val word = text.take(i).takeLastWhile { it.isLetter() || it == '.' }.lowercase()
            if (word in abbreviations || word.length == 1 || '.' in word) continue
            return i + 1
        }
        return 0
    }
}
