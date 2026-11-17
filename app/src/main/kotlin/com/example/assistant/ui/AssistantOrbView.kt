package com.example.assistant.ui

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
import com.example.assistant.R
import kotlin.math.min
import kotlin.math.sin

/**
 * Aurora's blue/lilac light fields flow within a stationary, softly lit sphere.
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
    private var cyanShader: Shader? = null
    private var blueShader: Shader? = null
    private var lilacShader: Shader? = null
    private var violetShader: Shader? = null
    private var pearlShader: Shader? = null
    private var sheenShader: Shader? = null
    private var depthShader: Shader? = null
    private val runtimeRenderer = createRuntimeRenderer()

    private var state = State.IDLE
    private var targetAmplitude = 0f
    private var targetLow = 0f
    private var targetMid = 0f
    private var targetHigh = 0f
    private var amplitude = 0f
    private var low = 0f
    private var mid = 0f
    private var high = 0f
    private var activity = IDLE_ACTIVITY
    private var animationSeconds = 0.0
    private var lastFrameNanos = 0L
    private var centerX = 0f
    private var centerY = 0f
    private var orbRadius = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        minimumWidth = dp(96)
        minimumHeight = dp(96)
    }

    fun setState(nextState: State) {
        if (state == nextState) return
        state = nextState
        targetAmplitude = 0f
        targetLow = 0f
        targetMid = 0f
        targetHigh = 0f
        // Preserve phase and eased values so changing state never snaps the light.
        invalidate()
    }

    /** Normalized audio level; only active while capture or speech output is running. */
    fun setAudioAmplitude(level: Float) {
        if (!acceptsAudio()) return
        targetAmplitude = normalized(level)
        invalidate()
    }

    /** Bass shifts broad fields; mids affect flow; treble gently shifts the sheen. */
    fun setAudioSpectrum(low: Float, mid: Float, high: Float) {
        if (!acceptsAudio()) return
        targetLow = normalized(low)
        targetMid = normalized(mid)
        targetHigh = normalized(high)
        invalidate()
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

        val animate = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()
        updateAnimationClock(animate)

        canvas.drawCircle(centerX, centerY, orbRadius * GLOW_RADIUS_RATIO, glowPaint)
        val renderer = if (canvas.isHardwareAccelerated) runtimeRenderer else null
        if (renderer != null) {
            renderer.draw(canvas, spherePaint, centerX, centerY, orbRadius, animationSeconds.toFloat(), activity, low, high)
        } else {
            drawAurora(canvas)
        }

        if (animate) {
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

    private fun updateAnimationClock(animate: Boolean) {
        if (!animate) {
            lastFrameNanos = 0L
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val delta = if (lastFrameNanos == 0L) 0f else {
            ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
        }
        lastFrameNanos = now

        amplitude = easeAudio(amplitude, targetAmplitude, delta)
        low = easeAudio(low, targetLow, delta)
        mid = easeAudio(mid, targetMid, delta)
        high = easeAudio(high, targetHigh, delta)
        val decay = delta * AUDIO_TARGET_DECAY_PER_SECOND
        targetAmplitude = (targetAmplitude - decay).coerceAtLeast(0f)
        targetLow = (targetLow - decay).coerceAtLeast(0f)
        targetMid = (targetMid - decay).coerceAtLeast(0f)
        targetHigh = (targetHigh - decay).coerceAtLeast(0f)

        val targetActivity = when (state) {
            State.IDLE, State.SUCCESS, State.ERROR -> IDLE_ACTIVITY
            State.PREVIEW -> 0.42f + wave(animationSeconds * 1.9) * 0.12f
            State.LISTENING -> 0.24f + amplitude * 0.22f + mid * 0.1f
            State.PROCESSING -> 0.36f
            State.SPEAKING -> 0.3f + amplitude * 0.24f + mid * 0.12f
        }
        activity += (targetActivity - activity) * (delta * ACTIVITY_EASING_PER_SECOND).coerceAtMost(1f)
        val speed = when (state) {
            State.IDLE, State.SUCCESS, State.ERROR -> 0.32f
            State.PREVIEW -> 1.25f
            State.LISTENING -> 0.62f
            State.PROCESSING -> 0.82f
            State.SPEAKING -> 0.74f
        }
        animationSeconds += delta * (speed + amplitude * 0.55f + mid * 0.25f)
    }

    private fun drawAurora(canvas: Canvas) {
        val time = animationSeconds
        val drift = 0.12f + activity * 0.3f
        // Independent paths continually change the blend without resetting a timeline.
        drawField(canvas, baseShader, 0f, 0f, 1f, 1f, wave(time * 0.19) * 9f)
        drawField(
            canvas, cyanShader,
            -0.48f + wave(time * 0.61) * drift,
            -0.35f + wave(time * 0.43 + 1.1) * drift,
            1.38f + low * 0.12f, 1.02f + wave(time * 0.37) * 0.08f,
            -24f + wave(time * 0.31) * 18f,
        )
        drawField(
            canvas, blueShader,
            -0.38f + wave(time * 0.47 + 2.3) * drift,
            0.48f + wave(time * 0.59) * drift,
            1.28f, 0.94f + low * 0.14f,
            28f + wave(time * 0.29) * 20f,
        )
        drawField(
            canvas, lilacShader,
            0.52f + wave(time * 0.53 + 1.6) * drift,
            -0.25f + wave(time * 0.41 + 3.4) * drift,
            1.18f + wave(time * 0.67) * 0.1f, 1.06f + low * 0.08f,
            -38f + wave(time * 0.23) * 24f,
        )
        drawField(
            canvas, violetShader,
            0.36f + wave(time * 0.39 + 4.1) * drift,
            0.62f + wave(time * 0.57 + 0.7) * drift,
            1.06f, 0.88f + wave(time * 0.51) * 0.1f,
            15f + wave(time * 0.33) * 26f,
        )
        drawField(
            canvas, pearlShader,
            -0.24f + wave(time * 0.37 + 0.4) * 0.12f,
            -0.42f + wave(time * 0.49 + 1.2) * 0.1f,
            1.1f + activity * 0.08f, 0.72f,
            -32f + wave(time * 0.27) * 15f,
        )
        drawField(
            canvas, sheenShader,
            wave(time * 0.31) * 0.24f,
            -0.06f + wave(time * 0.43) * 0.18f,
            1.2f, 1f,
            -34f + wave(time * 0.35) * (13f + high * 8f),
        )
        // Broad shading gives depth without a rim, outline, or ring.
        spherePaint.shader = depthShader
        canvas.drawCircle(centerX, centerY, orbRadius, spherePaint)
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
        val shadow = context.getColor(R.color.orb_shadow)
        glowPaint.shader = RadialGradient(
            centerX, centerY, orbRadius * GLOW_RADIUS_RATIO,
            intArrayOf(withAlpha(glow, 28), withAlpha(glow, 16), withAlpha(glow, 5), withAlpha(glow, 0)),
            floatArrayOf(0.56f, 0.72f, 0.88f, 1f), Shader.TileMode.CLAMP,
        )
        baseShader = LinearGradient(
            -0.65f, -1f, 0.55f, 1f,
            intArrayOf(
                context.getColor(R.color.orb_base_light),
                context.getColor(R.color.orb_base),
                context.getColor(R.color.orb_base_deep),
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        cyanShader = softField(context.getColor(R.color.orb_cyan), 224)
        blueShader = softField(context.getColor(R.color.orb_blue), 200)
        lilacShader = softField(context.getColor(R.color.orb_lilac), 228)
        violetShader = softField(context.getColor(R.color.orb_violet), 194)
        pearlShader = softField(pearl, 168)
        sheenShader = LinearGradient(
            0f, -1f, 0f, 1f,
            intArrayOf(withAlpha(pearl, 0), withAlpha(pearl, 0), withAlpha(pearl, 58), withAlpha(pearl, 0), withAlpha(pearl, 0)),
            floatArrayOf(0f, 0.18f, 0.48f, 0.8f, 1f), Shader.TileMode.CLAMP,
        )
        depthShader = RadialGradient(
            centerX - orbRadius * 0.25f, centerY - orbRadius * 0.32f, orbRadius * 1.43f,
            intArrayOf(withAlpha(shadow, 0), withAlpha(shadow, 0), withAlpha(shadow, 40), withAlpha(shadow, 148)),
            floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP,
        )
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
            high: Float,
        ) {
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("activity", activity)
            shader.setFloatUniform("bass", low)
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
        const val IDLE_ACTIVITY = 0.08f
        const val SOFTWARE_FRAME_DELAY_MS = 33L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_DELTA_SECONDS = 0.05f
        const val AUDIO_ATTACK_PER_SECOND = 14f
        const val AUDIO_RELEASE_PER_SECOND = 4f
        const val AUDIO_TARGET_DECAY_PER_SECOND = 0.8f
        const val ACTIVITY_EASING_PER_SECOND = 3.5f

        // A broad flowing color field: no textures, ray marching, rings, or particles.
        // Written for Friday; references informed visual direction, not source code.
        const val AURORA_SHADER = """
            uniform float2 center;
            uniform float radius;
            uniform float time;
            uniform float activity;
            uniform float bass;
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
                float depth = sqrt(max(0.0, 1.0 - dot(p, p)));
                float2 surface = p / (1.0 + depth * 0.38);
                float drift = sin(time * 0.31) * 0.06;
                float broadNoise = noise(surface * 2.5 + float2(time * 0.13, -time * 0.09)) - 0.5;
                float bend = sin(surface.x * 3.5 + time * 0.48) * 0.16
                    + sin(surface.x * 1.8 - time * 0.29 + 1.3) * 0.08
                    + broadNoise * (0.13 + activity * 0.07);
                float flowY = surface.y + surface.x * 0.26 - bend - drift;
                float width = 0.30 + activity * 0.035 + bass * 0.045;
                float band = exp(-flowY * flowY / (width * width));
                float colorPosition = clamp(
                    0.5 + surface.x * 0.62 + sin(time * 0.23) * 0.1 + broadNoise * 0.09,
                    0.0, 1.0
                );
                half3 flowColor = mix(blue.rgb, cyan.rgb, smoothstep(0.02, 0.45, colorPosition));
                flowColor = mix(flowColor, lilac.rgb, smoothstep(0.42, 0.96, colorPosition));
                half3 body = mix(deep.rgb, blue.rgb, 0.30 + depth * 0.16);
                body = mix(body, lilac.rgb, 0.16 * smoothstep(-0.5, 0.9, surface.x));
                half3 color = mix(body, flowColor, 0.93 * band);
                float silkY = (flowY + 0.065) / 0.105;
                float silk = exp(-silkY * silkY);
                color = mix(color, pearl.rgb, silk * (0.10 + treble * 0.035));
                float2 lightPosition = p - float2(-0.30, -0.40);
                float light = exp(-dot(lightPosition, lightPosition) * 4.5);
                color = mix(color, pearl.rgb, light * 0.21);
                float shade = smoothstep(0.32, 1.2, length(p - float2(-0.16, -0.23)));
                color = mix(color, shadow.rgb, shade * 0.34);
                return half4(color, 1.0);
            }
        """
    }
}
