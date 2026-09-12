package com.captionglass.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonWriter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.captionglass.engine.CaptionLine
import kotlinx.coroutines.*
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal enum class RecordFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"), JSON("json", "application/json"), CSV("csv", "text/csv"),
}

/** Main owns the snapshot; process-scoped IO survives navigation and Activity recreation, like model imports. */
internal object RecordExport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Request(val capture: CaptureState, val format: RecordFormat, val owner: String)
    private var pending: Request? = null
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
        private set

    fun prepare(capture: CaptureState, format: RecordFormat, owner: String): Intent? {
        if (busy || capture.lines.isEmpty()) return null
        pending = Request(capture.copy(lines = capture.lines.toList()), format, owner)
        busy = true
        message = null
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(format.mimeType).putExtra(Intent.EXTRA_TITLE, "CaptionGlass-$timestamp.${format.extension}")
    }

    fun launchFailed() { pending = null; busy = false; message = R.string.records_export_failed }
    fun dismiss() { message = null }
    fun abandon(owner: String) {
        if (pending?.owner == owner) { pending = null; busy = false }
    }

    fun save(context: Context, uri: Uri?, owner: String) {
        val request = pending
        if (request != null && request.owner != owner) return
        pending = null
        if (uri == null) { busy = false; return }
        if (request == null) {
            // A restored picker must never export a different session after process death.
            busy = false
            message = R.string.records_export_expired
            return
        }
        val application = context.applicationContext
        scope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    checkNotNull(application.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                        writeRecords(application, request.capture, request.format, it)
                    }
                    R.string.records_export_saved
                } catch (_: Exception) {
                    // ACTION_CREATE_DOCUMENT returns a new document. Remove partial output when its provider allows it.
                    runCatching { DocumentsContract.deleteDocument(application.contentResolver, uri) }
                    R.string.records_export_failed
                }
            }
            busy = false
        }
    }
}

private val CaptionLine.exportStatus: String
    get() = outcome?.untranslatedReason?.name ?: if (outcome == null) "PENDING" else "TRANSLATED"

/** Exports the same retained rows as Records, including explicitly incomplete previews. Never uses media time. */
internal fun writeRecords(context: Context, capture: CaptureState, format: RecordFormat, writer: Writer) {
    val languages = capture.selection.languages
    val lines = capture.lines.sortedBy { it.segment.key.sequence }
    when (format) {
        RecordFormat.TXT -> {
            writer.write(context.getString(R.string.records_export_heading, languages.source.code, languages.target.code, lines.size))
            for (line in lines) {
                val status = context.getString(line.outcome?.untranslatedReason?.label
                    ?: if (line.outcome == null) R.string.records_state_pending else R.string.records_state_translated)
                writer.write("\n[${sessionTime(line.segment.startMs)} – ${sessionTime(line.segment.endMs)}] $status\n")
                writer.write(context.getString(R.string.records_export_source, line.segment.source))
                if (line.translation.isNotEmpty()) writer.write(context.getString(
                    if (line.exportStatus == "TRANSLATED") R.string.records_export_translation else R.string.records_export_preview,
                    line.translation))
            }
        }
        RecordFormat.JSON -> {
            val json = JsonWriter(writer).apply { setIndent("  ") }
            json.beginObject().name("time_base").value("capture_session")
                .name("retained_only").value(true)
                .name("source_language").value(languages.source.code).name("target_language").value(languages.target.code)
                .name("records").beginArray()
            for (line in lines) {
                val segment = line.segment
                json.beginObject().name("session_id").value(segment.key.sessionId)
                    .name("sequence").value(segment.key.sequence).name("revision").value(segment.key.revision.toLong())
                    .name("start_ms").value(segment.startMs).name("end_ms").value(segment.endMs)
                    .name("source").value(segment.source).name("translation").value(line.translation)
                    .name("status").value(line.exportStatus).endObject()
            }
            json.endArray().endObject()
            writer.write("\n")
        }
        RecordFormat.CSV -> {
            // UTF-8 BOM lets spreadsheet apps recognize Chinese; quoted fields preserve commas and newlines.
            writer.write("\uFEFFsession_id,sequence,revision,session_start_ms,session_end_ms,source_language,target_language,source,translation,status\r\n")
            for (line in lines) {
                val segment = line.segment
                writer.write(listOf(segment.key.sessionId, segment.key.sequence.toString(), segment.key.revision.toString(),
                    segment.startMs.toString(), segment.endMs.toString(), languages.source.code, languages.target.code,
                    segment.source, line.translation, line.exportStatus).joinToString(",", postfix = "\r\n", transform = ::csvCell))
            }
        }
    }
}

private fun csvCell(value: String): String {
    // Speech is untrusted spreadsheet input. Only CSV adds a text prefix; TXT/JSON remain lossless.
    val first = value.dropWhile { it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
    val text = if (first != null && first in "=+-@＝＋－＠" || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
    return "\"${text.replace("\"", "\"\"")}\""
}
