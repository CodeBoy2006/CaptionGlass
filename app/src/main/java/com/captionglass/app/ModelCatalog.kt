package com.captionglass.app

import android.content.Context
import com.captionglass.engine.Language
import com.captionglass.engine.LanguagePair
import com.captionglass.engine.TranslationFormat
import com.captionglass.nativebridge.RecognizerFiles
import org.json.JSONObject
import java.io.File

data class ModelFile(val role: String, val name: String, val size: Long, val sha256: String, val url: String)
data class ModelSpec(val id: String, val name: String, val kind: String, val languages: List<Language>,
                     val files: List<ModelFile>, val decodingMethod: String?, val manifest: String,
                     val source: String, val revision: String, val license: String, val languagePrompt: Boolean,
                     val family: String, val variant: String, val adapter: String, val sourceLanguages: List<Language>,
                     val installable: Boolean, val unavailableReason: String?, val communityConversion: Boolean) {
    val size: Long get() = files.sumOf { it.size }
    val segmented: Boolean get() = kind == "asr" && adapter != "online-transducer"
    val translationFormat: TranslationFormat get() = TranslationFormat.entries.single { it.id == adapter }
    fun supports(pair: LanguagePair) = installable && if (kind == "asr") pair.source in languages
        else pair.source in sourceLanguages && pair.target in languages
    fun file(context: Context, role: String): File = File(ModelPack.directory(context, this), files.single { it.role == role }.name)
    fun recognizerFiles(context: Context, language: Language): RecognizerFiles {
        require(installable && kind == "asr" && language in languages)
        return RecognizerFiles(files.associate { it.role to file(context, it.role) }, adapter,
            checkNotNull(decodingMethod), language.takeIf { languagePrompt })
    }
}

data class ModelSelection(val languages: LanguagePair = LanguagePair(), val recognizerId: String = "x-asr-zh-en-480ms",
                          val translatorId: String = "hy-mt2-1.8b-q4-k-m")

/** APK-owned catalog only. Imported folders contain data, never executable configuration. */
class ModelCatalog(context: Context) {
    val models: List<ModelSpec>
    val recognizers: List<ModelSpec> get() = models.filter { it.kind == "asr" && it.installable }
    val translators: List<ModelSpec> get() = models.filter { it.kind == "mt" && it.installable }
    val families: Map<String, List<ModelSpec>> get() = models.groupBy { it.family }
    val sources: List<Language> get() = Language.entries.filter { language ->
        recognizers.any { language in it.languages } && translators.any { language in it.sourceLanguages }
    }
    fun targets(source: Language) = Language.entries.filter { target -> target != source &&
        translators.any { source in it.sourceLanguages && target in it.languages } }

    init {
        val root = context.assets.open("catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        check(root.getInt("schemaVersion") == 3)
        val entries = root.getJSONArray("models")
        models = List(entries.length()) { index ->
            val model = entries.getJSONObject(index)
            val kind = model.getString("kind").also { check(it in setOf("asr", "mt")) }
            val installable = model.getBoolean("installable")
            val adapter = model.getString("adapter")
            val id = model.getString("id").also { check(it.matches(Regex("[a-z0-9][a-z0-9.-]*"))) }
            val revision = model.optString("revision")
            val source = model.getString("source").also { check(it.matches(Regex("https://huggingface\\.co/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) }
            fun languages(key: String): List<Language> {
                val list = model.getJSONArray(key)
                return List(list.length()) { checkNotNull(Language.fromCode(list.getString(it))) }
                    .also { check(it.isNotEmpty() && it.distinct().size == it.size) }
            }
            val files = model.getJSONArray("files")
            val parsedFiles = List(files.length()) {
                val entry = files.getJSONObject(it)
                val path = entry.getString("path")
                check(path.split('/').all { part -> part.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) })
                val name = entry.optString("name", path.substringAfterLast('/'))
                check(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) && name != "verified")
                val url = entry.getString("url")
                // Auxiliary VAD files have their own immutable repository revision.
                check(url.matches(Regex("https://huggingface\\.co/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/resolve/[a-f0-9]{40}/.+")))
                check(url.endsWith("/$path"))
                if (entry.getString("role") != "vad") check(url == "$source/resolve/$revision/$path")
                val size = entry.getLong("sizeBytes").also { check(it > 0) }
                val sha = entry.getString("sha256").also { check(it.matches(Regex("[a-f0-9]{64}"))) }
                ModelFile(entry.getString("role"), name, size, sha, url)
            }
            if (installable) {
                check(revision.matches(Regex("[a-f0-9]{40}")))
                val runtime = model.getJSONObject("runtime")
                check(runtime.getString("revision") == when {
                    adapter == "japanese-zipformer" -> "2.2.0"
                    kind == "asr" -> "11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf"
                    else -> "5266f24da75dc449bd56cbed7addb9c8e4a6a73e"
                })
                if (runtime.getString("name") == "sherpa-onnx") check(runtime.getString("patch") == "captionglass-qwen-completeness-1")
                val roles = when (adapter) {
                    "online-transducer" -> setOf("encoder", "decoder", "joiner", "tokens")
                    "offline-transducer" -> setOf("encoder", "decoder", "joiner", "tokens", "vad")
                    "offline-ctc" -> setOf("model", "tokens", "vad")
                    "qwen3-asr" -> setOf("frontend", "encoder", "decoder", "vocab", "merges", "tokenizer", "vad")
                    "japanese-zipformer" -> setOf("model", "vocab", "vad")
                    else -> { check(kind == "mt" && TranslationFormat.entries.any { it.id == adapter }); setOf("model") }
                }
                check(parsedFiles.map { it.role }.toSet() == roles)
                if (kind == "asr") check(model.getString("decodingMethod") in setOf("greedy_search", "modified_beam_search"))
            } else check(parsedFiles.isEmpty() && model.getString("unavailableReason").isNotBlank())
            check(parsedFiles.map { it.name }.distinct().size == parsedFiles.size)
            check(parsedFiles.map { it.role }.distinct().size == parsedFiles.size)
            // Presentation changes must not invalidate a verified install.
            val fingerprint = "$id|$adapter|$revision|${model.optJSONObject("runtime")}|$parsedFiles"
            ModelSpec(id, model.getString("name"), kind, languages("intendedLanguages"), parsedFiles,
                model.optString("decodingMethod").takeIf { kind == "asr" }, fingerprint, source, revision,
                model.getString("license"), model.optBoolean("languagePrompt"), model.getString("family"),
                model.getString("variant"), adapter, if (kind == "mt") languages("sourceLanguages") else emptyList(),
                installable, model.optString("unavailableReason").takeIf { it.isNotBlank() }, model.optBoolean("communityConversion"))
        }
        check(models.map { it.id }.distinct().size == models.size)
        check(families.values.all { family -> family.map { it.kind }.distinct().size == 1 })
        check(valid(ModelSelection()))
    }

    fun recognizer(selection: ModelSelection) = recognizers.single { it.id == selection.recognizerId }
    fun translator(selection: ModelSelection) = translators.single { it.id == selection.translatorId }
    fun required(selection: ModelSelection) = listOf(recognizer(selection), translator(selection))
    fun valid(selection: ModelSelection) = recognizers.any { it.id == selection.recognizerId && it.supports(selection.languages) } &&
        translators.any { it.id == selection.translatorId && it.supports(selection.languages) }

    fun withLanguages(selection: ModelSelection, pair: LanguagePair): ModelSelection {
        val asr = recognizers.find { it.id == selection.recognizerId && it.supports(pair) } ?: recognizers.first { it.supports(pair) }
        val mt = translators.find { it.id == selection.translatorId && it.supports(pair) } ?: translators.first { it.supports(pair) }
        return ModelSelection(pair, asr.id, mt.id).also { require(valid(it)) }
    }
}
