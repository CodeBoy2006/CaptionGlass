package com.captionglass.engine

data class SourceUpdate(
    val stable: String,
    val provisional: String,
    val committed: String? = null,
    val correctionRequired: Boolean = false,
)

/** One utterance per instance. Feed cumulative hypotheses from distinct new-audio revisions. */
class SourceGate(private val maxWaitMs: Long = 5_500) {
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
        if (revision <= lastRevision || finished) return lastUpdate.copy(committed = null)
        require(atMs >= lastTimeMs)
        lastRevision = revision
        lastTimeMs = atMs
        if (pendingSinceMs == null && text.isNotBlank()) pendingSinceMs = atMs

        var stable = if (isFinal) text else previous.commonPrefixWith(text)
        // Avoid treating a partial Latin word as stable ("can" -> "can't", for example).
        if (!isFinal && stable.length < text.length && stable.lastOrNull()?.isAsciiWord() == true) {
            stable = stable.dropLastWhile { it.isAsciiWord() || it == '\'' || it == '’' }
        }
        correctionRequired = correctionRequired || !text.startsWith(committedPrefix)
        val remainder = stable.removePrefix(committedPrefix)
        val waited = atMs - (pendingSinceMs ?: atMs)
        // ponytail: terminal punctuation/timeout heuristic; replace with language-calibrated
        // clause rules before shipping real ASR (abbreviations and Japanese need separate cases).
        val ready = isFinal || remainder.trimEnd().lastOrNull() in listOf('.', '!', '?', '。', '！', '？') ||
            waited >= maxWaitMs
        val commit = if (!correctionRequired && stable.startsWith(committedPrefix) &&
            ready && remainder.isNotBlank()
        ) remainder.trim() else null
        if (commit != null) {
            committedPrefix = stable
            pendingSinceMs = atMs
        }
        previous = text
        finished = isFinal
        return SourceUpdate(stable, text.removePrefix(stable), commit, correctionRequired)
            .also { lastUpdate = it }
    }

    private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
