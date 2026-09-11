package com.captionglass.engine

data class SourceUpdate(
    val stable: String,
    val provisional: String,
    val committed: String? = null,
    val correctionRequired: Boolean = false,
    val invalidatesCommitted: Boolean = false,
    val retainedFrom: Long = 0,
    val committedEnd: Long = 0,
    val pendingStable: String = "",
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
    private var baseOffset = 0L
    private val committedEnds = ArrayDeque<Long>()

    fun update(windowText: String, revision: Int, atMs: Long, isFinal: Boolean = false, textOffset: Long = 0): SourceUpdate {
        require(revision >= 0 && atMs >= 0 && textOffset >= 0)
        // A duplicate callback must not count as another agreement or submit a second segment.
        if (revision <= lastRevision || finished) return lastUpdate.copy(committed = null, invalidatesCommitted = false)
        require(atMs >= lastTimeMs)
        lastRevision = revision
        lastTimeMs = atMs
        // Only retire committed text which the recognizer can no longer revise.
        val retireThrough = committedEnds.lastOrNull { it <= textOffset } ?: baseOffset
        val retired = (retireThrough - baseOffset).toInt()
        while (committedEnds.firstOrNull()?.let { it <= retireThrough } == true) committedEnds.removeFirst()
        previous = previous.drop(retired)
        committedPrefix = committedPrefix.drop(retired)
        baseOffset += retired
        check(textOffset <= baseOffset + previous.length) { "Missing transcript window" }
        val text = if (textOffset < baseOffset) windowText.drop((baseOffset - textOffset).toInt())
            else previous.take((textOffset - baseOffset).toInt()) + windowText
        if (pendingSinceMs == null && text.isNotBlank()) pendingSinceMs = atMs

        var stable = if (isFinal) text else previous.commonPrefixWith(text)
        // Avoid treating a partial Latin word as stable ("can" -> "can't", for example).
        if (!isFinal && stable.length < text.length && stable.lastOrNull()?.isAsciiWord() == true) {
            stable = stable.dropLastWhile { it.isAsciiWord() || it == '\'' || it == '’' }
        }
        val invalidates = !correctionRequired && !text.startsWith(committedPrefix)
        correctionRequired = correctionRequired || invalidates
        if (invalidates) committedEnds.clear()
        val repairing = correctionRequired
        val remainder = if (repairing) stable else stable.removePrefix(committedPrefix)
        val waited = atMs - (pendingSinceMs ?: atMs)
        val boundary = semanticBoundary(remainder)
        val commit = when {
            // A stable closed sentence can repair a revision without waiting for a long utterance to end.
            correctionRequired && isFinal -> text.trim().ifBlank { null }
            correctionRequired && boundary > 0 -> remainder.take(boundary).trim()
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
            committedPrefix = if (correctionRequired) if (isFinal) text else remainder.take(boundary)
                else committedPrefix + remainder.take(if (isFinal || boundary == 0) remainder.length else boundary)
            pendingSinceMs = atMs
            correctionRequired = false
            committedEnds.addLast(baseOffset + committedPrefix.length)
        }
        previous = text
        finished = isFinal
        // A late full stop completes the previous source; it is not a new translation unit.
        val semanticCommit = commit?.takeIf { it.any(Char::isLetterOrDigit) }
        return SourceUpdate(stable, text.removePrefix(stable), semanticCommit, repairing, invalidates,
            baseOffset, baseOffset + committedPrefix.length, stable.removePrefix(committedPrefix))
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
