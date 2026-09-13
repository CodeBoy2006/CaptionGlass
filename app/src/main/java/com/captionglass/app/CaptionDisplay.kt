package com.captionglass.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.captionglass.engine.CaptionLine
import com.captionglass.engine.readingWindow

/** How much of the transcript a caption surface keeps on screen. Records always keep every row. */
internal enum class CaptionDisplay(val label: Int, val hint: Int, private val segments: Int) {
    SCROLL(R.string.display_scroll, R.string.display_scroll_hint, Int.MAX_VALUE),
    TWO(R.string.display_two, R.string.display_two_hint, 2),
    ONE(R.string.display_one, R.string.display_one_hint, 1);

    val scrolls get() = this == SCROLL
    fun visible(lines: List<CaptionLine>) = if (scrolls) lines else readingWindow(lines, segments)
}

/** The outline style drops the plate behind captions; the status chip keeps it so a short word stays legible. */
internal enum class CaptionStyle(val label: Int, val hint: Int) {
    PLATE(R.string.style_plate, R.string.style_plate_hint),
    OUTLINE(R.string.style_outline, R.string.style_outline_hint),
}

/** Caption text size, as a multiplier over the reading sizes both caption surfaces are drawn at. */
internal enum class CaptionSize(val label: Int, val scale: Float) {
    SMALL(R.string.size_small, 0.85f),
    STANDARD(R.string.size_standard, 1f),
    LARGE(R.string.size_large, 1.2f),
    HUGE(R.string.size_huge, 1.45f),
}

/** Presentation choices shared by the activity, the in-app stage and the overlay service in one process. */
internal object CaptionPreferences {
    private const val NAME = "caption-display"
    const val DISPLAY = "display"
    const val STYLE = "style"
    const val SIZE = "size"

    fun of(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun display(context: Context) = of(context).getString(DISPLAY, null)
        .let { saved -> CaptionDisplay.entries.find { it.name == saved } } ?: CaptionDisplay.SCROLL
    fun style(context: Context) = of(context).getString(STYLE, null)
        .let { saved -> CaptionStyle.entries.find { it.name == saved } } ?: CaptionStyle.PLATE
    fun size(context: Context) = of(context).getString(SIZE, null)
        .let { saved -> CaptionSize.entries.find { it.name == saved } } ?: CaptionSize.STANDARD
    fun save(context: Context, display: CaptionDisplay) = of(context).edit { putString(DISPLAY, display.name) }
    fun save(context: Context, style: CaptionStyle) = of(context).edit { putString(STYLE, style.name) }
    fun save(context: Context, size: CaptionSize) = of(context).edit { putString(SIZE, size.name) }
}
