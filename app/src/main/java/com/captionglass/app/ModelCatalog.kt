package com.captionglass.app

import android.content.Context
import com.captionglass.engine.Language
import com.captionglass.engine.LanguagePair
import com.captionglass.nativebridge.RecognizerFiles
import org.json.JSONObject
import java.io.File

data class ModelFile(val role: String, val name: String, val size: Long, val sha256: String)
data class ModelSpec(val id: String, val name: String, val kind: String, val languages: List<Language>,
                     val files: List<ModelFile>, val decodingMethod: String?, val manifest: String) {
    val size: Long get() = files.sumOf { it.size }
    fun file(context: Context, role: String): File = File(ModelPack.directory(context, this), files.single { it.role == role }.name)
    fun recognizerFiles(context: Context) = RecognizerFiles(file(context, "encoder"), file(context, "decoder"),
        file(context, "joiner"), file(context, "tokens"), checkNotNull(decodingMethod))
}

data class ModelSelection(val languages: LanguagePair = LanguagePair(), val recognizerId: String = "x-asr-zh-en-480ms")

/** APK-owned catalog only. Imported folders contain data, never executable configuration. */
class ModelCatalog(context: Context) {
    val models: List<ModelSpec>
    val recognizers: List<ModelSpec> get() = models.filter { it.kind == "asr" }
    val translator: ModelSpec get() = models.single { it.kind == "mt" }
    val sources: List<Language> get() = Language.entries.filter { language -> recognizers.any { language in it.languages } }

    init {
        val root = context.assets.open("catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        check(root.getInt("schemaVersion") == 2)
        val entries = root.getJSONArray("models")
        models = List(entries.length()) { index ->
            val model = entries.getJSONObject(index)
            val kind = model.getString("kind")
            val runtime = model.getJSONObject("runtime")
            check(model.getBoolean("installable"))
            check(runtime.getString("revision") == when (kind) {
                "asr" -> "11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf"
                "mt" -> "5266f24da75dc449bd56cbed7addb9c8e4a6a73e"
                else -> error("Unknown model kind")
            })
            val id = model.getString("id").also { check(it.matches(Regex("[a-z0-9][a-z0-9.-]*"))) }
            val revision = model.getString("revision").also { check(it.matches(Regex("[a-f0-9]{40}"))) }
            val languages = model.getJSONArray("intendedLanguages")
            val files = model.getJSONArray("files")
            ModelSpec(id, model.getString("name"), kind,
                List(languages.length()) { checkNotNull(Language.fromCode(languages.getString(it))) },
                List(files.length()) {
                    val entry = files.getJSONObject(it)
                    check(entry.getString("url").startsWith(model.getString("source") + "/resolve/$revision/"))
                    val name = entry.getString("path").substringAfterLast('/')
                    check(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")))
                    val size = entry.getLong("sizeBytes").also { check(it > 0) }
                    val sha = entry.getString("sha256").also { check(it.matches(Regex("[a-f0-9]{64}"))) }
                    ModelFile(entry.getString("role"), name, size, sha)
                }, model.optString("decodingMethod").takeIf { kind == "asr" }, model.toString()).also { spec ->
                    if (kind == "asr") check(spec.decodingMethod in setOf("greedy_search", "modified_beam_search"))
                    check(spec.files.map { it.name }.distinct().size == spec.files.size)
                    check(spec.files.map { it.role }.toSet() == if (kind == "asr")
                        setOf("encoder", "decoder", "joiner", "tokens") else setOf("model"))
                }
        }
        check(models.map { it.id }.distinct().size == models.size)
        check(sources.all { it in translator.languages })
    }

    fun recognizer(selection: ModelSelection) = recognizers.single { it.id == selection.recognizerId }
    fun required(selection: ModelSelection) = listOf(recognizer(selection), translator)
    fun valid(selection: ModelSelection) = recognizers.any { it.id == selection.recognizerId && selection.languages.source in it.languages } &&
        selection.languages.target in translator.languages

    fun withLanguages(selection: ModelSelection, pair: LanguagePair): ModelSelection {
        val model = recognizers.find { it.id == selection.recognizerId && pair.source in it.languages }
            ?: recognizers.first { pair.source in it.languages }
        return ModelSelection(pair, model.id).also { require(valid(it)) }
    }
}
