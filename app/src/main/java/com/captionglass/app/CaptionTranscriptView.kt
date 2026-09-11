package com.captionglass.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.captionglass.engine.CaptionLine
import com.captionglass.engine.SegmentKey

/** Shared by the home stage and overlay. Existing rows retain their identity and reading position. */
internal class CaptionTranscriptView(context: Context) : ScrollView(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = linkedMapOf<SegmentKey, Row>()
    private val draft = captionText(15f)
    private var stableLength = -1
    private var following = true
    private var touching = false
    private var adjusting = false
    private var version = 0
    var maximumHeight = Int.MAX_VALUE

    init {
        isFillViewport = false
        clipToPadding = false
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        content.addView(draft)
        addView(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (mode == MeasureSpec.UNSPECIFIED) maximumHeight else minOf(maximumHeight, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height,
            if (mode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = true
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            following = atBottom()
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    private fun atBottom() = content.height - height - scrollY <= dp(8)
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!adjusting) following = atBottom()
    }

    fun render(state: CaptureState) {
        val follow = following
        val anchor = rows.entries.firstOrNull { it.value.bottom > scrollY }
        val anchorOffset = anchor?.let { scrollY - it.value.top } ?: 0
        adjusting = true
        val keys = state.lines.map { it.segment.key }.toSet()
        rows.keys.filter { it !in keys }.forEach { key -> content.removeView(rows.remove(key)) }
        state.lines.forEachIndexed { index, line ->
            val row = rows.getOrPut(line.segment.key) { Row() }
            if (row.parent == null) content.addView(row, index)
            row.render(line)
        }
        val live = state.stable + state.provisional
        draft.isVisible = live.isNotBlank()
        if (live != draft.text.toString() || stableLength != state.stable.length) {
            stableLength = state.stable.length
            draft.text = SpannableString(live).apply {
                setSpan(ForegroundColorSpan(CaptionPalette.MUTED), state.stable.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        draft.setTextColor(CaptionPalette.SOURCE)
        draft.setPadding(0, if (rows.isEmpty()) 0 else dp(10), dp(4), dp(2))
        val current = ++version
        post {
            if (current != version) return@post
            if (follow && following && !touching) {
                scrollTo(0, (content.height - height).coerceAtLeast(0))
            } else if (!touching) anchor?.let { rows[it.key]?.let { row -> scrollTo(0, row.top + anchorOffset) } }
            adjusting = false
        }
    }

    fun reflow() {
        rows.values.forEach { it.translation.textSize = 20f; it.source.textSize = 15f }
        draft.textSize = 15f
        requestLayout()
    }

    private fun captionText(size: Float) = TextView(context).apply {
        textSize = size
        includeFontPadding = true
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private inner class Row : LinearLayout(context) {
        val translation = captionText(20f).apply { typeface = Typeface.create(Typeface.DEFAULT, 500, false) }
        val source = captionText(15f)
        private var previous: CaptionLine? = null
        init {
            orientation = VERTICAL
            setPadding(0, dp(3), dp(4), dp(13))
            addView(translation); addView(source)
        }
        fun render(line: CaptionLine) {
            if (previous == line) return
            previous = line
            val unfinished = line.outcome == null
            val reason = line.outcome?.untranslatedReason
            translation.isVisible = line.translation.isNotEmpty()
            if (translation.text.toString() != line.translation) translation.text = line.translation
            translation.setTextColor(when { reason != null -> CaptionPalette.WARNING
                unfinished -> CaptionPalette.PROGRESS; else -> CaptionPalette.TRANSLATION })
            if (source.text.toString() != line.segment.source) source.text = line.segment.source
            source.setPadding(0, if (translation.isVisible) dp(4) else 0, 0, 0)
            source.setTextColor(if (unfinished && translation.isVisible) CaptionPalette.PROGRESS else CaptionPalette.SOURCE)
            source.contentDescription = reason?.let { "${line.segment.source}。${context.getString(it.label)}" }
            val icon = reason?.let { context.getDrawable(it.icon)?.mutate()?.apply {
                setTint(CaptionPalette.WARNING); setBounds(0, 0, dp(14), dp(14))
            } }
            source.setCompoundDrawablesRelative(icon, null, null, null)
            source.compoundDrawablePadding = dp(5)
        }
    }
}
