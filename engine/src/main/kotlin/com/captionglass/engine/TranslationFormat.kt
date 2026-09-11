package com.captionglass.engine

/** Trusted template pieces are tokenized separately from speech and history. */
data class TranslationPrompt(val prefix: String, val text: String, val suffix: String, val background: String = "")

enum class TranslationFormat(val id: String) {
    HY_MT2("hy-mt2"), STREAM_REVISE("stream-revise"), MILMMT("milmmt"), MURASAKI("murasaki");

    fun prompt(source: String, context: List<String>, pair: LanguagePair): TranslationPrompt {
        require(source.isNotBlank() && source.length <= 8_192 && source.any(Char::isLetterOrDigit))
        val target = pair.target.promptName
        val history = context.takeLast(4).joinToString("\n")
        return when (this) {
            HY_MT2 -> TranslationPrompt(HY_PREFIX,
                if (history.isEmpty()) "Translate the following text into $target. Note that you should only output the translated result without any additional explanation:\n\n$source"
                else "Translate only [Source Text] into $target. Use the background only for context. Output only the translated result without any additional explanation.\n\n[Source Text]\n$source",
                HY_SUFFIX, history)
            STREAM_REVISE -> {
                require(pair.source in CORE && pair.target in CORE)
                // Confirmed segments use the author's cold-start/source-history layout. No provisional MT is published.
                TranslationPrompt(HY_PREFIX,
                    if (history.isEmpty()) "Translate the following text from ${pair.source.promptName} into $target. Note that you should only output the translated result without any additional explanation:\n\n$source"
                    else "Please translate the following text from ${pair.source.promptName} into $target, taking the provided background information into consideration.\n\n[Source Text]\n$source",
                    HY_SUFFIX, if (history.isEmpty()) "" else "Recent source utterances:\n$history")
            }
            MILMMT -> TranslationPrompt("", "Translate this from ${pair.source.milName} to ${pair.target.milName}:\n${pair.source.milName}: $source\n${pair.target.milName}:", "")
            MURASAKI -> {
                require(pair == LanguagePair(Language.JA, Language.ZH))
                TranslationPrompt("<|im_start|>system\n你是一个严谨的 ACGN 短句翻译引擎。\n请对输入的日文短句进行精准直译。\n\n**核心要求：**\n1. **零上下文：** 严禁脑补不存在的背景或主语。指代不明时保持模糊。\n2. **精准还原：** 忠实保留原文的结构与信息量，不进行过度润色。<|im_end|>\n<|im_start|>user\n",
                    "请翻译：\n$source", "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n")
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
