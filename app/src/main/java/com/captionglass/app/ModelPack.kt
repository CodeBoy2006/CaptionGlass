package com.captionglass.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** M1 has one pinned offline pack. No downloaded code, network fallback or mutable model aliases. */
object ModelPack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow("")
    val state = mutableState.asStateFlow()
    var importing = false
        private set
    @Synchronized fun directory(context: Context): File {
        val target = File(context.filesDir, "language-pack")
        val backup = File(context.filesDir, "language-pack-backup")
        // Recover if the process died between moving the old pack and activating staging.
        if (!target.exists() && backup.exists()) check(backup.renameTo(target)) { "无法恢复原语言包" }
        return target
    }
    private fun manifest(context: Context) = context.assets.open("zh-en.json").use { it.readBytes() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun entries(context: Context): List<JSONObject> = buildList {
        val models = JSONObject(manifest(context).decodeToString()).getJSONArray("models")
        repeat(models.length()) { index ->
            val model = models.getJSONObject(index)
            check(model.getBoolean("installable") && model.getJSONObject("runtime").getString("revision").length == 40)
            val files = model.getJSONArray("files")
            repeat(files.length()) { add(files.getJSONObject(it)) }
        }
    }
    fun ready(context: Context): Boolean = runCatching {
        val dir = directory(context)
        File(dir, "verified").readText() == hash(manifest(context)) && entries(context).all {
            File(dir, it.getString("path").substringAfterLast('/')).length() == it.getLong("sizeBytes")
        }
    }.getOrDefault(false)

    /** Used by both SAF import and device acceptance. Hash every byte before native model parsing. */
    fun verify(context: Context, dir: File = directory(context)) {
        entries(context).forEach { entry ->
            val file = File(dir, entry.getString("path").substringAfterLast('/'))
            check(file.length() == entry.getLong("sizeBytes")) { "语言包文件大小不匹配：${file.name}" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == entry.getString("sha256")) {
                "语言包校验失败：${file.name}"
            }
        }
        File(dir, "verified").writeText(hash(manifest(context)))
    }

    fun import(context: Context, tree: Uri) {
        if (importing || PlaybackCaptureService.state.value.active) return
        importing = true
        mutableState.value = "正在导入并校验语言包…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val staging = File(context.filesDir, "language-pack-staging")
                    staging.deleteRecursively(); check(staging.mkdirs())
                    try {
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                        val documents = mutableMapOf<String, Uri>()
                        val expected = entries(context)
                        val names = expected.map { it.getString("path").substringAfterLast('/') }.toSet()
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
                            val name = entry.getString("path").substringAfterLast('/')
                            val uri = checkNotNull(documents[name]) { "所选文件夹缺少 $name" }
                            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                                File(staging, name).outputStream().use { output ->
                                    val buffer = ByteArray(1024 * 1024)
                                    var copied = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        copied += n
                                        check(copied <= entry.getLong("sizeBytes")) { "语言包文件过大：$name" }
                                        output.write(buffer, 0, n)
                                    }
                                    output.fd.sync()
                                }
                            }
                        }
                        verify(context, staging)
                        synchronized(ModelPack) {
                            val target = directory(context)
                            val backup = File(context.filesDir, "language-pack-backup")
                            backup.deleteRecursively()
                            if (target.exists()) check(target.renameTo(backup))
                            if (!staging.renameTo(target)) { backup.renameTo(target); error("语言包安装失败") }
                            backup.deleteRecursively()
                        }
                    } finally { staging.deleteRecursively() }
                }
                mutableState.value = "中英语言包已就绪"
            } catch (e: Exception) {
                mutableState.value = e.message ?: "导入失败，请检查空间与文件权限"
            } finally { importing = false }
        }
    }
}
