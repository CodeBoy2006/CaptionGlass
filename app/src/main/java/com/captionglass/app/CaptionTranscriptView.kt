package com.captionglass.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.captionglass.engine.CaptionLine
import com.captionglass.engine.SegmentKey
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared by the home stage and overlay. Rows keep their identity, and every change animates each row from where it
 * was on screen: new rows roll in, streamed text settles in place, and a reader who scrolled back is left alone.
 */
internal class CaptionTranscriptView(context: Context) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = linkedMapOf<SegmentKey, Row>()
    private val draft = Draft()
    private var last = CaptureState()
    private var following = true
    private var touching = false
    private var adjusting = false
    private var returning = false
    private var glide: ValueAnimator? = null
    private var pending: Pending? = null
    private val fadePaint = Paint()
    private var fade: LinearGradient? = null

    var maximumHeight = Int.MAX_VALUE
    /** True when the host keeps this view's bottom edge fixed on screen, as a bottom-anchored overlay does. */
    var anchoredBottom = false
    var onFollowingChanged: ((Boolean) -> Unit)? = null
    val isFollowing get() = following

    /** The surface behind the transcript; history scrolled under the top edge fades into it. Null draws no fade. */
    var edgeColor: Int? = null
        set(value) {
            if (field == value) return
            field = value
            fade = value?.let { LinearGradient(0f, 0f, 0f, dp(20).toFloat(), it, it and 0x00FFFFFF, Shader.TileMode.CLAMP) }
            fadePaint.shader = fade
            invalidate()
        }

    /** Text shadows for the plate-less outline style. */
    var outlined = false
        set(value) {
            if (field == value) return
            field = value
            rows.values.forEach { it.restyle() }
            draft.restyle()
        }

    var display = CaptionDisplay.SCROLL
        set(value) {
            if (field == value) return
            field = value
            isVerticalScrollBarEnabled = value.scrolls
            overScrollMode = if (value.scrolls) OVER_SCROLL_IF_CONTENT_SCROLLS else OVER_SCROLL_NEVER
            draft.tail = !value.scrolls
            glide?.cancel()
            setFollowing(true)
            refresh()
        }

    init {
        isFillViewport = false
        clipToPadding = false
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        verticalScrollbarThumbDrawable = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = 2 * density }
        verticalScrollbarTrackDrawable = null
        scrollBarSize = dp(3)
        content.addView(draft)
        addView(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (mode == MeasureSpec.UNSPECIFIED) maximumHeight else minOf(maximumHeight, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height,
            if (mode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
    }

    // Compact displays follow the newest text by themselves; gestures pass to whatever is underneath.
    override fun onInterceptTouchEvent(event: MotionEvent) = display.scrolls && super.onInterceptTouchEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!display.scrolls) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touching = true
            glide?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            setFollowing(atBottom())
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    private fun atBottom() = content.height - height - scrollY <= dp(8)
    private fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        onFollowingChanged?.invoke(value)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (adjusting || returning || !display.scrolls) return
        setFollowing(atBottom())
    }

    /** Glides back to the newest text and resumes following. */
    fun followLatest() {
        setFollowing(true)
        glide?.cancel()
        val bottom = (content.height - height).coerceAtLeast(0)
        if (scrollY == bottom) return
        if (!CaptionMotion.enabled || !isShown) { scrollTo(0, bottom); return }
        returning = true
        glide = ValueAnimator.ofInt(scrollY, bottom).apply {
            duration = CaptionMotion.MORPH; interpolator = CaptionMotion.standard
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { returning = false }
            })
            start()
        }
    }

    fun render(state: CaptureState) {
        // Sessions publish every audio frame; only caption changes need a layout pass.
        val unchanged = state.lines == last.lines && state.stable == last.stable && state.provisional == last.provisional
        last = state
        if (!unchanged) refresh()
    }

    private fun refresh() {
        val state = last
        val lines = display.visible(state.lines)
        val animate = CaptionMotion.enabled && isAttachedToWindow && isShown && width > 0
        // Changes before the next frame share one snapshot, taken before the first of them.
        val change = pending ?: Pending(if (animate) snapshot() else emptyMap()).also { batch ->
            pending = batch
            if (display.scrolls && !following) rows.entries.firstOrNull { it.value.bottom > scrollY }?.let {
                batch.anchor = it.key
                batch.anchorOffset = scrollY - it.value.top
            }
            batch.observer = viewTreeObserver.also { it.addOnPreDrawListener(batch) }
        }
        change.follow = !display.scrolls || following
        adjusting = true
        val keys = lines.mapTo(HashSet()) { it.segment.key }
        rows.keys.filter { it !in keys }.forEach { key -> rows.remove(key)?.let { leave(it, animate) } }
        lines.forEachIndexed { index, line ->
            val existing = rows[line.segment.key]
            val row = existing ?: Row().also { rows[line.segment.key] = it; content.addView(it, index) }
            row.render(line, animate && existing != null)
            if (existing == null && animate) change.entering += row
        }
        draft.render(state.stable, state.provisional, animate, rows.isEmpty())
        invalidate()
    }

    /** Screen positions relative to whichever edge the host holds still, including any motion in flight. */
    private fun snapshot(): Map<View, Float> {
        val edge = if (anchoredBottom) height else 0
        return (0 until content.childCount).map(content::getChildAt).filter { it.isVisible }
            .associateWith { it.top + it.translationY - scrollY - edge }
    }

    private fun leave(row: Row, animate: Boolean) {
        if (!animate || row.height == 0) { content.removeView(row); return }
        content.overlay.add(row)
        row.animate().alpha(0f).translationY(row.translationY - dp(8)).setStartDelay(0).setDuration(CaptionMotion.SHORT)
            .setInterpolator(CaptionMotion.exit).withEndAction { content.overlay.remove(row) }
    }

    private fun settle(change: Pending) {
        if (pending === change) pending = null
        detach(change)
        if (change.follow) {
            glide?.cancel()
            if (!touching) scrollTo(0, (content.height - height).coerceAtLeast(0))
        } else if (!touching) change.anchor?.let { key -> rows[key]?.let { scrollTo(0, it.top + change.anchorOffset) } }
        adjusting = false
        val edge = if (anchoredBottom) height else 0
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            val before = change.before[child] ?: continue
            val delta = before - (child.top - scrollY - edge)
            if (abs(delta) < 0.5f && child.translationY == 0f) continue
            child.translationY = delta
            child.animate().translationY(0f).setStartDelay(0).setDuration(CaptionMotion.SETTLE).setInterpolator(CaptionMotion.enter)
        }
        change.entering.filter { it.parent === content }.forEach { row ->
            row.alpha = 0f
            row.translationY = dp(8).toFloat()
            row.animate().alpha(1f).translationY(0f).setStartDelay(40).setDuration(CaptionMotion.SHORT).setInterpolator(CaptionMotion.enter)
        }
    }

    /** A listener added while detached moves to the window's observer on attach, so it is removed from both. */
    private fun detach(change: Pending) {
        change.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(change)
        viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(change)
    }

    private val fadeShift = android.graphics.Matrix()

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val shader = fade ?: return
        if (!display.scrolls || scrollY <= 0) return
        val length = dp(20).toFloat()
        val top = scrollY.toFloat()
        // The canvas scrolls with the content; shifting the shader pins the fade to the viewport's top edge.
        fadeShift.setTranslate(0f, top)
        shader.setLocalMatrix(fadeShift)
        fadePaint.alpha = (255 * min(1f, top / length)).toInt()
        canvas.drawRect(0f, top, width.toFloat(), top + length, fadePaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onDetachedFromWindow() {
        pending?.let(::detach)
        pending = null
        adjusting = false
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    fun reflow() {
        rows.values.forEach { it.sizeText() }
        draft.sizeText()
        requestLayout()
    }

    private fun TextView.configure() {
        includeFontPadding = true
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun TextView.size(size: Float, line: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setLineHeight(sp(line).roundToInt())
    }

    private fun TextView.outline(on: Boolean) =
        if (on) setShadowLayer(4 * density, 0f, density, 0xE6000000.toInt()) else setShadowLayer(0f, 0f, 0f, 0)

    private inner class Pending(val before: Map<View, Float>) : ViewTreeObserver.OnPreDrawListener {
        var observer: ViewTreeObserver? = null
        var follow = true
        var anchor: SegmentKey? = null
        var anchorOffset = 0
        val entering = mutableListOf<View>()
        override fun onPreDraw(): Boolean {
            if (pending === this) settle(this) else detach(this)
            return true
        }
    }

    private inner class Row : LinearLayout(context) {
        private val tag = TextView(context).apply {
            configure()
            setTextColor(CaptionPalette.WARNING)
            compoundDrawablePadding = dp(5)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isVisible = false
        }
        private val translation = TextView(context).apply { configure(); typeface = medium }
        private val placeholder = ShimmerBar(context).apply { isVisible = false }
        private val source = TextView(context).apply { configure() }
        private var previous: CaptionLine? = null
        private var promoted = false
        private var translationColor = 0
        private var sourceColor = 0
        private var tone: ValueAnimator? = null
        private var reveal: ValueAnimator? = null
        private var revealStart = 0

        init {
            orientation = VERTICAL
            setPadding(0, dp(3), dp(4), dp(13))
            addView(tag); addView(translation); addView(placeholder); addView(source)
            sizeText()
            restyle()
        }

        fun sizeText() {
            tag.size(13f, 20f)
            translation.size(20f, 28f)
            source.size(if (promoted) 18f else 15f, if (promoted) 26f else 21f)
            placeholder.lineHeight = sp(28f).roundToInt()
        }

        fun restyle() {
            tag.outline(outlined); translation.outline(outlined); source.outline(outlined)
        }

        fun render(line: CaptionLine, animate: Boolean) {
            if (previous == line) return
            val before = previous
            previous = line
            val reason = line.outcome?.untranslatedReason
            val unfinished = line.outcome == null
            tag.isVisible = reason != null
            if (reason != null && before?.outcome?.untranslatedReason != reason) {
                tag.text = context.getString(reason.label)
                tag.setCompoundDrawablesRelative(context.getDrawable(reason.icon)?.mutate()?.apply {
                    setTint(CaptionPalette.WARNING); setBounds(0, 0, dp(14), dp(14))
                }, null, null, null)
            }
            translation.isVisible = line.translation.isNotEmpty()
            stream(before?.translation.orEmpty(), line.translation, animate)
            placeholder.isVisible = unfinished && line.translation.isEmpty()
            if (placeholder.isVisible) placeholder.fraction = estimate(line.segment.source)
            if (source.text.toString() != line.segment.source) source.text = line.segment.source
            // Without any translation, an explicit result makes the source the line worth reading.
            val promote = reason != null && line.translation.isEmpty()
            if (promote != promoted) {
                promoted = promote
                source.typeface = if (promote) medium else Typeface.DEFAULT
                sizeText()
            }
            source.contentDescription = reason?.let { "${line.segment.source}。${context.getString(it.label)}" }
            source.setPadding(0, if (translation.isVisible || placeholder.isVisible) dp(4) else 0, 0, 0)
            tint(if (unfinished || reason != null) CaptionPalette.PROGRESS else CaptionPalette.TRANSLATION,
                when {
                    promote -> CaptionPalette.TRANSLATION
                    unfinished && translation.isVisible -> CaptionPalette.PROGRESS
                    else -> CaptionPalette.SOURCE
                }, animate)
        }

        /** Appended translation fades in where it lands; earlier words never move or flash. */
        private fun stream(old: String, new: String, animate: Boolean) {
            if (translation.text.toString() == new) return
            val carrying = reveal?.isRunning == true
            reveal?.cancel()
            if (!animate || !new.startsWith(old)) { translation.text = new; return }
            val start = if (carrying) min(revealStart, old.length) else old.length
            val span = FadeSpan()
            translation.text = SpannableString(new).apply { setSpan(span, start, new.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            revealStart = start
            reveal = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CaptionMotion.SHORT; interpolator = CaptionMotion.enter
                addUpdateListener { span.alpha = it.animatedValue as Float; translation.invalidate() }
                start()
            }
        }

        /** A finished row settles from the progress tint to its final colours. */
        private fun tint(translationTo: Int, sourceTo: Int, animate: Boolean) {
            if (translationTo == translationColor && sourceTo == sourceColor) return
            tone?.cancel()
            val translationFrom = translation.currentTextColor
            val sourceFrom = source.currentTextColor
            translationColor = translationTo
            sourceColor = sourceTo
            if (!animate) { translation.setTextColor(translationTo); source.setTextColor(sourceTo); return }
            tone = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CaptionMotion.SETTLE; interpolator = CaptionMotion.standard
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    translation.setTextColor(ColorUtils.blendARGB(translationFrom, translationTo, fraction))
                    source.setTextColor(ColorUtils.blendARGB(sourceFrom, sourceTo, fraction))
                }
                start()
            }
        }

        private fun estimate(text: String): Float {
            val available = this@CaptionTranscriptView.width - dp(4)
            return if (available > 0) source.paint.measureText(text) * 1.05f / available else 0.6f
        }
    }

    /** Words still being recognised. Compact displays keep only its last two lines. */
    private inner class Draft : TextView(context) {
        private var stableLength = -1
        private var settling: ValueAnimator? = null
        var tail = false
            set(value) {
                if (field == value) return
                field = value
                maxLines = if (value) 2 else Int.MAX_VALUE
                // A bottom-gravity TextView scrolls its capped layout to the newest line.
                gravity = (if (value) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            }

        init {
            configure()
            setTextColor(CaptionPalette.SOURCE)
            sizeText()
            restyle()
        }

        fun sizeText() = size(15f, 21f)
        fun restyle() = outline(outlined)

        fun render(stable: String, provisional: String, animate: Boolean, first: Boolean) {
            val live = stable + provisional
            isVisible = live.isNotBlank()
            setPadding(0, if (first) 0 else dp(10), dp(4), dp(2))
            val shown = text.toString()
            if (live == shown && stableLength == stable.length) return
            val wasStable = stableLength.coerceIn(0, shown.length)
            stableLength = stable.length
            settling?.cancel()
            val kept = if (animate) shown.commonPrefixWith(live).length else live.length
            val styled = SpannableString(live)
            if (stable.length < live.length)
                styled.setSpan(ForegroundColorSpan(CaptionPalette.PROVISIONAL), stable.length, live.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val inkEnd = minOf(stable.length, kept)
            val ink = if (animate && inkEnd > wasStable) InkSpan().also {
                styled.setSpan(it, wasStable, inkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else null
            val appear = if (kept < live.length) FadeSpan().also {
                styled.setSpan(it, kept, live.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else null
            text = styled
            if (ink == null && appear == null) return
            settling = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CaptionMotion.SETTLE; interpolator = CaptionMotion.standard
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    ink?.progress = fraction
                    appear?.alpha = min(1f, fraction * 2f)
                    invalidate()
                }
                start()
            }
        }
    }
}

/** Colour that settles without relayout. Deliberately not a ForegroundColorSpan: stable text is never marked provisional. */
private class InkSpan : CharacterStyle(), UpdateAppearance {
    var progress = 0f
    override fun updateDrawState(paint: TextPaint) {
        paint.color = ColorUtils.blendARGB(CaptionPalette.PROVISIONAL, paint.color, progress)
    }
}

private class FadeSpan : CharacterStyle(), UpdateAppearance {
    var alpha = 0f
    override fun updateDrawState(paint: TextPaint) {
        paint.alpha = (paint.alpha * alpha).toInt()
    }
}
