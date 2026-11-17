package com.example.assistant.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.example.assistant.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * A procedural sky sphere shared by the in-app assistant and future compact surfaces.
 *
 * Android 13 and newer use an AGSL domain-warped cloud shader. Older versions use a
 * lightweight Canvas fallback made from cached radial gradients. Both renderers avoid
 * image assets and animation dependencies, and the animation clock pauses with the view.
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
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val cloudPuffs = createCloudPuffs()

    private var state = State.IDLE
    private var targetAudioEnergy = 0f
    private var renderedAudioEnergy = 0f
    private var renderedActivity = IDLE_ACTIVITY
    private var animationSeconds = 0f
    private var lastFrameNanos = 0L

    private var centerX = 0f
    private var centerY = 0f
    private var orbRadius = 0f
    private var glowShader: Shader? = null
    private var fallbackSkyShader: Shader? = null
    private var fallbackCloudShadowShader: Shader? = null
    private var fallbackCloudShader: Shader? = null
    private var fallbackCloudHighlightShader: Shader? = null
    private var fallbackSphereShadeShader: Shader? = null
    private var fallbackSphereLightShader: Shader? = null

    private val runtimeRenderer: RuntimeSkyRenderer? = createRuntimeRendererIfSupported()

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
        targetAudioEnergy = 0f
        lastFrameNanos = 0L
        invalidate()
    }

    /** Feeds a normalized microphone level for future listening and speaking states. */
    fun setAudioAmplitude(level: Float) {
        if (state != State.LISTENING && state != State.SPEAKING) return
        targetAudioEnergy = level.coerceIn(0f, 1f)
        invalidate()
    }

    /**
     * Feeds normalized spectral energy from the future microphone analyzer.
     * Frequency bands gently affect cloud evolution; the sphere never changes size.
     */
    fun setAudioSpectrum(low: Float, mid: Float, high: Float) {
        if (state != State.LISTENING && state != State.SPEAKING) return
        targetAudioEnergy = (
            low.coerceIn(0f, 1f) * 0.25f +
                mid.coerceIn(0f, 1f) * 0.5f +
                high.coerceIn(0f, 1f) * 0.25f
            ).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        centerX = width / 2f
        centerY = height / 2f
        orbRadius = min(width, height) * ORB_RADIUS_RATIO
        if (orbRadius <= 0f) return

        clipPath.reset()
        clipPath.addCircle(centerX, centerY, orbRadius, Path.Direction.CW)
        createCachedCanvasShaders()
        runtimeRenderer?.configure(
            centerX = centerX,
            centerY = centerY,
            radius = orbRadius,
            skyTop = context.getColor(R.color.orb_sky_top),
            skyMid = context.getColor(R.color.orb_sky_mid),
            skyDeep = context.getColor(R.color.orb_sky_deep),
            skyShadow = context.getColor(R.color.orb_sky_shadow),
            cloudShadow = context.getColor(R.color.orb_cloud_shadow),
            cloud = context.getColor(R.color.orb_cloud),
            cloudHighlight = context.getColor(R.color.orb_cloud_highlight),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (orbRadius <= 0f) return

        val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        val visible = isShown && windowVisibility == VISIBLE
        updateAnimationClock(animationsEnabled && visible)

        glowPaint.shader = glowShader
        canvas.drawCircle(centerX, centerY, orbRadius * GLOW_RADIUS_RATIO, glowPaint)
        glowPaint.shader = null

        // RuntimeShader needs a hardware Canvas. Bitmap snapshots and software
        // rendering use the same fallback as Android versions before API 33.
        val renderer = if (canvas.isHardwareAccelerated) runtimeRenderer else null
        if (renderer != null) {
            renderer.draw(
                canvas = canvas,
                paint = spherePaint,
                centerX = centerX,
                centerY = centerY,
                radius = orbRadius,
                time = animationSeconds,
                activity = renderedActivity.coerceIn(0f, 1f),
            )
        } else {
            drawCanvasFallbackSphere(canvas)
        }

        if (animationsEnabled && visible) {
            if (renderer != null) {
                postInvalidateOnAnimation()
            } else {
                postInvalidateDelayed(FALLBACK_FRAME_DELAY_MS)
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
            renderedAudioEnergy = targetAudioEnergy
            renderedActivity = activityForState()
            return
        }

        val now = SystemClock.elapsedRealtimeNanos()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            0f
        } else {
            ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
        }
        lastFrameNanos = now

        val energyEasing = if (targetAudioEnergy > renderedAudioEnergy) {
            AUDIO_ATTACK_PER_SECOND
        } else {
            AUDIO_RELEASE_PER_SECOND
        }
        renderedAudioEnergy += (targetAudioEnergy - renderedAudioEnergy) *
            (deltaSeconds * energyEasing).coerceIn(0f, 1f)
        targetAudioEnergy = (targetAudioEnergy - deltaSeconds * AUDIO_TARGET_DECAY_PER_SECOND).coerceAtLeast(0f)

        val targetActivity = activityForState()
        renderedActivity += (targetActivity - renderedActivity) *
            (deltaSeconds * ACTIVITY_EASING_PER_SECOND).coerceIn(0f, 1f)
        animationSeconds += deltaSeconds * motionSpeedForState()
    }

    private fun activityForState(): Float = when (state) {
        State.IDLE -> IDLE_ACTIVITY
        State.PREVIEW -> PREVIEW_ACTIVITY + wave01(animationSeconds * 0.85f) * 0.06f
        State.LISTENING -> LISTENING_ACTIVITY + renderedAudioEnergy * 0.12f
        State.PROCESSING -> PROCESSING_ACTIVITY
        State.SPEAKING -> SPEAKING_ACTIVITY + renderedAudioEnergy * 0.18f
        State.SUCCESS, State.ERROR -> IDLE_ACTIVITY
    }

    private fun motionSpeedForState(): Float = when (state) {
        State.IDLE -> IDLE_MOTION_SPEED
        State.PREVIEW -> PREVIEW_MOTION_SPEED
        State.LISTENING -> LISTENING_MOTION_SPEED + renderedAudioEnergy * 0.18f
        State.PROCESSING -> PROCESSING_MOTION_SPEED
        State.SPEAKING -> SPEAKING_MOTION_SPEED + renderedAudioEnergy * 0.28f
        State.SUCCESS, State.ERROR -> IDLE_MOTION_SPEED
    }

    private fun drawCanvasFallbackSphere(canvas: Canvas) {
        spherePaint.shader = fallbackSkyShader
        canvas.drawCircle(centerX, centerY, orbRadius, spherePaint)

        canvas.save()
        canvas.clipPath(clipPath)
        drawFallbackCloudPass(
            canvas = canvas,
            shader = fallbackCloudShadowShader,
            alphaScale = 0.42f,
            radiusScale = 1.16f,
            yOffset = orbRadius * 0.07f,
            stride = 1,
        )
        drawFallbackCloudPass(
            canvas = canvas,
            shader = fallbackCloudShader,
            alphaScale = 0.88f,
            radiusScale = 1f,
            yOffset = 0f,
            stride = 1,
        )
        drawFallbackCloudPass(
            canvas = canvas,
            shader = fallbackCloudHighlightShader,
            alphaScale = 0.58f,
            radiusScale = 0.56f,
            yOffset = -orbRadius * 0.035f,
            stride = 2,
        )

        spherePaint.alpha = 255
        spherePaint.shader = fallbackSphereShadeShader
        canvas.drawCircle(centerX, centerY, orbRadius, spherePaint)
        spherePaint.shader = fallbackSphereLightShader
        canvas.drawCircle(centerX, centerY, orbRadius, spherePaint)
        canvas.restore()
        spherePaint.shader = null
    }

    private fun drawFallbackCloudPass(
        canvas: Canvas,
        shader: Shader?,
        alphaScale: Float,
        radiusScale: Float,
        yOffset: Float,
        stride: Int,
    ) {
        cloudPaint.shader = shader
        var index = 0
        while (index < cloudPuffs.size) {
            val puff = cloudPuffs[index]
            val x = wrapCloudX(
                puff.x + animationSeconds * puff.speed +
                    sin(animationSeconds * 0.09f + puff.phase) * 0.035f,
            )
            val y = puff.y + sin(animationSeconds * (0.1f + puff.layer * 0.018f) + puff.phase) * 0.055f
            val morph = 0.94f + sin(animationSeconds * 0.13f + puff.phase * 1.7f) * 0.08f
            val opacity = 0.82f + sin(animationSeconds * 0.11f + puff.phase) * 0.18f
            // Fade each formation outside the sphere before wrapping so that a
            // recycled cloud cannot pop into view along the circular edge.
            val edgeFade = ((CLOUD_WRAP_MAX - abs(x)) / CLOUD_WRAP_FADE_WIDTH).coerceIn(0f, 1f)

            cloudPaint.alpha = (puff.alpha * alphaScale * opacity * edgeFade).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(centerX + x * orbRadius, centerY + y * orbRadius + yOffset)
            canvas.scale(
                orbRadius * puff.radiusX * radiusScale * morph,
                orbRadius * puff.radiusY * radiusScale / morph,
            )
            canvas.drawCircle(0f, 0f, 1f, cloudPaint)
            canvas.restore()
            index += stride
        }
        cloudPaint.alpha = 255
        cloudPaint.shader = null
    }

    private fun createCachedCanvasShaders() {
        val glow = context.getColor(R.color.orb_glow)
        val skyTop = context.getColor(R.color.orb_sky_top)
        val skyMid = context.getColor(R.color.orb_sky_mid)
        val skyDeep = context.getColor(R.color.orb_sky_deep)
        val skyShadow = context.getColor(R.color.orb_sky_shadow)
        val cloudShadow = context.getColor(R.color.orb_cloud_shadow)
        val cloud = context.getColor(R.color.orb_cloud)
        val cloudHighlight = context.getColor(R.color.orb_cloud_highlight)

        glowShader = RadialGradient(
            centerX,
            centerY,
            orbRadius * GLOW_RADIUS_RATIO,
            intArrayOf(
                withAlpha(glow, 34),
                withAlpha(glow, 20),
                withAlpha(glow, 7),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0.54f, 0.69f, 0.84f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackSkyShader = RadialGradient(
            centerX - orbRadius * 0.28f,
            centerY - orbRadius * 0.34f,
            orbRadius * 1.52f,
            intArrayOf(skyTop, skyMid, skyDeep),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackCloudShadowShader = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(cloudShadow, withAlpha(cloudShadow, 190), Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackCloudShader = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(cloudHighlight, cloud, withAlpha(cloud, 165), Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 0.63f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackCloudHighlightShader = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(cloudHighlight, withAlpha(cloudHighlight, 205), Color.TRANSPARENT),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackSphereShadeShader = RadialGradient(
            centerX - orbRadius * 0.18f,
            centerY - orbRadius * 0.22f,
            orbRadius * 1.3f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(skyShadow, 94), withAlpha(skyShadow, 178)),
            floatArrayOf(0f, 0.55f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        fallbackSphereLightShader = RadialGradient(
            centerX - orbRadius * 0.34f,
            centerY - orbRadius * 0.42f,
            orbRadius * 0.72f,
            intArrayOf(withAlpha(Color.WHITE, 72), withAlpha(Color.WHITE, 16), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun createRuntimeRendererIfSupported(): RuntimeSkyRenderer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            RuntimeSkyRenderer(SKY_SHADER_SOURCE)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun wrapCloudX(value: Float): Float {
        val width = CLOUD_WRAP_MAX - CLOUD_WRAP_MIN
        var wrapped = (value - CLOUD_WRAP_MIN) % width
        if (wrapped < 0f) wrapped += width
        return wrapped + CLOUD_WRAP_MIN
    }

    private fun createCloudPuffs(): Array<CloudPuff> = arrayOf(
        CloudPuff(-1.28f, -0.48f, 0.48f, 0.24f, 0.2f, 0.020f, 215, 0),
        CloudPuff(-0.94f, -0.43f, 0.5f, 0.28f, 1.1f, 0.019f, 232, 0),
        CloudPuff(-0.55f, -0.5f, 0.43f, 0.24f, 2.3f, 0.022f, 224, 0),
        CloudPuff(-0.2f, -0.39f, 0.52f, 0.29f, 3.2f, 0.018f, 220, 0),
        CloudPuff(0.22f, -0.46f, 0.42f, 0.23f, 4.4f, 0.021f, 206, 0),
        CloudPuff(0.58f, -0.34f, 0.5f, 0.27f, 5.5f, 0.017f, 218, 0),
        CloudPuff(0.96f, -0.42f, 0.44f, 0.24f, 6.2f, 0.020f, 210, 0),
        CloudPuff(1.3f, -0.31f, 0.52f, 0.28f, 0.8f, 0.019f, 226, 0),
        CloudPuff(-1.22f, 0.02f, 0.56f, 0.31f, 1.7f, 0.014f, 225, 1),
        CloudPuff(-0.78f, 0.08f, 0.48f, 0.29f, 2.8f, 0.016f, 236, 1),
        CloudPuff(-0.37f, -0.02f, 0.58f, 0.32f, 3.9f, 0.013f, 230, 1),
        CloudPuff(0.1f, 0.11f, 0.5f, 0.28f, 5.0f, 0.015f, 222, 1),
        CloudPuff(0.5f, 0.01f, 0.55f, 0.33f, 6.1f, 0.014f, 238, 1),
        CloudPuff(0.95f, 0.13f, 0.5f, 0.29f, 0.6f, 0.016f, 226, 1),
        CloudPuff(1.32f, 0.03f, 0.54f, 0.3f, 1.9f, 0.015f, 216, 1),
        CloudPuff(-1.3f, 0.48f, 0.5f, 0.27f, 2.5f, 0.011f, 210, 2),
        CloudPuff(-0.9f, 0.42f, 0.57f, 0.31f, 3.5f, 0.012f, 230, 2),
        CloudPuff(-0.43f, 0.52f, 0.49f, 0.27f, 4.7f, 0.010f, 220, 2),
        CloudPuff(-0.03f, 0.4f, 0.55f, 0.3f, 5.8f, 0.012f, 232, 2),
        CloudPuff(0.43f, 0.51f, 0.48f, 0.26f, 0.9f, 0.011f, 214, 2),
        CloudPuff(0.82f, 0.39f, 0.56f, 0.3f, 2.1f, 0.013f, 226, 2),
        CloudPuff(1.27f, 0.49f, 0.5f, 0.27f, 3.1f, 0.011f, 218, 2),
    )

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun wave01(value: Float): Float = (sin(value) + 1f) * 0.5f

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class CloudPuff(
        val x: Float,
        val y: Float,
        val radiusX: Float,
        val radiusY: Float,
        val phase: Float,
        val speed: Float,
        val alpha: Int,
        val layer: Int,
    )

    @SuppressLint("NewApi")
    private class RuntimeSkyRenderer(source: String) {
        private val shader = RuntimeShader(source)

        fun configure(
            centerX: Float,
            centerY: Float,
            radius: Float,
            skyTop: Int,
            skyMid: Int,
            skyDeep: Int,
            skyShadow: Int,
            cloudShadow: Int,
            cloud: Int,
            cloudHighlight: Int,
        ) {
            shader.setFloatUniform("center", centerX, centerY)
            shader.setFloatUniform("radius", radius)
            setColorUniform("skyTop", skyTop)
            setColorUniform("skyMid", skyMid)
            setColorUniform("skyDeep", skyDeep)
            setColorUniform("skyShadow", skyShadow)
            setColorUniform("cloudShadow", cloudShadow)
            setColorUniform("cloudColor", cloud)
            setColorUniform("cloudHighlight", cloudHighlight)
        }

        fun draw(
            canvas: Canvas,
            paint: Paint,
            centerX: Float,
            centerY: Float,
            radius: Float,
            time: Float,
            activity: Float,
        ) {
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("activity", activity)
            paint.shader = shader
            canvas.drawCircle(centerX, centerY, radius, paint)
            paint.shader = null
        }

        private fun setColorUniform(name: String, color: Int) {
            shader.setFloatUniform(
                name,
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
            )
        }
    }

    private companion object {
        const val ORB_RADIUS_RATIO = 0.35f
        const val GLOW_RADIUS_RATIO = 1.32f
        const val IDLE_ACTIVITY = 0.08f
        const val PREVIEW_ACTIVITY = 0.24f
        const val LISTENING_ACTIVITY = 0.18f
        const val PROCESSING_ACTIVITY = 0.34f
        const val SPEAKING_ACTIVITY = 0.25f
        const val IDLE_MOTION_SPEED = 0.42f
        const val PREVIEW_MOTION_SPEED = 0.78f
        const val LISTENING_MOTION_SPEED = 0.68f
        const val PROCESSING_MOTION_SPEED = 1.02f
        const val SPEAKING_MOTION_SPEED = 0.8f
        const val AUDIO_ATTACK_PER_SECOND = 8f
        const val AUDIO_RELEASE_PER_SECOND = 3.2f
        const val AUDIO_TARGET_DECAY_PER_SECOND = 1.7f
        const val ACTIVITY_EASING_PER_SECOND = 2.8f
        const val MAX_FRAME_DELTA_SECONDS = 0.05f
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val FALLBACK_FRAME_DELAY_MS = 33L
        const val CLOUD_WRAP_MIN = -1.55f
        const val CLOUD_WRAP_MAX = 1.55f
        const val CLOUD_WRAP_FADE_WIDTH = 0.55f

        const val SKY_SHADER_SOURCE = """
            uniform float2 center;
            uniform float radius;
            uniform float time;
            uniform float activity;
            uniform float3 skyTop;
            uniform float3 skyMid;
            uniform float3 skyDeep;
            uniform float3 skyShadow;
            uniform float3 cloudShadow;
            uniform float3 cloudColor;
            uniform float3 cloudHighlight;

            float hash21(float2 p) {
                p = fract(p * float2(123.34, 345.45));
                p += dot(p, p + 34.345);
                return fract(p.x * p.y);
            }

            float noise2(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash21(i);
                float b = hash21(i + float2(1.0, 0.0));
                float c = hash21(i + float2(0.0, 1.0));
                float d = hash21(i + float2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            float fbm(float2 p) {
                float value = 0.0;
                float amplitude = 0.5;
                for (int octave = 0; octave < 5; ++octave) {
                    value += amplitude * noise2(p);
                    p = float2(
                        p.x * 1.72 - p.y * 1.08,
                        p.x * 1.08 + p.y * 1.72
                    ) + float2(17.13, 9.71);
                    amplitude *= 0.52;
                }
                return value;
            }

            half4 main(float2 fragCoord) {
                float2 p = (fragCoord - center) / radius;
                float radiusSquared = dot(p, p);
                if (radiusSquared > 1.0) {
                    return half4(0.0);
                }

                float z = sqrt(max(0.0, 1.0 - radiusSquared));
                float2 sphereUv = p / (0.74 + z * 0.26);
                float2 wind = float2(time * 0.052, time * 0.009);

                float warpX = fbm(sphereUv * 1.12 + wind * 0.42 + float2(2.7, 5.3));
                float warpY = fbm(sphereUv * 1.18 - wind * 0.31 + float2(8.1, 1.9));
                float warpStrength = 0.42 + activity * 0.24;
                float2 warpedUv = sphereUv + (float2(warpX, warpY) - 0.5) * warpStrength;

                float broad = fbm(warpedUv * 1.32 + wind * 0.7);
                float body = fbm(warpedUv * 2.48 - wind * 0.37 + float2(4.2, 7.8));
                float detail = fbm(warpedUv * 5.15 + wind * 0.2 + float2(11.4, 3.6));
                float organicLift =
                    sin(warpedUv.x * 2.3 + time * 0.035) * 0.035 +
                    sin(warpedUv.y * 3.1 - time * 0.022) * 0.025;
                float cloudField = broad * 0.52 + body * 0.36 + detail * 0.12 + organicLift;
                float cloudMass = smoothstep(0.47, 0.68, cloudField);
                float denseCloud = smoothstep(0.58, 0.76, broad * 0.45 + body * 0.55 + organicLift);
                float haze = smoothstep(0.4, 0.57, broad) * (0.18 + activity * 0.06);

                float vertical = clamp(p.y * 0.5 + 0.5, 0.0, 1.0);
                float3 sky = mix(skyTop, skyDeep, smoothstep(0.0, 1.0, vertical));
                sky = mix(sky, skyMid, 0.42 + z * 0.16);
                sky = mix(sky, cloudShadow, haze);

                float3 cloudTone = mix(cloudShadow, cloudColor, 0.62 + denseCloud * 0.28);
                float3 color = mix(sky, cloudTone, cloudMass * 0.9);
                color = mix(color, cloudHighlight, denseCloud * 0.72);

                float3 normal = normalize(float3(p.x, p.y, z));
                float3 lightDirection = normalize(float3(-0.38, -0.52, 0.76));
                float diffuse = 0.9 + max(dot(normal, lightDirection), 0.0) * 0.1;
                color *= diffuse;

                float edge = pow(1.0 - z, 1.6);
                color = mix(color, skyShadow, edge * 0.43);
                float specular = pow(max(dot(normal, lightDirection), 0.0), 25.0);
                color += cloudHighlight * specular * 0.12;
                color += skyMid * pow(1.0 - z, 3.2) * 0.08;

                return half4(half3(clamp(color, 0.0, 1.0)), 1.0);
            }
        """
    }
}
