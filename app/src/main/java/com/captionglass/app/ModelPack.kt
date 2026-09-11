package com.captionglass.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.storage.StorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class PackPhase { IDLE, DOWNLOADING, IMPORTING, VERIFYING, REMOVING, CANCELLING }
data class PackState(val phase: PackPhase = PackPhase.IDLE, val progress: Float = 0f,
                     val failed: Boolean = false, val detail: String? = null, val modelId: String? = null,
                     val file: String? = null) {
    val busy: Boolean get() = phase != PackPhase.IDLE
}
data class InstalledModel(val ready: Boolean, val bytes: Long)

/** One operation at a time; activation never exposes unverified files to a session. */
object ModelPack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PackState())
    val state = mutableState.asStateFlow()
    val busy: Boolean get() = mutableState.value.busy
    private var job: Job? = null
    private var recovered = false

    @Synchronized fun directory(context: Context, model: ModelSpec): File {
        val target = File(context.filesDir, "models/${model.id}")
        val backup = File(context.filesDir, "models/${model.id}-backup")
        // Recover if the process died between moving the old pack and activating staging.
        if (!target.exists() && backup.exists()) check(backup.renameTo(target)) { "无法恢复原模型" }
        return target
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun ready(context: Context, model: ModelSpec): Boolean = runCatching {
        check(model.installable)
        val dir = directory(context, model)
        File(dir, "verified").readText() == hash(model.manifest) && model.files.all {
            File(dir, it.name).isFile && File(dir, it.name).length() == it.size
        }
    }.getOrDefault(false)
    fun installed(context: Context, model: ModelSpec) = InstalledModel(ready(context, model),
        listOf("", "-backup", "-staging", "-deleting").sumOf { suffix ->
            File(context.filesDir, "models/${model.id}$suffix").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        })
    fun availableBytes(context: Context): Long {
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    }

    /** Process death cancels a transfer. Remove its temporary bytes on the next launch. */
    fun recover(context: Context, models: List<ModelSpec>) {
        if (recovered || busy || PlaybackCaptureService.state.value.active) return
        recovered = true
        perform(null, PackPhase.VERIFYING) {
            models.forEach { model ->
                directory(context, model)
                listOf("-staging", "-backup", "-deleting").forEach { suffix ->
                    check(File(context.filesDir, "models/${model.id}$suffix").deleteRecursively()) { "临时文件清理失败，请重新打开应用" }
                }
            }
            null
        }
    }

    /** Hash every byte before native parsing. A failed recheck must invalidate the old marker. */
    fun verify(context: Context, model: ModelSpec, dir: File = directory(context, model), read: (Long) -> Unit = {}) {
        check(model.installable) { "此模型暂不可安装" }
        val marker = File(dir, "verified")
        check(!marker.exists() || marker.delete()) { "无法更新模型校验状态" }
        model.files.forEach { entry ->
            val file = File(dir, entry.name)
            check(file.isFile && file.length() == entry.size) { "模型文件大小不匹配：${file.name}" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    read(n.toLong())
                    digest.update(buffer, 0, n)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256) { "模型校验失败：${file.name}" }
        }
        marker.writeText(hash(model.manifest))
    }

    fun recheck(context: Context, model: ModelSpec) = perform(model.id, PackPhase.VERIFYING) {
        val operation = currentCoroutineContext()
        var done = 0L
        verify(context, model) { bytes ->
            operation.ensureActive()
            done += bytes
            progress(done.toFloat() / model.size)
        }
        "模型校验通过"
    }

    fun remove(context: Context, model: ModelSpec) = perform(model.id, PackPhase.REMOVING) {
        val target = directory(context, model)
        val deleting = File(context.filesDir, "models/${model.id}-deleting")
        check(deleting.deleteRecursively()) { "无法清理模型文件，请重试" }
        // Rename first so an interrupted deletion can never be mistaken for an installed pack.
        if (target.exists()) check(target.renameTo(deleting)) { "无法删除模型，请重试" }
        check(deleting.deleteRecursively()) { "部分文件未能删除，请重试" }
        check(File(context.filesDir, "models/${model.id}-backup").deleteRecursively()) { "无法清理原模型文件" }
        "模型已删除，需要时可重新下载或导入"
    }

    fun cancel() {
        if (!busy || mutableState.value.phase in listOf(PackPhase.REMOVING, PackPhase.CANCELLING)) return
        mutableState.update { it.copy(phase = PackPhase.CANCELLING) }
        job?.cancel()
    }
    fun dismiss() { if (!busy) mutableState.value = PackState() }

    fun download(context: Context, model: ModelSpec) = install(context, model, PackPhase.DOWNLOADING) { networkInput(it) }

    fun import(context: Context, model: ModelSpec, tree: Uri): Job? {
        val documents by lazy {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val names = model.files.map { it.name }.toSet()
            buildMap<String, Uri> {
                checkNotNull(context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)) { "无法读取文件夹，请重新选择" }.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        if (name in names) {
                            check(name !in this) { "文件夹中有重复的 $name" }
                            put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                        }
                    }
                }
                model.files.forEach { check(it.name in this) { "所选文件夹缺少 ${it.name}" } }
            }
        }
        return install(context, model, PackPhase.IMPORTING) { entry ->
            checkNotNull(context.contentResolver.openInputStream(documents.getValue(entry.name))) { "无法读取 ${entry.name}" }
        }
    }

    /** Download and SAF import share the same size limits, verification, and atomic activation. */
    internal fun install(context: Context, model: ModelSpec, phase: PackPhase, open: (ModelFile) -> InputStream): Job? =
        perform(model.id, phase) {
            check(model.installable) { "此模型暂不可安装" }
            val operation = currentCoroutineContext()
            val staging = File(context.filesDir, "models/${model.id}-staging")
            check(staging.deleteRecursively()) { "无法清理临时目录" }
            // Leave a small margin for the app and filesystem while the old model is retained.
            val needed = model.size + 32L * 1024 * 1024
            check(availableBytes(context) >= needed) { "空间不足，请至少腾出 ${modelSize(needed)} 后重试" }
            val storage = context.getSystemService(StorageManager::class.java)
            storage.allocateBytes(storage.getUuidForPath(context.filesDir), needed)
            check(staging.mkdirs()) { "无法创建临时目录，请检查存储空间" }
            try {
                var done = 0L
                model.files.forEach { entry ->
                    operation.ensureActive()
                    mutableState.update { it.copy(file = entry.name) }
                    open(entry).use { input ->
                        File(staging, entry.name).outputStream().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var copied = 0L
                            while (true) {
                                operation.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                copied += n
                                check(copied <= entry.size) { "模型文件过大：${entry.name}" }
                                output.write(buffer, 0, n)
                                done += n
                                progress(done.toFloat() / model.size / 2)
                            }
                            check(copied == entry.size) { "文件不完整：${entry.name}，请重试" }
                            output.fd.sync()
                        }
                    }
                }
                operation.ensureActive()
                mutableState.update { if (it.phase == PackPhase.CANCELLING) it else it.copy(phase = PackPhase.VERIFYING, file = null) }
                verify(context, model, staging) { bytes ->
                    operation.ensureActive()
                    done += bytes
                    progress(done.toFloat() / model.size / 2)
                }
                operation.ensureActive()
                synchronized(ModelPack) {
                    val target = directory(context, model)
                    val backup = File(context.filesDir, "models/${model.id}-backup")
                    check(backup.deleteRecursively()) { "无法清理原模型文件" }
                    if (target.exists()) check(target.renameTo(backup)) { "模型安装失败" }
                    if (!staging.renameTo(target)) { backup.renameTo(target); error("模型安装失败") }
                    check(backup.deleteRecursively()) { "模型已安装，但原文件清理失败，请重新打开应用" }
                }
                "模型已就绪"
            } finally { staging.deleteRecursively() }
        }

    private fun progress(value: Float) {
        mutableState.update {
            if (it.phase == PackPhase.CANCELLING || value - it.progress < 0.005f) it
            else it.copy(progress = value.coerceIn(0f, 1f))
        }
    }

    /** Main-thread entry reserves the operation before a capture service can start. */
    private fun perform(modelId: String?, phase: PackPhase, action: suspend () -> String?): Job? {
        if (busy || PlaybackCaptureService.state.value.active) return null
        mutableState.value = PackState(phase = phase, modelId = modelId)
        return scope.launch {
            var completed = false
            var message: String? = null
            try {
                withContext(Dispatchers.IO) { message = action(); completed = true }
                mutableState.value = PackState(modelId = modelId, detail = message)
            } catch (_: CancellationException) {
                mutableState.value = PackState(modelId = modelId, detail = when {
                    completed -> message
                    phase == PackPhase.VERIFYING -> "校验已取消，请重新校验后使用"
                    else -> "已取消，原有模型不受影响"
                })
            } catch (e: Exception) {
                mutableState.value = PackState(modelId = modelId, failed = true,
                    detail = (e as? IllegalStateException)?.message ?: when (phase) {
                        PackPhase.DOWNLOADING -> "下载失败，请检查网络与存储空间后重试"
                        PackPhase.IMPORTING -> "导入失败，请检查空间与文件权限"
                        else -> "模型操作失败，请重试"
                    })
            } finally { job = null }
        }.also { job = it }
    }

    private fun networkInput(file: ModelFile): InputStream {
        var url = URL(file.url)
        // Hugging Face redirects pinned files to its CDN. Never downgrade to cleartext HTTP.
        repeat(6) {
            check(url.protocol == "https" && url.userInfo == null) { "模型下载地址不安全" }
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, checkNotNull(connection.getHeaderField("Location")) { "模型下载地址无效" })
                    connection.disconnect()
                } else {
                    check(code == 200) { "下载失败（HTTP $code），请稍后重试" }
                    check(connection.contentLengthLong < 0 || connection.contentLengthLong == file.size) { "模型下载大小不匹配，请重试" }
                    return object : FilterInputStream(connection.inputStream) {
                        override fun close() { try { super.close() } finally { connection.disconnect() } }
                    }
                }
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        error("模型下载跳转过多，请稍后重试")
    }
}
