package com.captionglass.app

import android.app.Activity
import android.app.Instrumentation
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.captionglass.engine.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.StringWriter

/** Model-free format and Android export checks. These fixtures are not speech-quality evidence. */
internal fun Instrumentation.recordExportChecks(activity: Activity) {
    val owner = "export-test-owner"
    fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node != null) { yield(node); repeat(node.childCount) { yieldAll(nodes(node.getChild(it))) } }
    }
    fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Timed out" }; SystemClock.sleep(100) }
    }
    fun click(text: String) {
        await {
            val node = nodes(uiAutomation.rootInActiveWindow).firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
            var target = node
            while (target != null && !target.isClickable) target = target.parent
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        waitForIdleSync()
    }
    fun pressBack() { uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); waitForIdleSync() }

    fun render(capture: CaptureState, format: RecordFormat) = StringWriter().also {
        writeRecords(targetContext, capture, format, it)
    }.toString()

    fun fixture(): CaptureState {
        val first = Segment(SegmentKey("export-check", 0, 1), "He said, \"hello\".\r\n你好 😀", 3_661_000, 3_663_123)
        val translated = CaptionLine(first, "他说：你好。", Caption(first, "他说：你好。"))
        val pending = CaptionLine(Segment(SegmentKey("export-check", 1), " \t=1+1", 3_663_123, 3_664_000), "未完\n\"成\"\\\u0001")
        val failures = UntranslatedReason.entries.mapIndexed { index, reason ->
            val segment = Segment(SegmentKey("export-check", index + 2L), "Source $reason", 3_664_000, 3_665_000)
            CaptionLine(segment, "部分译文", Caption(segment, untranslatedReason = reason))
        }
        return CaptureState(lines = listOf(translated, pending) + failures, confirmed = 7)
    }

    fun formatChecks() {
        val capture = fixture()
        val json = JSONObject(render(capture.copy(lines = capture.lines.reversed()), RecordFormat.JSON))
        check(json.getString("time_base") == "capture_session" && json.getBoolean("retained_only"))
        check(json.getString("source_language") == "en" && json.getString("target_language") == "zh")
        val records = json.getJSONArray("records")
        check(records.length() == capture.lines.size)
        capture.lines.forEachIndexed { index, line ->
            val row = records.getJSONObject(index)
            check(row.getLong("sequence") == line.segment.key.sequence && row.getInt("revision") == line.segment.key.revision)
            check(row.getString("source") == line.segment.source && row.getString("translation") == line.translation)
            check(row.getLong("start_ms") == line.segment.startMs && row.getLong("end_ms") == line.segment.endMs)
            check(row.getString("status") == (line.outcome?.untranslatedReason?.name ?: if (index == 0) "TRANSLATED" else "PENDING"))
        }
        val txt = render(capture, RecordFormat.TXT)
        check("[1:01:01 – 1:01:03] 已翻译" in txt && "非源视频时间轴" in txt)
        check("原文：${capture.lines[0].segment.source}\n" in txt)
        check("译文（未完成）：${capture.lines[1].translation}\n" in txt)
        val csv = render(capture, RecordFormat.CSV)
        check(csv.startsWith("\uFEFFsession_id,") && csv.endsWith("\r\n"))
        check("\"He said, \"\"hello\"\".\r\n你好 😀\"" in csv)
        check("\"' \t=1+1\"" in csv && "\"PENDING\"\r\n" in csv)
        for (formula in listOf("=1+1", "+1", "-1", "@SUM(1)", "\t=2", "\r=2", "\n=2", "\uFEFF =2", "＝1+1")) {
            val segment = Segment(SegmentKey("formula", 0), formula, 0, 1)
            val row = CaptureState(lines = listOf(CaptionLine(segment, formula, Caption(segment, formula))))
            check("\"'$formula\",\"'$formula\"" in render(row, RecordFormat.CSV))
            check(JSONObject(render(row, RecordFormat.JSON)).getJSONArray("records").getJSONObject(0).getString("source") == formula)
        }
        check(JSONObject(render(CaptureState(), RecordFormat.JSON)).getJSONArray("records").length() == 0)
        check(render(CaptureState(), RecordFormat.CSV).count { it == '\n' } == 1)
        val feed = CaptionFeed()
        repeat(201) { feed.submit(Segment(SegmentKey("bounded", it.toLong()), "Row $it", it.toLong(), it + 1L)) }
        val retained = JSONObject(render(CaptureState(lines = feed.lines), RecordFormat.JSON)).getJSONArray("records")
        check(retained.length() == 200 && retained.getJSONObject(0).getLong("sequence") == 1L)
    }

    fun workflowChecks() {
        val capture = fixture()
        // Seed only the existing state publisher; exercise the real Activity, Records UI, launcher and result callback.
        @Suppress("UNCHECKED_CAST")
        val state = PlaybackCaptureService::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
            .get(null) as MutableStateFlow<CaptureState>
        val original = state.value
        try {
            runOnMainSync { state.value = capture }
            click("记录")
            click("导出")
            await { nodes(uiAutomation.rootInActiveWindow).any { it.text?.toString() == "JSON" } }
            File(targetContext.cacheDir, "record-export-menu.png").outputStream().use {
                checkNotNull(uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            pressBack()
            for (format in RecordFormat.entries) {
                val file = File(targetContext.cacheDir, "record-export-check.${format.extension}")
                val result = ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                val filter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); addDataType(format.mimeType)
                }
                val monitor = addMonitor(filter, result, true)
                try {
                    click("导出"); click(format.name)
                    await { monitor.hits == 1 && !RecordExport.busy }
                    check(RecordExport.message == R.string.records_export_saved)
                    check(file.readText() == render(capture, format))
                } finally { removeMonitor(monitor); file.delete() }
            }
            // Exercise the real local document provider once; the other formats use the same save path.
            click("导出"); click("JSON")
            val filename = "CaptionGlass-export-system-${System.currentTimeMillis()}.json"
            await {
                nodes(uiAutomation.rootInActiveWindow).firstOrNull { it.isEditable }?.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename)
                    }) == true
            }
            await { nodes(uiAutomation.rootInActiveWindow).any { it.isEditable && it.text?.toString() == filename } }
            File(targetContext.cacheDir, "record-export-picker.png").outputStream().use {
                checkNotNull(uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val saveLabel = if (targetContext.resources.configuration.locales[0].language == "zh") "保存" else "Save"
            click(saveLabel)
            await { !RecordExport.busy }
            check(RecordExport.message == R.string.records_export_saved)
            val saved = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("cat /sdcard/Download/$filename"))
                .bufferedReader().use { it.readText() }
            check(saved == render(capture, RecordFormat.JSON))
            uiAutomation.executeShellCommand("rm /sdcard/Download/$filename").close()
            runOnMainSync {
                val intent = checkNotNull(RecordExport.prepare(capture, RecordFormat.JSON, owner))
                check(intent.type == "application/json" && Intent.CATEGORY_OPENABLE in intent.categories)
                check(intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".json"))
                check(RecordExport.prepare(capture, RecordFormat.TXT, owner) == null)
                activity.recreate()
                state.value = CaptureState()
            }
            waitForIdleSync()
            val snapshot = File(targetContext.cacheDir, "record-export-snapshot.json")
            runOnMainSync { RecordExport.save(targetContext, Uri.fromFile(snapshot), owner) }
            await { !RecordExport.busy }
            check(snapshot.readText() == render(capture, RecordFormat.JSON))
            snapshot.delete()
            runOnMainSync {
                check(RecordExport.prepare(CaptureState(), RecordFormat.TXT, owner) == null)
                checkNotNull(RecordExport.prepare(capture, RecordFormat.TXT, owner))
                RecordExport.save(targetContext, null, owner)
                check(!RecordExport.busy && RecordExport.message == null)
                checkNotNull(RecordExport.prepare(capture, RecordFormat.TXT, owner))
                RecordExport.abandon("another-activity")
                check(RecordExport.busy)
                RecordExport.abandon(owner)
                check(!RecordExport.busy)
                checkNotNull(RecordExport.prepare(capture, RecordFormat.TXT, owner))
                RecordExport.save(targetContext, Uri.parse("content://com.captionglass.missing/export"), owner)
            }
            await { !RecordExport.busy }
            check(RecordExport.message == R.string.records_export_failed)
            runOnMainSync {
                RecordExport.save(targetContext, Uri.parse("content://com.captionglass.missing/expired"), owner)
                check(RecordExport.message == R.string.records_export_expired)
                RecordExport.dismiss()
            }
        } finally { runOnMainSync { state.value = original } }
    }

    formatChecks()
    workflowChecks()
}
