package com.captionglass.engine

/** Trusted template pieces are tokenized separately from speech and history. */
data class TranslationPrompt(val prefix: String, val text: String, val suffix: String, val background: String = "")

enum class TranslationFormat(val id: String) {
    HY_MT2("hy-mt2"), STREAM_REVISE("stream-revise"), MILMMT("milmmt"), MURASAKI("murasaki");

    /** Independent of speech and language direction; prepared once with the model. */
    val prefix: String get() = when (this) {
        HY_MT2, STREAM_REVISE -> HY_PREFIX
        MILMMT -> ""
        MURASAKI -> "<|im_start|>system\n将日文翻译成简体中文。只输出完整译文，不要分析、解释或补充原文没有的信息。<|im_end|>\n<|im_start|>user\n"
    }

    fun prompt(source: String, context: List<String>, pair: LanguagePair): TranslationPrompt {
        require(source.isNotBlank() && source.length <= 8_192 && source.any(Char::isLetterOrDigit))
        val target = pair.target.promptName
        // One complete preceding utterance limits repeated prefill without cutting the current source.
        val history = context.lastOrNull().orEmpty()
        return when (this) {
            HY_MT2 -> TranslationPrompt(prefix,
                if (history.isEmpty()) "Translate the following text into $target. Note that you should only output the translated result without any additional explanation:\n\n$source"
                else "Translate only [Source Text] into $target. Use the background only for context. Output only the translated result without any additional explanation.\n\n[Source Text]\n$source",
                HY_SUFFIX, history)
            STREAM_REVISE -> {
                require(pair.source in CORE && pair.target in CORE)
                // Confirmed segments use the author's cold-start/source-history layout. No provisional MT is published.
                TranslationPrompt(prefix,
                    if (history.isEmpty()) "Translate the following text from ${pair.source.promptName} into $target. Note that you should only output the translated result without any additional explanation:\n\n$source"
                    else "Please translate the following text from ${pair.source.promptName} into $target, taking the provided background information into consideration.\n\n[Source Text]\n$source",
                    HY_SUFFIX, if (history.isEmpty()) "" else "Recent source utterances:\n$history")
            }
            MILMMT -> TranslationPrompt(prefix, "Translate this from ${pair.source.milName} to ${pair.target.milName}:\n${pair.source.milName}: $source\n${pair.target.milName}:", "")
            MURASAKI -> {
                require(pair == LanguagePair(Language.JA, Language.ZH))
                // Begin the answer explicitly; an empty closed think block alone can resume analysis.
                TranslationPrompt(prefix, "请翻译：\n$source", "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n译文：")
            }
        }
    }

    fun result(text: String): String {
        var result = text.trim()
        if (this == MURASAKI && result.startsWith("<think>")) {
            check("</think>" in result) { "Incomplete reasoning output" }
            result = result.substringAfter("</think>").trim()
        }
        check(result.isNotBlank() && "<think>" !in result && "</think>" !in result) { "Empty or incomplete translation" }
        return result
    }

    fun preview(text: String): String {
        var result = text.trimStart()
        if ("<think>".startsWith(result)) return ""
        if (result.startsWith("<think>")) {
            if (this != MURASAKI || "</think>" !in result) return ""
            result = result.substringAfter("</think>").trimStart()
        }
        return result.substringBefore("<think>").substringBefore("</think>").trimEnd()
    }

    companion object {
        private const val HY_PREFIX = "<｜hy_begin▁of▁sentence｜><｜hy_User｜>"
        private const val HY_SUFFIX = "<｜hy_Assistant｜>"
        private val CORE = setOf(Language.ZH, Language.EN, Language.JA)
    }
}

private val Language.milName: String get() = when (this) {
    Language.ZH -> "Chinese (Simplified)"
    Language.ZH_HANT -> "Chinese (Traditional)"
    Language.TL -> "Tagalog"
    else -> promptName
}
