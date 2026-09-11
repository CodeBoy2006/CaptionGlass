package com.captionglass.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/** Progress spans the copy and hash passes, so both halves of a large import move the indicator. */
data class PackState(val importing: Boolean = false, val progress: Float = 0f, val failed: Boolean = false, val detail: String? = null, val modelId: String? = null)

/** Each pinned model has an atomic install; both ASR choices share one translation model. */
object ModelPack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PackState())
    val state = mutableState.asStateFlow()
    val importing: Boolean get() = mutableState.value.importing
    @Synchronized fun directory(context: Context, model: ModelSpec): File {
        val target = File(context.filesDir, "models/${model.id}")
        val backup = File(context.filesDir, "models/${model.id}-backup")
        // Recover if the process died between moving the old pack and activating staging.
        if (!target.exists() && backup.exists()) check(backup.renameTo(target)) { "无法恢复原模型" }
        return target
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun ready(context: Context, model: ModelSpec): Boolean = runCatching {
        val dir = directory(context, model)
        File(dir, "verified").readText() == hash(model.manifest) && model.files.all {
            File(dir, it.name).length() == it.size
        }
    }.getOrDefault(false)

    /** Used by both SAF import and device acceptance. Hash every byte before native model parsing. */
    fun verify(context: Context, model: ModelSpec, dir: File = directory(context, model), read: (Long) -> Unit = {}) {
        model.files.forEach { entry ->
            val file = File(dir, entry.name)
            check(file.length() == entry.size) { "模型文件大小不匹配：${file.name}" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    read(n.toLong())
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256) {
                "模型校验失败：${file.name}"
            }
        }
        File(dir, "verified").writeText(hash(model.manifest))
    }

    fun import(context: Context, model: ModelSpec, tree: Uri) {
        if (importing || PlaybackCaptureService.state.value.active) return
        mutableState.value = PackState(modelId = model.id, importing = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val staging = File(context.filesDir, "models/${model.id}-staging")
                    staging.deleteRecursively(); check(staging.mkdirs()) { "无法创建临时目录，请检查存储空间" }
                    try {
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                        val documents = mutableMapOf<String, Uri>()
                        val expected = model.files
                        val names = expected.map { it.name }.toSet()
                        val total = expected.sumOf { it.size } * 2
                        var done = 0L
                        fun advance(bytes: Long) {
                            done += bytes
                            val progress = (done.toFloat() / total).coerceAtMost(1f)
                            if (progress - mutableState.value.progress >= 0.005f) mutableState.value = PackState(modelId = model.id, importing = true, progress = progress)
                        }
                        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                            while (cursor.moveToNext()) {
                                val name = cursor.getString(1)
                                if (name in names) {
                                    check(name !in documents) { "文件夹中有重复的 $name" }
                                    documents[name] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                                }
                            }
                        }
                        expected.forEach { entry ->
                            val name = entry.name
                            val uri = checkNotNull(documents[name]) { "所选文件夹缺少 $name" }
                            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                                File(staging, name).outputStream().use { output ->
                                    val buffer = ByteArray(1024 * 1024)
                                    var copied = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        copied += n
                                        check(copied <= entry.size) { "模型文件过大：$name" }
                                        output.write(buffer, 0, n)
                                        advance(n.toLong())
                                    }
                                    output.fd.sync()
                                }
                            }
                        }
                        verify(context, model, staging) { advance(it) }
                        synchronized(ModelPack) {
                            val target = directory(context, model)
                            val backup = File(context.filesDir, "models/${model.id}-backup")
                            backup.deleteRecursively()
                            if (target.exists()) check(target.renameTo(backup)) { "模型安装失败" }
                            if (!staging.renameTo(target)) { backup.renameTo(target); error("模型安装失败") }
                            backup.deleteRecursively()
                        }
                    } finally { staging.deleteRecursively() }
                }
                mutableState.value = PackState()
            } catch (e: Exception) {
                // Our own checks carry actionable copy; platform I/O messages are not user-facing.
                mutableState.value = PackState(modelId = model.id, failed = true, detail = (e as? IllegalStateException)?.message)
            }
        }
    }
}
