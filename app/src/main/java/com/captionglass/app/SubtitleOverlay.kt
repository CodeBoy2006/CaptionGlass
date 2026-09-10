package com.captionglass.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.hardware.input.InputManager
import android.os.Build
import android.text.StaticLayout
import android.text.Layout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import com.captionglass.engine.UntranslatedReason

/** Two native windows: the caption can pass touches through while its small handle stays usable. */
internal class SubtitleOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("overlay-position", Context.MODE_PRIVATE)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val translation = text(20f, Color.WHITE)
    private val source = text(16f, Color.rgb(205, 221, 211))
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(0xE6192922.toInt()); cornerRadius = dp(12).toFloat() }
        addView(translation); addView(source)
    }
    private val handle = text(12f, Color.WHITE).apply {
        text = "字幕 · 拖动 / 点击切换穿透"
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(0xE6192922.toInt()); cornerRadius = dp(12).toFloat() }
        contentDescription = "拖动字幕位置，点击切换字幕触摸穿透"
        isClickable = true
    }
    private val bodyParams = parameters().apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // Android 12+ checks window opacity (not background alpha) before passing touches.
        alpha = if (Build.VERSION.SDK_INT >= 31) minOf(0.8f,
            context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch) else 0.8f
    }
    private val handleParams = parameters().apply { width = dp(224); height = dp(48) }
    private var attached = false
    private var passThrough = true
    private var x = 0
    private var y = 0
    private var orientation = context.resources.configuration.orientation
    private var lastState = CaptureState()
    private var rendered: CaptureState? = null

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
        loadPosition()
        bodyParams.width = pageWidth() + dp(24)
        body.setOnClickListener { toggleTouch() }
        handle.setOnClickListener { toggleTouch() }
        var startX = 0f; var startY = 0f; var originalX = 0; var originalY = 0
        var moved = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; originalX = x; originalY = y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt(); val dy = (event.rawY - startY).toInt()
                    moved = moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)
                    if (moved) { x = originalX + dx; y = originalY + dy; position() }
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); savePosition() }
                MotionEvent.ACTION_CANCEL -> savePosition()
            }
            true
        }
        position()
        windows.addView(body, bodyParams)
        try { windows.addView(handle, handleParams); attached = true }
        catch (e: Exception) { windows.removeView(body); throw e }
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (attached) position() }
        render(lastState)
    }

    private fun toggleTouch() {
        passThrough = !passThrough
        bodyParams.flags = if (passThrough) bodyParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else bodyParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        handle.text = if (passThrough) "字幕 · 穿透已开启" else "字幕 · 点击开启穿透"
        windows.updateViewLayout(body, bodyParams)
    }

    fun render(state: CaptureState) {
        lastState = state
        val previous = rendered
        if (previous != null && previous.page == state.page && previous.stable == state.stable &&
            previous.provisional == state.provisional && previous.message == state.message) return
        rendered = state
        val page = state.page
        translation.text = page?.translation ?: page?.caption?.untranslatedReason?.label() ?: "等待译文…"
        source.text = page?.source ?: pages((state.stable + state.provisional).ifBlank { state.message }, false).last()
        if (page != null && page.total > 1) handle.text = context.getString(R.string.subtitle_page, page.index + 1, page.total)
        else handle.text = if (passThrough) "字幕 · 穿透已开启" else "字幕 · 点击开启穿透"
    }

    fun configurationChanged() {
        savePosition(); orientation = context.resources.configuration.orientation
        translation.textSize = 20f; source.textSize = 16f
        loadPosition(); bodyParams.width = pageWidth() + dp(24); position()
    }
    private fun loadPosition() {
        x = preferences.getInt("x-$orientation", dp(16))
        y = preferences.getInt("y-$orientation", context.resources.displayMetrics.heightPixels - dp(260))
    }
    private fun savePosition() { preferences.edit { putInt("x-$orientation", x); putInt("y-$orientation", y) } }
    private fun position() {
        val bounds = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds else
            android.graphics.Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val insets = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else android.graphics.Insets.NONE
        x = x.coerceIn(insets.left, maxOf(insets.left, bounds.width() - bodyParams.width - insets.right))
        y = y.coerceIn(insets.top + dp(48), maxOf(insets.top + dp(48), bounds.height() - maxOf(body.height, dp(120)) - insets.bottom))
        bodyParams.x = x; bodyParams.y = y
        handleParams.x = x; handleParams.y = y - dp(48)
        if (attached) { windows.updateViewLayout(body, bodyParams); windows.updateViewLayout(handle, handleParams) }
    }
    fun close() {
        if (!attached) return
        savePosition(); attached = false
        windows.removeView(handle); windows.removeView(body)
    }
}

internal fun UntranslatedReason.label() = when (this) {
    UntranslatedReason.BACKLOG -> "未翻译：翻译积压"
    UntranslatedReason.FAILED -> "未翻译：翻译失败"
    UntranslatedReason.TIMED_OUT -> "未翻译：等待超时"
    UntranslatedReason.STOPPED -> "未翻译：已停止"
    UntranslatedReason.SUPERSEDED -> "已被原文修订替代"
}
