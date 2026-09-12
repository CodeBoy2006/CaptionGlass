package com.captionglass.app

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.captionglass.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.StringWriter

/** Deterministic UI fixtures exercise real windows and animations, never speech quality. */
internal fun Instrumentation.captionDisplayChecks() = runBlocking(Dispatchers.Main) {
    fun report(text: String) = sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
    fun line(sequence: Long, translation: String = "字幕", done: Boolean = true): CaptionLine {
        val segment = Segment(SegmentKey("display-check", sequence), "Source sentence $sequence.", sequence, sequence + 1)
        return CaptionLine(segment, translation, if (done) Caption(segment, translation) else null)
    }
    fun texts(view: View): List<TextView> = if (view is ViewGroup)
        (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else listOfNotNull(view as? TextView)
    fun screenshot(name: String) {
        File(targetContext.cacheDir, "$name.png").outputStream().use {
            checkNotNull(uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    val positions = targetContext.getSharedPreferences("overlay-position", Context.MODE_PRIVATE)
    val orientation = targetContext.resources.configuration.orientation
    val savedEdge = positions.all["edge-$orientation"] as? Int
    val savedBottom = positions.all["bottom-$orientation"] as? Boolean
    for (mode in listOf(CaptionDisplay.ONE, CaptionDisplay.TWO, CaptionDisplay.SCROLL)) {
        val overlay = SubtitleOverlay(targetContext)
        try {
            positions.edit().putBoolean("bottom-$orientation", mode == CaptionDisplay.TWO)
                .putInt("edge-$orientation", targetContext.resources.displayMetrics.heightPixels *
                    (if (mode == CaptionDisplay.TWO) 7 else 4) / 10).commit()
            overlay.show()
            val view = overlay.captions
            view.display = mode
            var state = CaptureState(active = true, status = CaptureStatus.HEARING, lines = listOf(line(0)))
            overlay.render(state)
            delay(700)
            val height = view.rootView.height
            val content = view.getChildAt(0) as ViewGroup
            val row = content.getChildAt(0)
            val top = row.top - view.scrollY
            state = state.copy(provisional = "New speech is arriving and wrapping onto another line.")
            overlay.render(state)
            delay(400)
            report("$mode card height before=$height after draft=${view.rootView.height}")
            screenshot("caption-display-${mode.name.lowercase()}")
            if (!mode.scrolls) {
                check(view.rootView.height == height) { "$mode draft changed the card height" }
                check(row.top - view.scrollY == top && row.translationY == 0f) { "$mode draft moved existing text" }
            }
            state = state.copy(provisional = "")
            overlay.render(state)
            delay(400)
            if (!mode.scrolls) check(view.rootView.height == height && row.top - view.scrollY == top)
            state = state.copy(lines = listOf(line(0), line(1, "", false)))
            overlay.render(state)
            delay(350)
            repeat(8) { index ->
                state = state.copy(lines = listOf(line(0), line(1, "这是逐步出现的字幕。".take(index + 1), false)))
                overlay.render(state)
                delay(120)
                if (!mode.scrolls) check(view.rootView.height == height) { "$mode streaming resized the window" }
            }
            state = state.copy(lines = listOf(line(0), line(1, "这是逐步出现的字幕。")))
            overlay.render(state)
            delay(500)
            check((0 until content.childCount).all { content.getChildAt(it).translationY == 0f })
            val retained = state.lines
            // Audio ticks and status changes must neither postpone expiry nor resurrect cleared captions.
            repeat(16) { second ->
                delay(1_000)
                overlay.render(state.copy(audioMs = second * 1000L, status = CaptureStatus.SILENT))
            }
            check(view.rootView.height < height && !view.isShown) { "$mode did not collapse after inactivity" }
            check(texts(view).all { it.text.isEmpty() }) { "Collapsed window retained text" }
            screenshot("caption-display-idle")
            overlay.configurationChanged()
            delay(500)
            check(!view.isShown) { "Configuration change restored cleared text" }
            val exported = StringWriter().also { writeRecords(targetContext, state, RecordFormat.JSON, it) }
            check(JSONObject(exported.toString()).getJSONArray("records").length() == retained.size)
            overlay.render(state.copy(lines = retained + line(2)))
            delay(500)
            check(view.isShown)
            check(texts(view).any { it.text.toString() == "Source sentence 2." })
            check(texts(view).none { it.text.toString() in listOf("Source sentence 0.", "Source sentence 1.") })
            if (!mode.scrolls) {
                overlay.render(state.copy(lines = retained + line(2, "完整的长字幕不会被截断。".repeat(20))))
                delay(500)
                check(view.rootView.height == height && !view.canScrollVertically(1))
            }
            report("PASS: $mode stable geometry, idle clear, retained records and fresh-content resume")
        } finally {
            overlay.close(); delay(450)
            positions.edit().apply {
                if (savedEdge == null) remove("edge-$orientation") else putInt("edge-$orientation", savedEdge)
                if (savedBottom == null) remove("bottom-$orientation") else putBoolean("bottom-$orientation", savedBottom)
            }.commit()
        }
    }
}
