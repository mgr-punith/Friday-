package com.puneet.friday.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.puneet.friday.BuildConfig
import com.puneet.friday.R
import kotlin.math.min

/** Shared voice and lifecycle host for the Home and compact orbs. */
class AssistantOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    ribbon: Boolean = false,
) : FrameLayout(context, attrs) {
    enum class State { IDLE, PREVIEW, LISTENING, PROCESSING, SPEAKING, SUCCESS, ERROR }

    private val wakeOrb = if (ribbon) WakeOrbView(context) else null
    private val renderer = if (ribbon) null else ProceduralOrbRenderer(resources.displayMetrics.density) { message ->
        post {
            renderUnavailable = true
            setRenderMode(false)
            invalidate()
            if (BuildConfig.DEBUG) android.util.Log.w("FridayOrb", message)
        }
    }
    private val glView = renderer?.let { OrbTextureView(context, it) }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var renderUnavailable = false
    private var state = State.IDLE
    private var targetVoiceEnergy = 0f
    private var visualNoiseFloor = OrbMotionMath.DEFAULT_NOISE_FLOOR
    private var calibrationFramesRemaining = 0
    private var quietStop: Runnable? = null
    private var staleSignal: Runnable? = null
    private var settlingUntilMs = 0L
    private var hostResumed = false

    init {
        isFocusable = !ribbon
        isSoundEffectsEnabled = false
        clipChildren = true
        clipToPadding = true
        if (!ribbon) {
            minimumWidth = dp(96f).toInt()
            minimumHeight = dp(96f).toInt()
        }
        val visual = wakeOrb ?: requireNotNull(glView)
        visual.isClickable = false
        visual.isFocusable = false
        addView(visual, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (ribbon) importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setRadiusForBounds()
        setWillNotDraw(false)
    }

    internal fun setWakeShape(shape: WakeOrbView.Shape) {
        wakeOrb?.setShape(shape)
    }

    internal fun setWakeLabelHeight(height: Int) {
        wakeOrb?.setLabelHeight(height)
    }

    internal fun onWakePillReveal(update: (Float) -> Unit) {
        wakeOrb?.onPillReveal = update
    }

    override fun getAccessibilityClassName(): CharSequence =
        if (wakeOrb == null) android.widget.Button::class.java.name else super.getAccessibilityClassName()

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    fun setState(nextState: State) {
        if (state == nextState) return
        state = nextState
        targetVoiceEnergy = 0f
        quietStop?.let { removeCallbacks(it) }
        quietStop = null
        staleSignal?.let { removeCallbacks(it) }
        staleSignal = null
        settlingUntilMs = 0L
        renderer?.clearAudioSignal()
        wakeOrb?.setAudioSignal(0f, 0f, 0f, 0f)
        updateMotionPolicy()
        if (nextState == State.LISTENING || nextState == State.SPEAKING) {
            visualNoiseFloor = OrbMotionMath.DEFAULT_NOISE_FLOOR
            calibrationFramesRemaining = if (nextState == State.LISTENING) CALIBRATION_FRAMES else 0
        }
        requestFrame()
        invalidate()
    }

    /** Existing gated audio is passed through; this view does not own the microphone. */
    fun setAudioSignal(rms: Float, low: Float, mid: Float, high: Float) {
        if (!acceptsAudio()) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            val hadSignal = targetVoiceEnergy > MOTION_EPSILON || settlingUntilMs != 0L
            targetVoiceEnergy = 0f
            if (hadSignal) {
                renderer?.clearAudioSignal()
                wakeOrb?.setAudioSignal(0f, 0f, 0f, 0f)
            }
            updateMotionPolicy()
            return
        }
        val safeRms = normalized(rms)
        if (safeRms < OrbMotionMath.noiseThreshold(visualNoiseFloor)) {
            visualNoiseFloor = OrbMotionMath.adaptNoiseFloor(
                visualNoiseFloor, safeRms,
                if (calibrationFramesRemaining > 0) CALIBRATION_RATE else NOISE_ADAPT_RATE,
            )
        }
        if (calibrationFramesRemaining > 0) calibrationFramesRemaining--
        val previousEnergy = targetVoiceEnergy
        targetVoiceEnergy = OrbMotionMath.voiceEnergy(safeRms, visualNoiseFloor)
        setVisualSignal(
            targetVoiceEnergy,
            normalized(low) * targetVoiceEnergy,
            normalized(mid) * targetVoiceEnergy,
            normalized(high) * targetVoiceEnergy,
        )
        if (targetVoiceEnergy > MOTION_EPSILON) {
            quietStop?.let { removeCallbacks(it) }
            quietStop = null
            settlingUntilMs = 0L
            staleSignal?.let { removeCallbacks(it) }
            val stale = Runnable {
                targetVoiceEnergy = 0f
                setVisualSignal(0f, 0f, 0f, 0f)
                staleSignal = null
                settleMotion()
            }
            staleSignal = stale
            postDelayed(stale, SIGNAL_TIMEOUT_MS)
        } else if (previousEnergy > MOTION_EPSILON) {
            staleSignal?.let { removeCallbacks(it) }
            staleSignal = null
            settleMotion()
        }
        updateMotionPolicy()
    }

    private fun setVisualSignal(energy: Float, low: Float, mid: Float, high: Float) {
        renderer?.setAudioSignal(energy, low, mid, high)
        wakeOrb?.setAudioSignal(energy, low, mid, high)
    }

    private fun settleMotion() {
        quietStop?.let { removeCallbacks(it) }
        settlingUntilMs = SystemClock.elapsedRealtime() + QUIET_SETTLE_MS
        val stop = Runnable {
            settlingUntilMs = 0L
            quietStop = null
            updateMotionPolicy()
        }
        quietStop = stop
        postDelayed(stop, QUIET_SETTLE_MS)
        updateMotionPolicy()
    }

    fun onHostResume() {
        hostResumed = true
        glView?.resumeRendering()
        renderer?.resetFrameClock()
        updateMotionPolicy()
        requestFrame()
    }

    fun onHostPause() {
        hostResumed = false
        targetVoiceEnergy = 0f
        quietStop?.let { removeCallbacks(it) }
        quietStop = null
        staleSignal?.let { removeCallbacks(it) }
        staleSignal = null
        settlingUntilMs = 0L
        renderer?.clearAudioSignal()
        setRenderMode(false)
        renderer?.setMotionEnabled(false)
        glView?.pauseRendering()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        setRadiusForBounds()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            setRenderMode(false)
            renderer?.setMotionEnabled(false)
        } else {
            renderer?.resetFrameClock()
            updateMotionPolicy()
            requestFrame()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            setRenderMode(false)
            renderer?.setMotionEnabled(false)
        } else {
            renderer?.resetFrameClock()
            updateMotionPolicy()
            requestFrame()
        }
    }

    override fun onDetachedFromWindow() {
        onHostPause()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (isPressed || isFocused) {
            fallbackPaint.style = if (isPressed) Paint.Style.FILL else Paint.Style.STROKE
            fallbackPaint.strokeWidth = dp(2f)
            fallbackPaint.color = if (isPressed) 0x26FFFFFF else context.getColor(R.color.focus_ring)
            canvas.drawCircle(width / 2f, height / 2f, min(width, height) * .44f, fallbackPaint)
            fallbackPaint.style = Paint.Style.FILL
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!renderUnavailable || width <= 0 || height <= 0) return
        val radius = min(width, height) * .35f
        fallbackPaint.shader = RadialGradient(
            width / 2f, height / 2f, radius,
            intArrayOf(0xFFF8F8F8.toInt(), 0xFFBDBDBD.toInt(), 0xFF242424.toInt()),
            floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(width / 2f, height / 2f, radius, fallbackPaint)
        fallbackPaint.shader = null
    }

    private fun setRadiusForBounds() {
        val compactBounds = min(width, height) > 0 && min(width, height) < dp(160f)
        renderer?.setRadiusRatio(if (compactBounds) COMPACT_RADIUS_RATIO else HOME_RADIUS_RATIO)
    }

    private fun setRenderMode(continuous: Boolean, responding: Boolean = false) {
        val active = continuous && hostResumed && isAttachedToWindow && isShown && windowVisibility == VISIBLE
        glView?.setFrameRate(if (!active) 0 else if (responding) 60 else 30)
        wakeOrb?.setAnimating(active)
    }

    private fun requestFrame() {
        glView?.requestFrame()
        wakeOrb?.invalidate()
    }

    private fun updateMotionPolicy() {
        val settling = SystemClock.elapsedRealtime() < settlingUntilMs
        val continuous = OrbMotionPolicy.shouldRenderContinuously(
            state = state,
            gatedEnergy = targetVoiceEnergy,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
            visible = !renderUnavailable && isAttachedToWindow && isShown && windowVisibility == VISIBLE,
            settling = settling,
            ambientMotion = renderer != null,
            processingIndicator = wakeOrb != null,
        )
        renderer?.setMotionEnabled(continuous && hostResumed)
        setRenderMode(continuous, settling || targetVoiceEnergy > MOTION_EPSILON)
    }

    private fun acceptsAudio(): Boolean = state == State.LISTENING || state == State.SPEAKING

    private fun normalized(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val HOME_RADIUS_RATIO = .35f
        const val COMPACT_RADIUS_RATIO = .44f
        const val CALIBRATION_FRAMES = 12
        const val CALIBRATION_RATE = .25f
        const val NOISE_ADAPT_RATE = .035f
        const val MOTION_EPSILON = .006f
        const val QUIET_SETTLE_MS = 400L
        const val SIGNAL_TIMEOUT_MS = 160L
    }
}

/** Pure scheduling rule for the existing voice-state and system-motion contract. */
object OrbMotionPolicy {
    fun shouldRenderContinuously(
        state: AssistantOrbView.State,
        gatedEnergy: Float,
        animatorsEnabled: Boolean,
        visible: Boolean,
        settling: Boolean = false,
        ambientMotion: Boolean = true,
        processingIndicator: Boolean = false,
    ): Boolean {
        if (!animatorsEnabled || !visible) return false
        if (ambientMotion) return true
        return when (state) {
            AssistantOrbView.State.PREVIEW -> true
            AssistantOrbView.State.LISTENING, AssistantOrbView.State.SPEAKING ->
                settling || gatedEnergy.isFinite() && gatedEnergy > 0.006f
            AssistantOrbView.State.PROCESSING -> processingIndicator
            AssistantOrbView.State.IDLE,
            AssistantOrbView.State.SUCCESS,
            AssistantOrbView.State.ERROR -> false
        }
    }
}
