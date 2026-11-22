package com.puneet.friday.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.puneet.friday.R
import kotlin.math.min
import kotlin.math.sin

/**
 * Blue, lilac, and pearl light fields react to speech inside a stationary sphere.
 *
 * Android 13+ uses one small procedural shader. Cached gradients provide a softer
 * approximation on older Android and software Canvas. Both draw through the same
 * antialiased circle without images or offscreen layers. Call setters on the UI thread.
 */
class AssistantOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    enum class State {
        IDLE,
        PREVIEW,
        LISTENING,
        PROCESSING,
        SPEAKING,
        SUCCESS,
        ERROR,
    }

    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradientMatrix = Matrix()
    private var baseShader: Shader? = null
    private var blueShader: Shader? = null
    private var lilacShader: Shader? = null
    private var pearlShader: Shader? = null
    private val runtimeRenderer = createRuntimeRenderer()

    private var state = State.IDLE
    private var targetVoiceEnergy = 0f
    private var targetLow = 0f
    private var targetMid = 0f
    private var targetHigh = 0f
    private var voiceEnergy = 0f
    private var low = 0f
    private var mid = 0f
    private var high = 0f
    private var activity = 0f
    private var visualNoiseFloor = OrbMotionMath.DEFAULT_NOISE_FLOOR
    private var calibrationFramesRemaining = 0
    private var animationSeconds = 0.0
    private var lastFrameNanos = 0L
    private var centerX = 0f
    private var centerY = 0f
    private var orbRadius = 0f

    init {
        isFocusable = true
        isSoundEffectsEnabled = false
        minimumWidth = dp(96)
        minimumHeight = dp(96)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    fun setState(nextState: State) {
        if (state == nextState) return
        state = nextState
        targetVoiceEnergy = 0f
        targetLow = 0f
        targetMid = 0f
        targetHigh = 0f
        if (nextState == State.LISTENING || nextState == State.SPEAKING) {
            visualNoiseFloor = OrbMotionMath.DEFAULT_NOISE_FLOOR
            calibrationFramesRemaining = if (nextState == State.LISTENING) VISUAL_CALIBRATION_FRAMES else 0
        }
        if (nextState == State.IDLE || nextState == State.SUCCESS || nextState == State.ERROR) {
            voiceEnergy = 0f
            low = 0f
            mid = 0f
            high = 0f
            activity = 0f
            lastFrameNanos = 0L
        }
        invalidate()
    }

    /** Raw RMS drives motion; the spectrum changes the internal flow without resizing the sphere. */
    fun setAudioSignal(rms: Float, low: Float, mid: Float, high: Float) {
        if (!acceptsAudio() || !ValueAnimator.areAnimatorsEnabled()) return
        val safeRms = normalized(rms)
        if (safeRms < OrbMotionMath.noiseThreshold(visualNoiseFloor)) {
            visualNoiseFloor = OrbMotionMath.adaptNoiseFloor(
                visualNoiseFloor, safeRms,
                if (calibrationFramesRemaining > 0) VISUAL_CALIBRATION_RATE else VISUAL_NOISE_ADAPT_RATE,
            )
        }
        if (calibrationFramesRemaining > 0) calibrationFramesRemaining--
        targetVoiceEnergy = OrbMotionMath.voiceEnergy(safeRms, visualNoiseFloor)
        targetLow = normalized(low) * targetVoiceEnergy
        targetMid = normalized(mid) * targetVoiceEnergy
        targetHigh = normalized(high) * targetVoiceEnergy
        if (targetVoiceEnergy > MOTION_EPSILON || lastFrameNanos != 0L) invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        centerX = width / 2f
        centerY = height / 2f
        orbRadius = min(width, height) * ORB_RADIUS_RATIO
        if (orbRadius > 0f) {
            createCachedGradients()
            runtimeRenderer?.configure(context, centerX, centerY, orbRadius)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (orbRadius <= 0f) return

        val motionEnabled = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()
        val needsNextFrame = updateAnimationClock(motionEnabled)

        canvas.drawCircle(centerX, centerY, orbRadius * GLOW_RADIUS_RATIO, glowPaint)
        val renderer = if (canvas.isHardwareAccelerated) runtimeRenderer else null
        if (renderer != null) {
            renderer.draw(
                canvas,
                spherePaint,
                centerX,
                centerY,
                orbRadius,
                animationSeconds.toFloat(),
                activity,
                low,
                mid,
                high,
            )
        } else {
            drawAurora(canvas)
        }

        if (isPressed || isFocused) {
            spherePaint.style = if (isPressed) Paint.Style.FILL else Paint.Style.STROKE
            spherePaint.strokeWidth = dp(2).toFloat()
            spherePaint.color = if (isPressed) 0x26FFFFFF else context.getColor(R.color.focus_ring)
            canvas.drawCircle(centerX, centerY, if (isPressed) orbRadius else orbRadius + dp(4), spherePaint)
            spherePaint.style = Paint.Style.FILL
            spherePaint.color = Color.WHITE
        }

        if (needsNextFrame) {
            if (canvas.isHardwareAccelerated) {
                postInvalidateOnAnimation()
            } else {
                postInvalidateDelayed(SOFTWARE_FRAME_DELAY_MS)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrameNanos = 0L
        if (visibility == VISIBLE) invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrameNanos = 0L
        if (visibility == VISIBLE) invalidate()
    }

    private fun updateAnimationClock(motionEnabled: Boolean): Boolean {
        if (!motionEnabled) {
            lastFrameNanos = 0L
            return false
        }
        val syntheticMotion = state == State.PREVIEW
        val audioMotion = targetVoiceEnergy > MOTION_EPSILON || voiceEnergy > MOTION_EPSILON
        val spectrumSettling = low > MOTION_EPSILON || mid > MOTION_EPSILON || high > MOTION_EPSILON
        if (!syntheticMotion && !audioMotion && !spectrumSettling) {
            lastFrameNanos = 0L
            activity = 0f
            return false
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val delta = if (lastFrameNanos == 0L) 0f else {
            ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
        }
        lastFrameNanos = now

        voiceEnergy = easeAudio(voiceEnergy, targetVoiceEnergy, delta)
        low = easeAudio(low, targetLow, delta)
        mid = easeAudio(mid, targetMid, delta)
        high = easeAudio(high, targetHigh, delta)
        val decay = delta * AUDIO_TARGET_DECAY_PER_SECOND
        targetVoiceEnergy = (targetVoiceEnergy - decay).coerceAtLeast(0f)
        targetLow = (targetLow - decay).coerceAtLeast(0f)
        targetMid = (targetMid - decay).coerceAtLeast(0f)
        targetHigh = (targetHigh - decay).coerceAtLeast(0f)

        val targetActivity = when (state) {
            State.IDLE, State.SUCCESS, State.ERROR -> 0f
            State.PREVIEW -> 0.42f + wave(animationSeconds * 1.9) * 0.12f
            State.LISTENING, State.SPEAKING -> voiceEnergy
            State.PROCESSING -> voiceEnergy
        }
        activity += (targetActivity - activity) * (delta * ACTIVITY_EASING_PER_SECOND).coerceAtMost(1f)
        val speed = when {
            syntheticMotion -> PREVIEW_SPEED
            else -> voiceEnergy * (VOICE_BASE_SPEED + voiceEnergy * VOICE_EXTRA_SPEED + mid * MID_SPEED)
        }
        animationSeconds += delta * speed
        return syntheticMotion || targetVoiceEnergy > MOTION_EPSILON || voiceEnergy > MOTION_EPSILON ||
            low > MOTION_EPSILON || mid > MOTION_EPSILON || high > MOTION_EPSILON
    }

    private fun drawAurora(canvas: Canvas) {
        val time = animationSeconds
        val drift = activity * 0.45f
        drawField(canvas, baseShader, 0f, 0f, 1f, 1f, 0f)
        drawField(canvas, blueShader,
            -0.47f, -0.60f + wave(time * 0.64) * drift, 1.4f, 1f, -12f)
        drawField(canvas, lilacShader,
            0.28f + wave(time * 0.84 + 1.2) * drift, 0.52f, 1.40f, 0.90f, -12f)
        drawField(canvas, pearlShader,
            -0.52f + wave(time * 1.1) * drift, 0.20f + wave(time * 0.9) * drift,
            1.04f, 0.56f + low * 0.24f, -18f)
        drawField(canvas, pearlShader,
            0.64f + wave(time * 0.8) * drift, -0.04f + wave(time * 1.2 + 1.4) * drift,
            1.18f, 0.57f + mid * 0.22f, -28f + high * 13f)
        spherePaint.shader = null
    }

    private fun drawField(
        canvas: Canvas,
        shader: Shader?,
        x: Float,
        y: Float,
        scaleX: Float,
        scaleY: Float,
        rotation: Float,
    ) {
        if (shader == null) return
        gradientMatrix.setScale(orbRadius * scaleX, orbRadius * scaleY)
        gradientMatrix.postRotate(rotation)
        gradientMatrix.postTranslate(centerX + x * orbRadius, centerY + y * orbRadius)
        shader.setLocalMatrix(gradientMatrix)
        spherePaint.shader = shader
        canvas.drawCircle(centerX, centerY, orbRadius, spherePaint)
    }

    private fun createCachedGradients() {
        val glow = context.getColor(R.color.orb_glow)
        val pearl = context.getColor(R.color.orb_pearl)
        glowPaint.shader = RadialGradient(
            centerX, centerY, orbRadius * GLOW_RADIUS_RATIO,
            intArrayOf(withAlpha(glow, 28), withAlpha(glow, 16), withAlpha(glow, 5), withAlpha(glow, 0)),
            floatArrayOf(0.56f, 0.72f, 0.88f, 1f), Shader.TileMode.CLAMP,
        )
        baseShader = LinearGradient(
            -0.65f, -1f, 0.55f, 1f,
            intArrayOf(
                context.getColor(R.color.orb_base_deep),
                context.getColor(R.color.orb_blue),
                context.getColor(R.color.orb_base_light),
            ),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP,
        )
        blueShader = softField(context.getColor(R.color.orb_blue), 185)
        lilacShader = softField(context.getColor(R.color.orb_lilac), 160)
        pearlShader = softField(pearl, 222)
    }

    private fun softField(color: Int, alpha: Int): Shader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha * 3 / 4), withAlpha(color, alpha / 3), withAlpha(color, 0)),
        floatArrayOf(0f, 0.28f, 0.62f, 1f), Shader.TileMode.CLAMP,
    )

    private fun createRuntimeRenderer(): RuntimeAuroraRenderer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            RuntimeAuroraRenderer()
        } catch (_: RuntimeException) {
            null
        }
    }

    @SuppressLint("NewApi")
    private class RuntimeAuroraRenderer {
        private val shader = RuntimeShader(AURORA_SHADER)

        fun configure(context: Context, x: Float, y: Float, radius: Float) {
            shader.setFloatUniform("center", x, y)
            shader.setFloatUniform("radius", radius)
            shader.setColorUniform("blue", context.getColor(R.color.orb_blue))
            shader.setColorUniform("cyan", context.getColor(R.color.orb_cyan))
            shader.setColorUniform("lilac", context.getColor(R.color.orb_lilac))
            shader.setColorUniform("pearl", context.getColor(R.color.orb_pearl))
            shader.setColorUniform("deep", context.getColor(R.color.orb_base_deep))
            shader.setColorUniform("shadow", context.getColor(R.color.orb_shadow))
        }

        fun draw(
            canvas: Canvas,
            paint: Paint,
            x: Float,
            y: Float,
            radius: Float,
            time: Float,
            activity: Float,
            low: Float,
            mid: Float,
            high: Float,
        ) {
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("activity", activity)
            shader.setFloatUniform("bass", low)
            shader.setFloatUniform("mid", mid)
            shader.setFloatUniform("treble", high)
            paint.shader = shader
            canvas.drawCircle(x, y, radius, paint)
            paint.shader = null
        }
    }

    private fun acceptsAudio(): Boolean = state == State.LISTENING || state == State.SPEAKING

    private fun normalized(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun easeAudio(current: Float, target: Float, delta: Float): Float {
        val rate = if (target > current) AUDIO_ATTACK_PER_SECOND else AUDIO_RELEASE_PER_SECOND
        return current + (target - current) * (delta * rate).coerceAtMost(1f)
    }

    private fun wave(phase: Double): Float = sin(phase).toFloat()

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ORB_RADIUS_RATIO = 0.35f
        const val GLOW_RADIUS_RATIO = 1.32f
        const val SOFTWARE_FRAME_DELAY_MS = 33L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_DELTA_SECONDS = 0.05f
        const val AUDIO_ATTACK_PER_SECOND = 20f
        const val AUDIO_RELEASE_PER_SECOND = 5.5f
        const val AUDIO_TARGET_DECAY_PER_SECOND = 1.7f
        const val ACTIVITY_EASING_PER_SECOND = 12f
        const val MOTION_EPSILON = 0.006f
        const val VISUAL_CALIBRATION_FRAMES = 12
        const val VISUAL_CALIBRATION_RATE = 0.25f
        const val VISUAL_NOISE_ADAPT_RATE = 0.035f
        const val PREVIEW_SPEED = 1.25f
        const val VOICE_BASE_SPEED = 3.2f
        const val VOICE_EXTRA_SPEED = 3.2f
        const val MID_SPEED = 0.9f

        // A broad flowing color field: no textures, ray marching, rings, or particles.
        // Written for Friday; references informed visual direction, not source code.
        const val AURORA_SHADER = """
            uniform float2 center;
            uniform float radius;
            uniform float time;
            uniform float activity;
            uniform float bass;
            uniform float mid;
            uniform float treble;
            layout(color) uniform half4 blue;
            layout(color) uniform half4 cyan;
            layout(color) uniform half4 lilac;
            layout(color) uniform half4 pearl;
            layout(color) uniform half4 deep;
            layout(color) uniform half4 shadow;

            float hash(float2 p) {
                float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
                q += dot(q, q.yzx + 33.33);
                return fract((q.x + q.y) * q.z);
            }

            float noise(float2 p) {
                float2 cell = floor(p);
                float2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(hash(cell), hash(cell + float2(1.0, 0.0)), f.x),
                    mix(hash(cell + float2(0.0, 1.0)), hash(cell + float2(1.0, 1.0)), f.x),
                    f.y
                );
            }

            half4 main(float2 position) {
                float2 p = (position - center) / radius;
                float t = time * 0.55;
                float2 field = p + float2(t * 0.42, -t * 0.14);
                float billow = noise(field * 2.35 + float2(1.2, 6.7)) - 0.5;
                float wisps = noise(field * 5.2 + float2(-t * 0.6, 2.3)) - 0.5;

                // Softer, broader clouds on quiet syllables and more lift on stressed ones.
                float bank = p.y + p.x * 0.20 + 0.13
                    + billow * (0.55 + mid * 0.34)
                    + wisps * (0.15 + treble * 0.14) + activity * 0.23
                    + sin(p.x * 2.7 + t) * (0.09 + bass * 0.18);
                float skyHeight = smoothstep(-1.0, 0.42, p.y);
                half3 sky = mix(mix(deep.rgb, blue.rgb, 0.67), blue.rgb, skyHeight);
                sky = mix(sky, cyan.rgb, 0.25 * smoothstep(-0.4, 0.58, billow - p.x * 0.35));

                float cloud = smoothstep(-0.29, 0.34, bank);
                float shade = smoothstep(0.12, 1.1, p.y + billow * 0.23);
                half3 air = mix(blue.rgb, lilac.rgb, 0.64);
                half3 cloudColor = mix(pearl.rgb, air, shade * 0.68);
                half3 color = mix(sky, cloudColor, cloud * 0.96);
                float veil = smoothstep(-0.16, 0.03, bank + wisps * 0.26)
                    * (1.0 - smoothstep(0.11, 0.48, bank));
                color = mix(color, pearl.rgb, veil * (0.17 + activity * 0.16));
                color = mix(color, shadow.rgb, 0.10 * smoothstep(0.65, 1.0, length(p)));
                return half4(color, 1.0);
            }
        """
    }
}
