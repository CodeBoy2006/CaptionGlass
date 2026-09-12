package com.captionglass.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Motion tokens for caption surfaces. Text only moves when its content changes; loops belong to waiting states. */
internal object CaptionMotion {
    val enter: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val exit: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val standard: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    const val MICRO = 120L
    const val SHORT = 200L
    const val SETTLE = 240L
    const val MORPH = 280L

    /** False when the animator scale is zero, including the accessibility setting that removes animations. */
    val enabled get() = ValueAnimator.areAnimatorsEnabled()
}

/** A retargetable float that animates from wherever it currently is, and jumps when animations are off. */
internal class Tween(private val view: View, initial: Float, private val onUpdate: (Float) -> Unit = {}) {
    var value = initial
        private set
    var target = initial
        private set
    private var animator: ValueAnimator? = null
    val running get() = animator?.isStarted == true

    fun to(target: Float, duration: Long, interpolator: TimeInterpolator = CaptionMotion.standard, delay: Long = 0,
           end: (() -> Unit)? = null) {
        if (target == this.target && running) return
        this.target = target
        stop()
        if (!CaptionMotion.enabled || duration <= 0L || (value == target && delay == 0L)) {
            value = target; onUpdate(target); view.invalidate(); end?.invoke()
            return
        }
        animator = ValueAnimator.ofFloat(value, target).apply {
            this.duration = duration; this.interpolator = interpolator; startDelay = delay
            addUpdateListener { value = it.animatedValue as Float; onUpdate(value); view.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    if (!cancelled) end?.invoke()
                }
            })
            start()
        }
    }

    fun snap(target: Float) { this.target = target; stop(); value = target; onUpdate(target); view.invalidate() }
    fun stop() { animator?.let { animator = null; it.cancel() } }
}

/**
 * Obsidian glass drawn without blur: a vertical gradient, a hairline that is brighter at the top, an optional edge
 * light for waiting states and the blocking outline, which draws from the top centre down both sides.
 */
internal class CaptionPlate(private val density: Float) {
    enum class Glow { NONE, ORBIT, BREATHE, WARN }

    private val radius = 20 * density
    /** Outline style fades the fill and hairline away behind captions. */
    var fill = 1f
    var ring = 0f
    var ringAlpha = 0f
    var glow = Glow.NONE
    var glowPhase = 0f

    private val unit = Matrix()
    private val fillShader = LinearGradient(0f, 0f, 0f, 1f, CaptionPalette.PLATE_TOP, CaptionPalette.PLATE_BOTTOM, Shader.TileMode.CLAMP)
    private val edgeShader = LinearGradient(0f, 0f, 0f, 1f, CaptionPalette.EDGE_TOP, CaptionPalette.EDGE_BOTTOM, Shader.TileMode.CLAMP)
    private val orbitShader = SweepGradient(0f, 0f,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, ColorUtils.setAlphaComponent(CaptionPalette.ACCENT, 46), CaptionPalette.ACCENT, Color.TRANSPARENT),
        floatArrayOf(0f, 0.62f, 0.76f, 0.95f, 1f))
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = fillShader }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; shader = edgeShader }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeCap = Paint.Cap.ROUND; color = CaptionPalette.ACCENT
    }
    private val inset = RectF()
    private val traced = RectF()
    private val outline = Path()
    private val trimmed = Path()
    private val measure = PathMeasure()

    private fun corner(plate: RectF) = min(radius, min(plate.width(), plate.height()) / 2)

    fun drawFill(canvas: Canvas, plate: RectF) {
        if (fill <= 0f || plate.isEmpty) return
        unit.setScale(1f, plate.height()); unit.postTranslate(0f, plate.top)
        fillShader.setLocalMatrix(unit)
        fillPaint.alpha = (255 * fill).toInt()
        canvas.drawRoundRect(plate, corner(plate), corner(plate), fillPaint)
    }

    fun drawEdge(canvas: Canvas, plate: RectF) {
        if (plate.isEmpty) return
        val half = density / 2
        inset.set(plate.left + half, plate.top + half, plate.right - half, plate.bottom - half)
        val corner = max(0f, corner(plate) - half)
        if (fill > 0f) {
            unit.setScale(1f, plate.height()); unit.postTranslate(0f, plate.top)
            edgeShader.setLocalMatrix(unit)
            edgePaint.alpha = (255 * fill).toInt()
            canvas.drawRoundRect(inset, corner, corner, edgePaint)
        }
        when (glow) {
            Glow.NONE -> Unit
            Glow.ORBIT -> {
                unit.setRotate(glowPhase * 360f); unit.postTranslate(plate.centerX(), plate.centerY())
                orbitShader.setLocalMatrix(unit)
                glowPaint.shader = orbitShader; glowPaint.alpha = 255
                canvas.drawRoundRect(inset, corner, corner, glowPaint)
            }
            Glow.BREATHE, Glow.WARN -> {
                glowPaint.shader = null
                glowPaint.color = if (glow == Glow.WARN) CaptionPalette.WARNING else CaptionPalette.ACCENT
                glowPaint.alpha = if (glow == Glow.WARN) 128 else (255 * (0.18f + 0.32f * glowPhase)).toInt()
                canvas.drawRoundRect(inset, corner, corner, glowPaint)
            }
        }
        if (ring <= 0f || ringAlpha <= 0f) return
        ringPaint.alpha = (255 * ringAlpha).toInt()
        if (ring >= 1f) { canvas.drawRoundRect(inset, corner, corner, ringPaint); return }
        trace(inset, corner)
        val length = measure.length
        trimmed.rewind()
        measure.getSegment(0f, length * ring / 2, trimmed, true)
        measure.getSegment(length - length * ring / 2, length, trimmed, true)
        canvas.drawPath(trimmed, ringPaint)
    }

    /** A rounded rectangle that starts at the top centre, so a trimmed stroke grows symmetrically. */
    private fun trace(rect: RectF, corner: Float) {
        if (rect == traced && !outline.isEmpty) return
        traced.set(rect)
        val c = corner * 2
        outline.rewind()
        outline.moveTo(rect.centerX(), rect.top)
        outline.lineTo(rect.right - corner, rect.top)
        outline.arcTo(rect.right - c, rect.top, rect.right, rect.top + c, 270f, 90f, false)
        outline.lineTo(rect.right, rect.bottom - corner)
        outline.arcTo(rect.right - c, rect.bottom - c, rect.right, rect.bottom, 0f, 90f, false)
        outline.lineTo(rect.left + corner, rect.bottom)
        outline.arcTo(rect.left, rect.bottom - c, rect.left + c, rect.bottom, 90f, 90f, false)
        outline.lineTo(rect.left, rect.top + corner)
        outline.arcTo(rect.left, rect.top, rect.left + c, rect.top + c, 180f, 90f, false)
        outline.close()
        measure.setPath(outline, false)
    }
}

/** The 18 dp symbol in the status chip: a dot while loading, a live level meter while hearing, otherwise an icon. */
internal class StatusSignal(context: Context) : View(context) {
    enum class Kind { DOT, METER, ICON }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    // A fixed contour rotated per reading makes the bars read as speech rather than noise.
    private val contour = floatArrayOf(0.55f, 1f, 0.78f, 0.42f)
    private val bars = FloatArray(4) { contour[it] * 0.4f }
    private val targets = FloatArray(4) { 0.18f }
    private var kind = Kind.ICON
    private var color = CaptionPalette.SOURCE
    private var iconRes: Int? = null
    private var icon: Drawable? = null
    private var shift = 0
    private var heardAt = 0L
    private var steppedAt = 0L
    private var visibleNow = false
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { step() }
    }

    fun show(kind: Kind, icon: Int?, color: Int) {
        if (this.kind == kind && this.color == color && iconRes == icon) return
        this.kind = kind; this.color = color
        if (iconRes != icon) { iconRes = icon; this.icon = icon?.let { context.getDrawable(it)?.mutate() } }
        this.icon?.setTint(color)
        updateTicker()
        invalidate()
    }

    /** Capture RMS mapped to 0..1, delivered about ten times a second. */
    fun level(value: Float) {
        if (kind != Kind.METER || !CaptionMotion.enabled) return
        heardAt = SystemClock.uptimeMillis()
        shift = (shift + 1) % contour.size
        val level = value.coerceIn(0f, 1f)
        for (i in targets.indices) targets[i] = 0.18f + 0.82f * level * contour[(i + shift) % contour.size]
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - steppedAt).coerceIn(0L, 48L)
        steppedAt = now
        if (now - heardAt > 400) targets.fill(0.18f)
        val k = 1f - exp(-elapsed / 90f)
        for (i in bars.indices) bars[i] += (targets[i] - bars[i]) * k
        invalidate()
    }

    private fun updateTicker() {
        val run = kind == Kind.METER && visibleNow && isAttachedToWindow && CaptionMotion.enabled
        if (run && !ticker.isStarted) { steppedAt = SystemClock.uptimeMillis(); ticker.start() }
        else if (!run && ticker.isStarted) ticker.cancel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateTicker() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        updateTicker()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            Kind.DOT -> {
                paint.color = color; paint.alpha = 56
                canvas.drawCircle(w / 2, h / 2, 6.5f * density, paint)
                paint.alpha = 255
                canvas.drawCircle(w / 2, h / 2, 3.5f * density, paint)
            }
            Kind.METER -> {
                paint.color = color
                val bar = 2.5f * density
                val gap = (w - bar * 4) / 3
                for (i in bars.indices) {
                    val size = h * 0.88f * (if (ticker.isStarted) bars[i] else contour[i] * 0.8f)
                    val left = i * (bar + gap)
                    rect.set(left, (h - size) / 2, left + bar, (h + size) / 2)
                    canvas.drawRoundRect(rect, bar / 2, bar / 2, paint)
                }
            }
            Kind.ICON -> icon?.run { setBounds(0, 0, width, height); draw(canvas) }
        }
    }
}

/** Translation placeholder: one line-high bar sized from its source text, with a faint sweep every 2.4 s. */
internal class ShimmerBar(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x21FFFFFF }
    private val gradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(Color.TRANSPARENT, 0x26FFFFFF, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
    private val unit = Matrix()
    private val rect = RectF()
    private var visibleNow = false
    private val sweep = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    var lineHeight = 0
        set(value) { if (field != value) { field = value; requestLayout() } }
    var fraction = 0.5f
        set(value) {
            val clamped = value.coerceIn(0.28f, 0.92f)
            if (field != clamped) { field = clamped; invalidate() }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), lineHeight)

    override fun onDraw(canvas: Canvas) {
        val bar = 10 * density
        val top = (height - bar) / 2
        rect.set(0f, top, width * fraction, top + bar)
        canvas.drawRoundRect(rect, bar / 2, bar / 2, base)
        if (!sweep.isStarted) return
        val band = rect.width() * 0.6f
        unit.setScale(band, 1f)
        unit.postTranslate(-band + (rect.width() + band) * sweep.animatedFraction, 0f)
        gradient.setLocalMatrix(unit)
        canvas.drawRoundRect(rect, bar / 2, bar / 2, sheen)
    }

    private fun updateSweep() {
        val run = visibleNow && isAttachedToWindow && CaptionMotion.enabled
        if (run && !sweep.isStarted) sweep.start() else if (!run && sweep.isStarted) { sweep.cancel(); invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateSweep() }
    override fun onDetachedFromWindow() { sweep.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        updateSweep()
    }
}

/** The drag handle rides the plate's top edge. It rests as a thin line and briefly shows the touch mode. */
internal class HandleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val unit = Matrix()
    private val fillShader = LinearGradient(0f, 0f, 0f, 1f, CaptionPalette.PLATE_TOP, CaptionPalette.PLATE_BOTTOM, Shader.TileMode.CLAMP)
    private val edgeShader = LinearGradient(0f, 0f, 0f, 1f, 0x3DFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = fillShader }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; shader = edgeShader }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val rest = Tween(this, 0f)
    private val press = Tween(this, 0f)
    private val flash = Tween(this, 0f)
    private val hint = Tween(this, 0f)
    private var icon: Drawable? = null
    private val sleep = Runnable { rest.to(1f, CaptionMotion.SHORT) }
    private val unhint = Runnable { hint.to(0f, CaptionMotion.SHORT); sleepLater() }
    var blocking = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Distance from this view's bottom edge up to the plate edge the pill is centred on. */
    val overlap get() = (9 * density).toInt()

    fun wake() { removeCallbacks(sleep); rest.to(0f, CaptionMotion.MICRO, CaptionMotion.enter) }
    fun sleepLater() { removeCallbacks(sleep); postDelayed(sleep, 2_500) }
    fun press(down: Boolean) = press.to(if (down) 1f else 0f, CaptionMotion.MICRO, CaptionMotion.enter)
    fun flash() = flash.to(1f, 60L) { flash.to(0f, CaptionMotion.SETTLE) }
    fun hint(drawable: Int) {
        icon = context.getDrawable(drawable)?.mutate()?.apply { setTint(CaptionPalette.ACCENT) }
        wake()
        hint.to(1f, CaptionMotion.MICRO)
        removeCallbacks(unhint); postDelayed(unhint, 1_200)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(sleep); removeCallbacks(unhint)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val resting = rest.value
        val awake = 1f - resting
        val scale = 1f + 0.12f * press.value
        val w = (44f - 14f * resting) * density * scale
        val h = (18f - 13f * resting) * density * scale
        val cx = width / 2f
        val cy = height - 9 * density
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val corner = h / 2
        if (awake > 0f) {
            unit.setScale(1f, rect.height()); unit.postTranslate(0f, rect.top)
            fillShader.setLocalMatrix(unit); edgeShader.setLocalMatrix(unit)
            fillPaint.alpha = (255 * awake).toInt(); edgePaint.alpha = fillPaint.alpha
            canvas.drawRoundRect(rect, corner, corner, fillPaint)
            canvas.drawRoundRect(rect, corner, corner, edgePaint)
        }
        if (resting > 0f) {
            paint.color = Color.WHITE; paint.alpha = (107 * resting).toInt()
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        val gripAlpha = awake * (1f - hint.value)
        if (gripAlpha > 0f) {
            paint.color = ColorUtils.blendARGB(0x8CFFFFFF.toInt(), CaptionPalette.ACCENT, if (blocking) 1f else flash.value)
            paint.alpha = (Color.alpha(paint.color) * gripAlpha).toInt()
            val half = 10 * density * scale
            val thick = 1.5f * density * scale
            rect.set(cx - half, cy - thick, cx + half, cy + thick)
            canvas.drawRoundRect(rect, thick, thick, paint)
        }
        val shown = hint.value * awake
        icon?.takeIf { shown > 0f }?.run {
            val half = (6 * density * scale).toInt()
            setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            alpha = (255 * shown).toInt()
            draw(canvas)
        }
    }
}
