package com.captionglass.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.hardware.input.InputManager
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isVisible
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two native windows. The caption card passes touches through by default; a small handle above it stays
 * touchable for dragging, and a tap on it switches the card to blocking touches (outlined) and back.
 */
internal class SubtitleOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("overlay-position", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private val translation = text(20f, CaptionPalette.TRANSLATION).apply { typeface = Typeface.create(Typeface.DEFAULT, 500, false) }
    private val source = text(15f, CaptionPalette.SOURCE)
    private val pageBar = PageBar(context)
    private val statusIcon = ImageView(context)
    private val statusLabel = TextView(context).apply { textSize = 14f; maxLines = 1 }
    private val captions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(translation); addView(source)
        addView(pageBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(8) })
    }
    private val status = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(statusIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(statusLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    }
    private val cardShape = GradientDrawable().apply { setColor(CaptionPalette.STAGE); cornerRadius = dp(18).toFloat() }
    private val card = FrameLayout(context).apply { background = cardShape; addView(captions); addView(status) }
    private val body = FrameLayout(context).apply {
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL))
    }
    private val gripShape = GradientDrawable().apply { setColor(CaptionPalette.MUTED); cornerRadius = dp(2).toFloat() }
    private val pill = FrameLayout(context).apply {
        background = GradientDrawable().apply { setColor(CaptionPalette.STAGE); cornerRadius = dp(11).toFloat() }
        addView(View(context).apply { background = gripShape }, FrameLayout.LayoutParams(dp(24), dp(4), Gravity.CENTER))
    }
    private val handle = FrameLayout(context).apply {
        addView(pill, FrameLayout.LayoutParams(dp(52), dp(22), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(6) })
        contentDescription = context.getString(R.string.overlay_handle)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
    }
    // Android 12+ checks window opacity (not background alpha) before passing touches.
    private val passThroughAlpha = if (Build.VERSION.SDK_INT >= 31) minOf(0.8f,
        context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch) else 0.8f
    private val bodyParams = parameters().apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        alpha = passThroughAlpha
    }
    private val handleParams = parameters().apply { width = dp(HANDLE_WIDTH); height = dp(HANDLE_HEIGHT) }
    private val spin = ObjectAnimator.ofFloat(statusIcon, View.ROTATION, 0f, 360f).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
    }
    private val pending = ObjectAnimator.ofFloat(translation, View.ALPHA, 0.3f, 0.85f).apply {
        duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
    }
    private val rest = Runnable { pill.animate().alpha(0.5f).setDuration(400) }
    private var attached = false
    private var passThrough = true
    private var x = 0
    private var y = 0
    private var orientation = context.resources.configuration.orientation
    private var lastState = CaptureState()
    private var rendered: CaptureState? = null
    private var startX = 0f
    private var startY = 0f
    private var originalX = 0
    private var originalY = 0
    private var moved = false

    init {
        captions.visibility = View.GONE
        card.setPadding(dp(14), dp(8), dp(16), dp(8))
        (card.layoutParams as FrameLayout.LayoutParams).width = FrameLayout.LayoutParams.WRAP_CONTENT
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (attached) position() }
        styleTouchMode()
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        textSize = size; setTextColor(color); maxLines = 2; includeFontPadding = true
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private fun parameters() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    /** Use portrait-width pages in both orientations; rotation never clips an already queued page. */
    private fun pageWidth(): Int {
        val metrics = context.resources.displayMetrics
        return (minOf(metrics.widthPixels, metrics.heightPixels, dp(640)) - dp(56)).coerceAtLeast(dp(80))
    }
    // Card padding is exactly two dp(16), so text always gets the width pages were measured at.
    private fun bodyWidth() = pageWidth() + 2 * dp(16)
    fun pages(text: String, primary: Boolean): List<String> {
        if (text.isEmpty()) return listOf("")
        val paint = if (primary) translation.paint else source.paint
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val remaining = text.substring(offset)
            val layout = StaticLayout.Builder.obtain(remaining, 0, remaining.length, paint, pageWidth())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
            val end = if (layout.lineCount > 2) layout.getLineEnd(1) else text.length - offset
            check(end > 0)
            result += text.substring(offset, offset + end)
            offset += end
        }
        return result
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (attached) return
        bodyParams.width = bodyWidth()
        loadPosition()
        body.setOnClickListener { toggleTouch() }
        handle.setOnClickListener { toggleTouch() }
        val drag = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originalX = x; originalY = y; moved = false; wake() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt(); val dy = (event.rawY - startY).toInt()
                    moved = moved || abs(dx) + abs(dy) > dp(8)
                    if (moved) {
                        x = originalX + dx; y = originalY + dy
                        // A light pull toward the horizontal centre makes symmetric placement effortless.
                        val centered = (screen().width() - bodyParams.width) / 2
                        if (abs(x - centered) < dp(12)) x = centered
                        position()
                    }
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); savePosition(); rest() }
                MotionEvent.ACTION_CANCEL -> { savePosition(); rest() }
            }
            true
        }
        body.setOnTouchListener(drag)
        handle.setOnTouchListener(drag)
        position()
        windows.addView(body, bodyParams)
        try { windows.addView(handle, handleParams); attached = true }
        catch (e: Exception) { windows.removeView(body); throw e }
        rest()
        rendered = null
        render(lastState)
    }

    private fun wake() { pill.removeCallbacks(rest); pill.animate().cancel(); pill.alpha = 1f }
    private fun rest() { pill.removeCallbacks(rest); pill.postDelayed(rest, 2_500) }

    private fun toggleTouch() {
        passThrough = !passThrough
        bodyParams.flags = if (passThrough) bodyParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else bodyParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        // Only a pass-through card must stay under the obscuring limit; a blocking card can be fully opaque.
        bodyParams.alpha = if (passThrough) passThroughAlpha else 1f
        styleTouchMode()
        handle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (attached) windows.updateViewLayout(body, bodyParams)
    }
    private fun styleTouchMode() {
        if (passThrough) cardShape.setStroke(0, Color.TRANSPARENT)
        else cardShape.setStroke((1.5f * density).roundToInt(), CaptionPalette.ACCENT)
        gripShape.setColor(if (passThrough) CaptionPalette.MUTED else CaptionPalette.ACCENT)
        if (Build.VERSION.SDK_INT >= 30) handle.stateDescription =
            context.getString(if (passThrough) R.string.overlay_pass_through else R.string.overlay_blocking)
    }

    fun render(state: CaptureState) {
        lastState = state
        if (!attached) return
        val previous = rendered
        if (previous != null && previous.page == state.page && previous.stable == state.stable &&
            previous.provisional == state.provisional && previous.status == state.status) return
        rendered = state
        val page = state.page
        val live = state.stable + state.provisional
        when {
            page != null -> {
                pending.cancel(); translation.alpha = 1f
                val reason = page.caption.untranslatedReason
                if (page.translation != null) line(translation, page.translation, CaptionPalette.TRANSLATION)
                else line(translation, reason?.let { context.getString(it.label) }, CaptionPalette.WARNING, reason?.icon)
                line(source, page.source, CaptionPalette.SOURCE)
                pageBar.show(page.index, page.total)
                showCaptions()
            }
            live.isNotBlank() -> {
                line(translation, context.getString(R.string.overlay_pending), CaptionPalette.TRANSLATION)
                if (!pending.isRunning) pending.start()
                val tail = SpannableString(pages(live, false).last())
                val provisionalStart = (tail.length - state.provisional.length).coerceAtLeast(0)
                if (provisionalStart < tail.length)
                    tail.setSpan(ForegroundColorSpan(CaptionPalette.MUTED), provisionalStart, tail.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line(source, tail, CaptionPalette.SOURCE)
                pageBar.show(0, 0)
                showCaptions()
            }
            else -> {
                pending.cancel(); translation.alpha = 1f
                showStatus(state.status)
            }
        }
    }

    private fun line(view: TextView, text: CharSequence?, color: Int, icon: Int? = null) {
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        view.text = text
        view.setTextColor(color)
        val drawable = icon?.let { context.getDrawable(it)?.mutate()?.apply { setTint(color); setBounds(0, 0, dp(18), dp(18)) } }
        view.setCompoundDrawablesRelative(drawable, null, null, null)
        view.compoundDrawablePadding = dp(6)
    }

    private fun showCaptions() {
        spin.cancel()
        if (captions.isVisible) return
        status.visibility = View.GONE
        captions.visibility = View.VISIBLE
        card.setPadding(dp(16), dp(10), dp(16), dp(12))
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.MATCH_PARENT }
        captions.alpha = 0f
        captions.animate().alpha(1f).setDuration(160)
    }

    /** Before any words arrive the card shrinks to a compact chip: one symbol and a short word. */
    private fun showStatus(value: CaptureStatus) {
        val color = when (value) {
            CaptureStatus.HEARING -> CaptionPalette.ACCENT
            CaptureStatus.SILENT -> CaptionPalette.WARNING
            else -> CaptionPalette.SOURCE
        }
        statusIcon.setImageResource(value.icon)
        statusIcon.setColorFilter(color)
        statusLabel.text = context.getString(value.label)
        statusLabel.setTextColor(color)
        if (value == CaptureStatus.PREPARING) { if (!spin.isRunning) spin.start() }
        else { spin.cancel(); statusIcon.rotation = 0f }
        if (status.isVisible) return
        captions.visibility = View.GONE
        status.visibility = View.VISIBLE
        card.setPadding(dp(14), dp(8), dp(16), dp(8))
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.WRAP_CONTENT }
    }

    fun configurationChanged() {
        savePosition(); orientation = context.resources.configuration.orientation
        translation.textSize = 20f; source.textSize = 15f; statusLabel.textSize = 14f
        bodyParams.width = bodyWidth()
        loadPosition(); position()
        rendered = null
        render(lastState)
    }
    private fun screen(): Rect = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds else
        Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
    /** Defaults: centred just below a top-aligned 16:9 video in portrait, near the bottom edge in landscape. */
    private fun loadPosition() {
        val bounds = screen()
        val landscape = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        x = preferences.getInt("x-$orientation", (bounds.width() - bodyParams.width) / 2)
        y = preferences.getInt("y-$orientation", if (landscape) bounds.height() - dp(150) else bounds.height() * 36 / 100)
    }
    private fun savePosition() { preferences.edit { putInt("x-$orientation", x); putInt("y-$orientation", y) } }
    private fun position() {
        val bounds = screen()
        val insets = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else android.graphics.Insets.NONE
        val above = dp(HANDLE_HEIGHT)
        x = x.coerceIn(insets.left, maxOf(insets.left, bounds.width() - bodyParams.width - insets.right))
        y = y.coerceIn(insets.top + above, maxOf(insets.top + above, bounds.height() - maxOf(body.height, dp(120)) - insets.bottom))
        bodyParams.x = x; bodyParams.y = y
        handleParams.x = x + (bodyParams.width - handleParams.width) / 2; handleParams.y = y - above
        if (attached) { windows.updateViewLayout(body, bodyParams); windows.updateViewLayout(handle, handleParams) }
    }
    fun close() {
        if (!attached) return
        savePosition(); attached = false
        spin.cancel(); pending.cancel(); pill.removeCallbacks(rest)
        windows.removeView(handle); windows.removeView(body)
    }

    private companion object {
        const val HANDLE_WIDTH = 96
        const val HANDLE_HEIGHT = 48
    }
}

/** Thin segments under a long caption: which page is showing and how many follow. */
private class PageBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CaptionPalette.TRANSLATION }
    private val rect = RectF()
    private var index = 0
    private var total = 0

    fun show(index: Int, total: Int) {
        this.index = index; this.total = total
        visibility = if (total > 1) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (total < 2) return
        val gap = 4 * resources.displayMetrics.density
        val radius = height / 2f
        val segment = (width - gap * (total - 1)) / total
        if (segment < gap) {
            paint.alpha = 45; rect.set(0f, 0f, width.toFloat(), height.toFloat()); canvas.drawRoundRect(rect, radius, radius, paint)
            paint.alpha = 230; rect.right = width * (index + 1f) / total; canvas.drawRoundRect(rect, radius, radius, paint)
            return
        }
        repeat(total) { i ->
            paint.alpha = when { i == index -> 230; i < index -> 110; else -> 45 }
            val left = i * (segment + gap)
            rect.set(left, 0f, left + segment, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
