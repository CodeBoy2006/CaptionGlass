package com.captionglass.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
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
 * Two native windows. The readable card scrolls; the handle moves it or switches touch passthrough.
 */
internal class SubtitleOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("overlay-position", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    val captions = CaptionTranscriptView(context)
    private val statusIcon = ImageView(context)
    private val statusLabel = TextView(context).apply { textSize = 14f; maxLines = 1 }
    private val status = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(statusIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(statusLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    }
    private val cardShape = GradientDrawable().apply { setColor(CaptionPalette.STAGE); cornerRadius = dp(18).toFloat() }
    private val card = FrameLayout(context).apply { background = cardShape; addView(captions); addView(status) }
    private val body = object : FrameLayout(context) {
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            if (attached) configurationChanged()
        }
    }.apply {
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
    private var passThrough = preferences.getBoolean("pass-through", false)
    private val bodyParams = parameters().apply {
        if (passThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        alpha = if (passThrough) passThroughAlpha else 1f
    }
    private val handleParams = parameters().apply { width = dp(HANDLE_WIDTH); height = dp(HANDLE_HEIGHT) }
    private val spin = ObjectAnimator.ofFloat(statusIcon, View.ROTATION, 0f, 360f).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
    }
    private val rest = Runnable { pill.animate().alpha(0.5f).setDuration(400) }
    private var attached = false
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

    private fun parameters() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    private fun bodyWidth(): Int {
        val metrics = context.resources.displayMetrics
        return (minOf(metrics.widthPixels, metrics.heightPixels, dp(640)) - dp(24)).coerceAtLeast(dp(120))
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (attached) return
        bodyParams.width = bodyWidth()
        loadPosition()
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
        preferences.edit { putBoolean("pass-through", passThrough) }
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
        if (previous != null && previous.lines == state.lines && previous.stable == state.stable &&
            previous.provisional == state.provisional && previous.status == state.status) return
        rendered = state
        if (state.lines.isNotEmpty() || state.stable.isNotBlank() || state.provisional.isNotBlank()) {
            captions.maximumHeight = minOf(dp(236), screen().height() / 3)
            captions.render(state)
            showCaptions()
        } else showStatus(state.status)
    }

    private fun showCaptions() {
        spin.cancel()
        if (captions.isVisible) return
        status.visibility = View.GONE
        captions.visibility = View.VISIBLE
        card.setPadding(dp(16), dp(10), dp(16), dp(12))
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.MATCH_PARENT }
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
        captions.reflow(); statusLabel.textSize = 14f
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
        spin.cancel(); pill.removeCallbacks(rest)
        windows.removeView(handle); windows.removeView(body)
    }

    private companion object {
        const val HANDLE_WIDTH = 96
        const val HANDLE_HEIGHT = 48
    }
}
