package com.captionglass.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RotateDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.view.isVisible
import com.captionglass.engine.SegmentKey
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Two native windows. The caption card is anchored by its top or bottom edge, whichever is nearer the middle of
 * the screen it sits in, so growing text never pushes the reading line around. The handle rides the card's top edge.
 */
internal class SubtitleOverlay(private val context: Context) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val preferences = context.getSharedPreferences("overlay-position", Context.MODE_PRIVATE)
    private val look = CaptionPreferences.of(context)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    val captions = CaptionTranscriptView(context)
    private val signal = StatusSignal(context)
    private val statusLabel = TextView(context).apply {
        textSize = 14f; maxLines = 1; typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val status = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(signal, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(statusLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    }
    private val latest = TextView(context).apply {
        text = context.getString(R.string.overlay_latest)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        setTextColor(CaptionPalette.ACCENT)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply {
            setColor(0xF2121815.toInt()); cornerRadius = dp(14).toFloat()
            setStroke(dp(1), ColorUtils.setAlphaComponent(CaptionPalette.ACCENT, 102))
        }
        compoundDrawablePadding = dp(2)
        setCompoundDrawablesRelative(RotateDrawable().apply {
            drawable = context.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply { setTint(CaptionPalette.ACCENT) }
            fromDegrees = 90f; toDegrees = 90f
            setBounds(0, 0, dp(18), dp(18))
        }, null, null, null)
        setOnClickListener { postponeCollapse(); captions.followLatest() }
    }
    private val card = CaptionCard(context, status, captions, latest)
    private val handle = HandleView(context).apply {
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
    private val lookListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CaptionPreferences.DISPLAY || key == CaptionPreferences.STYLE || key == CaptionPreferences.SIZE) applyLook()
    }
    private var attached = false
    private var x = 0
    /** Screen y of the anchored plate edge: its top when [anchorBottom] is false, otherwise its bottom. */
    private var edge = 0
    private var anchorBottom = false
    private var orientation = context.resources.configuration.orientation
    private var lastState = CaptureState()
    private var rendered: CaptureState? = null
    private var shownStatus: CaptureStatus? = null
    private var latestShown = false
    private var startX = 0f
    private var startY = 0f
    private var originalX = 0
    private var originalTop = 0
    private var moved = false
    private var snapped = false
    private var handlingTouch = false
    private var clearedThrough: SegmentKey? = null
    private var clearedDraft: Pair<String, String>? = null
    private val collapse = Runnable {
        if (!attached || card.chip) return@Runnable
        if (handlingTouch || captions.isInteracting) {
            postponeCollapse()
            return@Runnable
        }
        // Only dismiss this surface. Session records and export snapshots retain all their content.
        lastState.lines.lastOrNull()?.segment?.key?.let { clearedThrough = it }
        clearedDraft = lastState.stable to lastState.provisional
        rendered = null
        render(lastState)
    }

    init {
        card.onPlateMoved = { placeHandle() }
        card.onConfiguration = { if (attached) configurationChanged() }
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (attached) position() }
        captions.onFollowingChanged = { updateLatest() }
        captions.onInteraction = { postponeCollapse() }
        styleTouchMode()
    }

    private fun parameters() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        // Offsets are measured from the display edges for both gravities, never from inside the system bars.
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private fun bodyWidth(): Int {
        val metrics = context.resources.displayMetrics
        return (minOf(metrics.widthPixels, metrics.heightPixels, dp(640)) - dp(24)).coerceAtLeast(dp(120))
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (attached) return
        bodyParams.width = bodyWidth()
        applyLook()
        loadPosition()
        handle.setOnClickListener { toggleTouch() }
        handle.setOnTouchListener { view, event ->
            postponeCollapse()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handlingTouch = true
                    startX = event.rawX; startY = event.rawY; originalX = x; originalTop = plateTop()
                    moved = false; snapped = false
                    handle.wake(); handle.press(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt(); val dy = (event.rawY - startY).toInt()
                    moved = moved || abs(dx) + abs(dy) > dp(8)
                    if (moved) {
                        x = originalX + dx
                        // A light pull toward the horizontal centre makes symmetric placement effortless.
                        val centered = (screen().width() - bodyParams.width) / 2
                        val snap = abs(x - centered) < dp(12)
                        if (snap) x = centered
                        if (snap && !snapped) { handle.flash(); handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        snapped = snap
                        moveTop(originalTop + dy)
                    }
                }
                MotionEvent.ACTION_UP -> { handlingTouch = false; handle.press(false); if (!moved) view.performClick(); savePosition(); handle.sleepLater() }
                MotionEvent.ACTION_CANCEL -> { handlingTouch = false; handle.press(false); savePosition(); handle.sleepLater() }
            }
            true
        }
        position()
        windows.addView(card, bodyParams)
        try { windows.addView(handle, handleParams); attached = true }
        catch (e: Exception) { windows.removeView(card); throw e }
        look.registerOnSharedPreferenceChangeListener(lookListener)
        card.blocking(!passThrough, animate = false)
        handle.blocking = !passThrough
        handle.sleepLater()
        rendered = null
        render(lastState)
        if (CaptionMotion.enabled) {
            card.alpha = 0f; card.scaleX = 0.96f; card.scaleY = 0.96f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(220).setInterpolator(CaptionMotion.enter)
            handle.alpha = 0f
            handle.animate().alpha(1f).setStartDelay(80).setDuration(CaptionMotion.SHORT)
        }
    }

    /** Level of the captured playback, 0..1. Only the listening chip shows it. */
    fun level(value: Float) = signal.level(value)

    private fun applyLook() {
        captions.display = CaptionPreferences.display(context)
        captions.scale = CaptionPreferences.size(context).scale
        captions.maximumHeight = readingHeight()
        val outline = CaptionPreferences.style(context) == CaptionStyle.OUTLINE
        captions.outlined = outline
        captions.edgeColor = if (outline) null else CaptionPalette.PLATE_TOP
        card.outline = outline
        styleTouchMode()
        updateLatest()
        postponeCollapse()
    }

    private fun toggleTouch() {
        postponeCollapse()
        passThrough = !passThrough
        preferences.edit { putBoolean("pass-through", passThrough) }
        bodyParams.flags = if (passThrough) bodyParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else bodyParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        // Only a pass-through card must stay under the obscuring limit; a blocking card can be fully opaque.
        bodyParams.alpha = if (passThrough) passThroughAlpha else 1f
        styleTouchMode()
        card.blocking(!passThrough, animate = true)
        handle.blocking = !passThrough
        handle.hint(if (passThrough) R.drawable.ic_touch_app else R.drawable.ic_back_hand)
        handle.performHapticFeedback(when {
            passThrough -> HapticFeedbackConstants.CLOCK_TICK
            Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        })
        // A pass-through card cannot be scrolled, so it returns to the newest text.
        if (passThrough) captions.followLatest()
        updateLatest()
        if (attached) windows.updateViewLayout(card, bodyParams)
    }

    private fun styleTouchMode() {
        if (Build.VERSION.SDK_INT >= 30) handle.stateDescription = context.getString(when {
            passThrough -> R.string.overlay_pass_through
            captions.display.scrolls -> R.string.overlay_scrollable
            else -> R.string.overlay_blocking
        })
    }

    private fun updateLatest() {
        val show = attached && !card.chip && captions.display.scrolls && !captions.isFollowing && !passThrough
        if (show == latestShown) return
        latestShown = show
        latest.animate().cancel()
        if (show) {
            latest.visibility = View.VISIBLE
            if (!CaptionMotion.enabled) { latest.alpha = 1f; latest.translationY = 0f; return }
            latest.alpha = 0f; latest.translationY = dp(6).toFloat()
            latest.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(CaptionMotion.SHORT).setInterpolator(CaptionMotion.enter)
        } else if (!CaptionMotion.enabled) latest.visibility = View.GONE
        else latest.animate().alpha(0f).setStartDelay(0).setDuration(CaptionMotion.MICRO).setInterpolator(CaptionMotion.exit)
            .withEndAction { latest.visibility = View.GONE }
    }

    private fun postponeCollapse() {
        main.removeCallbacks(collapse)
        if (attached && !card.chip) main.postDelayed(collapse, IDLE_MILLIS)
    }

    fun render(state: CaptureState) {
        lastState = state
        if (!attached) return
        val previous = rendered
        if (previous != null && previous.lines == state.lines && previous.stable == state.stable &&
            previous.provisional == state.provisional && previous.status == state.status) return
        rendered = state
        val captionsChanged = previous == null || previous.lines != state.lines || previous.stable != state.stable ||
            previous.provisional != state.provisional
        if (clearedDraft != (state.stable to state.provisional)) clearedDraft = null
        val visible = state.copy(
            lines = clearedThrough?.let { cleared -> state.lines.filter {
                it.segment.key.sessionId != cleared.sessionId || it.segment.key.sequence > cleared.sequence
            } } ?: state.lines,
            stable = if (clearedDraft == null) state.stable else "",
            provisional = if (clearedDraft == null) state.provisional else "",
        )
        if (visible.lines.isNotEmpty() || visible.stable.isNotBlank() || visible.provisional.isNotBlank()) {
            captions.maximumHeight = readingHeight()
            if (card.chip) { card.showCaptions(); updateLatest() }
            captions.render(visible)
            if (captionsChanged) postponeCollapse()
        } else {
            main.removeCallbacks(collapse)
            captions.render(visible)
            showStatus(state.status)
        }
    }

    /** Room for about four reading lines at the chosen caption size, and never more than a third of the screen. */
    private fun readingHeight() = minOf((dp(236) * captions.scale).roundToInt(), screen().height() / 3)

    /** Before any words arrive the card is a compact chip: a live symbol and a short word. */
    private fun showStatus(value: CaptureStatus) {
        val warning = value == CaptureStatus.SILENT || value.isProblem
        val labelColor = when {
            value == CaptureStatus.HEARING -> CaptionPalette.ACCENT
            warning -> CaptionPalette.WARNING
            value.isLive -> CaptionPalette.SOURCE
            else -> CaptionPalette.MUTED
        }
        when (value) {
            CaptureStatus.PREPARING -> signal.show(StatusSignal.Kind.DOT, null, CaptionPalette.ACCENT)
            CaptureStatus.HEARING -> signal.show(StatusSignal.Kind.METER, null, CaptionPalette.ACCENT)
            else -> signal.show(StatusSignal.Kind.ICON, value.icon, labelColor)
        }
        statusLabel.text = context.getString(value.label)
        statusLabel.setTextColor(labelColor)
        card.showStatus(when {
            value == CaptureStatus.PREPARING -> CaptionPlate.Glow.ORBIT
            value == CaptureStatus.WAITING -> CaptionPlate.Glow.BREATHE
            warning -> CaptionPlate.Glow.WARN
            else -> CaptionPlate.Glow.NONE
        }, nudge = value.isProblem && shownStatus != value)
        shownStatus = value
        updateLatest()
    }

    fun configurationChanged() {
        savePosition(); orientation = context.resources.configuration.orientation
        captions.reflow(); statusLabel.textSize = 14f; latest.textSize = 13f
        bodyParams.width = bodyWidth()
        loadPosition(); position()
        if (attached) windows.updateViewLayout(card, bodyParams)
        rendered = null
        render(lastState)
    }

    private fun screen(): Rect = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds else
        Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)

    /** Bottom of the frame BOTTOM-gravity offsets count from. Before Android 11 an overlay's frame ends above the navigation bar. */
    @Suppress("DEPRECATION")
    private fun frameBottom(bounds: Rect) = if (Build.VERSION.SDK_INT >= 30) bounds.height()
        else bounds.height() - (card.rootWindowInsets?.stableInsetBottom ?: 0)

    /** Defaults: below a top-aligned 16:9 video in portrait, and resting near the bottom edge in landscape. */
    private fun loadPosition() {
        val bounds = screen()
        x = preferences.getInt("x-$orientation", (bounds.width() - bodyParams.width) / 2)
        when {
            preferences.contains("edge-$orientation") -> {
                edge = preferences.getInt("edge-$orientation", 0)
                anchorBottom = preferences.getBoolean("bottom-$orientation", false)
            }
            preferences.contains("y-$orientation") -> {
                // Positions saved before edge anchoring stored the card top.
                val top = preferences.getInt("y-$orientation", 0)
                anchorBottom = top + dp(60) > bounds.height() / 2
                edge = if (anchorBottom) top + dp(124) else top
            }
            orientation == Configuration.ORIENTATION_LANDSCAPE -> { anchorBottom = true; edge = bounds.height() - dp(24) }
            else -> { anchorBottom = false; edge = bounds.height() * 36 / 100 }
        }
        card.anchorBottom = anchorBottom
        captions.anchoredBottom = anchorBottom
    }

    private fun savePosition() = preferences.edit {
        putInt("x-$orientation", x); putInt("edge-$orientation", edge); putBoolean("bottom-$orientation", anchorBottom)
        remove("y-$orientation")
    }

    private fun plateTop() = if (anchorBottom) edge - card.plateHeight.roundToInt() else edge

    /** Dragging picks the anchor from where the card's centre is, keeping the plate exactly under the finger. */
    private fun moveTop(top: Int) {
        val plate = max(card.targetHeight, dp(40))
        anchorBottom = top + plate / 2 > screen().height() / 2
        edge = if (anchorBottom) top + plate else top
        card.anchorBottom = anchorBottom
        captions.anchoredBottom = anchorBottom
        position()
    }

    private fun position() {
        val bounds = screen()
        val insets = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else android.graphics.Insets.NONE
        val plate = max(card.targetHeight, dp(40))
        val highest = insets.top + dp(HANDLE_HEIGHT) - handle.overlap
        val lowest = bounds.height() - insets.bottom
        x = x.coerceIn(insets.left, max(insets.left, bounds.width() - bodyParams.width - insets.right))
        edge = if (anchorBottom) edge.coerceIn(min(lowest, highest + plate), lowest)
            else edge.coerceIn(highest, max(highest, lowest - plate))
        val gravity = Gravity.START or if (anchorBottom) Gravity.BOTTOM else Gravity.TOP
        val y = if (anchorBottom) frameBottom(bounds) - edge else edge
        val changed = bodyParams.x != x || bodyParams.y != y || bodyParams.gravity != gravity
        bodyParams.x = x; bodyParams.y = y; bodyParams.gravity = gravity
        if (attached && changed) windows.updateViewLayout(card, bodyParams)
        placeHandle()
    }

    /** Follows the plate's top edge frame by frame while a bottom-anchored card changes height. */
    private fun placeHandle() {
        val handleX = x + (bodyParams.width - handleParams.width) / 2
        val handleY = plateTop() - (dp(HANDLE_HEIGHT) - handle.overlap)
        if (handleParams.x == handleX && handleParams.y == handleY) return
        handleParams.x = handleX; handleParams.y = handleY
        if (attached) windows.updateViewLayout(handle, handleParams)
    }

    fun close() {
        main.removeCallbacks(collapse)
        if (!attached) return
        savePosition(); attached = false
        look.unregisterOnSharedPreferenceChangeListener(lookListener)
        card.stopEffects()
        var removed = false
        val remove = Runnable {
            if (removed) return@Runnable
            removed = true
            runCatching { windows.removeView(handle) }
            runCatching { windows.removeView(card) }
        }
        if (!CaptionMotion.enabled || !card.isShown) { remove.run(); return }
        handle.animate().alpha(0f).setStartDelay(0).setDuration(CaptionMotion.MICRO)
        card.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).setStartDelay(0).setDuration(160).setInterpolator(CaptionMotion.exit)
            .withEndAction(remove)
        // Frames stop while the display is off; removal must not wait for them.
        main.postDelayed(remove, 400)
    }

    private companion object {
        const val HANDLE_WIDTH = 96
        const val HANDLE_HEIGHT = 48
        const val IDLE_MILLIS = 15_000L
    }
}

/**
 * The body window. It draws the plate itself so the plate can morph between chip and card and between caption
 * heights. The window only resizes when a change starts (to grow) or ends (to shrink), never on every frame.
 */
@SuppressLint("ViewConstructor") // Built in code around views the overlay owns; never inflated.
private class CaptionCard(context: Context, private val status: View, private val captions: View, private val latest: View) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val plate = CaptionPlate(density)
    private val rect = RectF()
    private val spanX = Tween(this, 0f)
    private val spanY = Tween(this, 0f) { onPlateMoved?.invoke() }
    private val fill = Tween(this, 1f) { plate.fill = it }
    private val ring = Tween(this, 0f) { plate.ring = it }
    private val ringAlpha = Tween(this, 0f) { plate.ringAlpha = it }
    private var glow: ValueAnimator? = null
    private var shake: ValueAnimator? = null
    private var targetWidth = 0
    private var hold = 0
    private var placed = false
    private var morphing = false

    var onPlateMoved: (() -> Unit)? = null
    var onConfiguration: (() -> Unit)? = null
    var chip = true
        private set
    var targetHeight = 0
        private set
    val plateHeight get() = spanY.value
    var anchorBottom = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout(); invalidate()
        }
    var outline = false
        set(value) {
            if (field == value) return
            field = value
            fill.to(if (value && !chip) 0f else 1f, CaptionMotion.SHORT)
        }

    init {
        addView(status); addView(captions); addView(latest)
        captions.alpha = 0f
        captions.visibility = INVISIBLE
        latest.visibility = GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onConfiguration?.invoke()
    }

    fun showCaptions() {
        if (!chip) return
        chip = false; morphing = true
        status.fade(false, 0)
        captions.fade(true, 90)
        if (outline) fill.to(0f, CaptionMotion.SHORT)
        runGlow(CaptionPlate.Glow.NONE)
        requestLayout()
    }

    fun showStatus(glow: CaptionPlate.Glow, nudge: Boolean) {
        if (!chip) {
            chip = true; morphing = true
            captions.fade(false, 0)
            status.fade(true, 120)
            fill.to(1f, CaptionMotion.SHORT)
            requestLayout()
        }
        if (plate.glow != glow) runGlow(glow)
        if (nudge) nudge()
    }

    fun blocking(on: Boolean, animate: Boolean) {
        if (!animate) { ring.snap(if (on) 1f else 0f); ringAlpha.snap(if (on) 1f else 0f); return }
        if (on) { ringAlpha.snap(1f); ring.snap(0f); ring.to(1f, CaptionMotion.MORPH, CaptionMotion.enter) }
        else ringAlpha.to(0f, 160L, CaptionMotion.exit) { ring.snap(0f) }
    }

    fun stopEffects() { glow?.cancel(); shake?.cancel() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        status.measure(MeasureSpec.makeMeasureSpec(max(0, available - dp(28)), MeasureSpec.AT_MOST), loose)
        captions.measure(MeasureSpec.makeMeasureSpec(max(0, available - dp(32)), MeasureSpec.EXACTLY), loose)
        latest.measure(loose, loose)
        targetWidth = if (chip) min(available, status.measuredWidth + dp(28)) else available
        targetHeight = if (chip) dp(40) else dp(12) + captions.measuredHeight + dp(14)
        // A shrinking plate keeps the window at the size currently drawn until the animation lands.
        val drawn = ceil(spanY.value).toInt()
        if (placed && CaptionMotion.enabled && isShown && targetHeight < drawn) hold = max(hold, drawn)
        setMeasuredDimension(available, max(targetHeight, hold))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        if (!placed || !CaptionMotion.enabled || !isShown) {
            placed = true
            spanX.snap(targetWidth.toFloat()); spanY.snap(targetHeight.toFloat())
            release()
        } else {
            val duration = if (morphing) CaptionMotion.MORPH else CaptionMotion.SETTLE
            spanX.to(targetWidth.toFloat(), duration)
            spanY.to(targetHeight.toFloat(), duration) { release() }
        }
        morphing = false
        val plateTop = if (anchorBottom) h - targetHeight else 0
        val statusLeft = (w - status.measuredWidth) / 2 - dp(2)
        val statusTop = plateTop + (dp(40) - status.measuredHeight) / 2
        status.layout(statusLeft, statusTop, statusLeft + status.measuredWidth, statusTop + status.measuredHeight)
        captions.layout(dp(16), plateTop + dp(12), dp(16) + captions.measuredWidth, plateTop + dp(12) + captions.measuredHeight)
        val latestLeft = (w - latest.measuredWidth) / 2
        val latestBottom = plateTop + targetHeight - dp(10)
        latest.layout(latestLeft, latestBottom - latest.measuredHeight, latestLeft + latest.measuredWidth, latestBottom)
        onPlateMoved?.invoke()
    }

    private fun release() {
        if (hold == 0) return
        hold = 0
        post { requestLayout() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val drawnWidth = spanX.value
        val drawnHeight = spanY.value
        val plateTop = if (anchorBottom) height - drawnHeight else 0f
        rect.set((width - drawnWidth) / 2, plateTop, (width + drawnWidth) / 2, plateTop + drawnHeight)
        plate.drawFill(canvas, rect)
        canvas.withClip(rect) { super.dispatchDraw(this) }
        plate.drawEdge(canvas, rect)
    }

    private fun View.fade(shown: Boolean, delay: Long) {
        animate().cancel()
        if (!CaptionMotion.enabled) {
            alpha = if (shown) 1f else 0f
            visibility = if (shown) VISIBLE else INVISIBLE
            return
        }
        if (shown) {
            visibility = VISIBLE
            animate().alpha(1f).setStartDelay(delay).setDuration(CaptionMotion.SHORT).setInterpolator(CaptionMotion.enter)
        } else animate().alpha(0f).setStartDelay(delay).setDuration(CaptionMotion.MICRO).setInterpolator(CaptionMotion.exit)
            .withEndAction { visibility = INVISIBLE }
    }

    /** Edge light loops only while the chip waits; captions on screen always get a still plate. */
    private fun runGlow(kind: CaptionPlate.Glow) {
        glow?.cancel(); glow = null
        plate.glow = kind
        plate.glowPhase = if (kind == CaptionPlate.Glow.BREATHE) 0.5f else 0f
        invalidate()
        if (!CaptionMotion.enabled || !isAttachedToWindow) return
        glow = when (kind) {
            CaptionPlate.Glow.ORBIT -> ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            }
            CaptionPlate.Glow.BREATHE -> ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_200; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
            else -> null
        }?.apply {
            addUpdateListener { plate.glowPhase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun nudge() {
        if (!CaptionMotion.enabled) return
        shake?.cancel()
        val amplitude = 3 * density
        shake = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CaptionMotion.MORPH
            addUpdateListener {
                val f = it.animatedFraction
                translationX = amplitude * sin(f * 3 * PI).toFloat() * (1 - f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { translationX = 0f }
            })
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runGlow(plate.glow)
    }

    override fun onDetachedFromWindow() {
        stopEffects()
        super.onDetachedFromWindow()
    }
}
