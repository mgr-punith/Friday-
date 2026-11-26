package com.puneet.friday.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Silver ribbons drawn from the current audio envelope. This view never captures audio. */
internal class VoiceWaveformView(context: Context) : View(context) {
    private val motion = VoiceMotion()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbon = Path()
    private val lowerEdge = Path()
    private val taper = FloatArray(SAMPLES + 1) { sin(PI.toFloat() * it / SAMPLES) }
    private val waveSin = FloatArray(SAMPLES + 1) { sin(PI.toFloat() * 3f * it / SAMPLES) }
    private val waveCos = FloatArray(SAMPLES + 1) { cos(PI.toFloat() * 3f * it / SAMPLES) }
    private val lowWave = Array(3) { layer -> FloatArray(SAMPLES + 1) { sin(TWO_PI * it / SAMPLES + layer) } }
    private val midSin = FloatArray(SAMPLES + 1) { sin(TWO_PI * 2f * it / SAMPLES) }
    private val midCos = FloatArray(SAMPLES + 1) { cos(TWO_PI * 2f * it / SAMPLES) }
    private val highSin = FloatArray(SAMPLES + 1) { sin(TWO_PI * 3f * it / SAMPLES) }
    private val highCos = FloatArray(SAMPLES + 1) { cos(TWO_PI * 3f * it / SAMPLES) }
    private val upperPoints = FloatArray(SAMPLES + 1)
    private val lowerPoints = FloatArray(SAMPLES + 1)
    private var fill: Shader? = null
    private var highlight: Shader? = null
    private var shadow: Shader? = null
    private var animating = false
    private var previousFrame = 0L
    private var phase = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun setAudioSignal(energy: Float, low: Float, mid: Float, high: Float) {
        motion.setTarget(energy, low, mid, high)
        invalidate()
    }

    fun setAnimating(value: Boolean) {
        if (animating == value) return
        animating = value
        previousFrame = 0L
        if (!value) motion.reset()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill = LinearGradient(0f, h * 0.04f, 0f, h * 0.96f,
            intArrayOf(0x08FFFFFF, 0xD9FFFFFF.toInt(), 0x25D5D5D5, 0xBDFFFFFF.toInt(), 0x08FFFFFF),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f), Shader.TileMode.CLAMP)
        highlight = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0x00FFFFFF, 0x88FFFFFF.toInt(), 0xF0FFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f), Shader.TileMode.CLAMP)
        shadow = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0xAA000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = SystemClock.elapsedRealtimeNanos()
        val delta = if (previousFrame == 0L || !animating) 0f
        else ((now - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
        previousFrame = now
        motion.advance(delta)
        if (animating && motion.energy > 0.002f) phase = (phase + delta * 0.7f) % TWO_PI

        val density = resources.displayMetrics.density
        val center = height / 2f
        val amplitude = minOf(height * 0.36f, 26f * density) * sqrt(motion.energy) + 1.4f * density
        val left = width * 0.025f
        val span = width * 0.95f
        repeat(3) { layer ->
            ribbon.rewind()
            lowerEdge.rewind()
            // The sample harmonics never change. Only rotate their coefficients once
            // per layer, instead of evaluating thousands of trig functions per frame.
            val offset = (layer - 1) * 0.50f + sin(phase + layer * 0.55f) * 0.32f
            val offsetSin = sin(offset)
            val offsetCos = cos(offset)
            val lowerSin = sin(offset + 0.30f)
            val lowerCos = cos(offset + 0.30f)
            val layerAmplitude = amplitude * (1f - layer * 0.13f)
            for (sample in 0..SAMPLES) {
                val spectrum = motion.low * lowWave[layer][sample] * 0.08f +
                    motion.mid * (midSin[sample] * offsetCos - midCos[sample] * offsetSin) * 0.06f +
                    motion.high * (highSin[sample] * offsetCos + highCos[sample] * offsetSin) * 0.025f
                val scale = layerAmplitude * taper[sample]
                upperPoints[sample] = center + scale *
                    (waveSin[sample] * offsetCos + waveCos[sample] * offsetSin + spectrum)
                lowerPoints[sample] = center - scale *
                    (waveSin[sample] * lowerCos + waveCos[sample] * lowerSin + spectrum) * 0.84f
            }
            val step = span / SAMPLES
            ribbon.moveTo(left, upperPoints[0])
            appendCurve(ribbon, upperPoints, left, step)
            ribbon.lineTo(left + span, lowerPoints[SAMPLES])
            appendCurve(ribbon, lowerPoints, left, step, reverse = true)
            lowerEdge.moveTo(left + span, lowerPoints[SAMPLES])
            appendCurve(lowerEdge, lowerPoints, left, step, reverse = true)
            ribbon.close()
            canvas.save()
            canvas.translate(0f, density * 2.5f)
            paint.style = Paint.Style.FILL
            paint.shader = shadow
            paint.alpha = 150
            canvas.drawPath(ribbon, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * 7f
            paint.alpha = 35
            canvas.drawPath(lowerEdge, paint)
            canvas.restore()

            paint.style = Paint.Style.FILL
            paint.shader = fill
            paint.alpha = if (layer == 0) 245 else if (layer == 1) 185 else 135
            canvas.drawPath(ribbon, paint)

            paint.style = Paint.Style.STROKE
            paint.shader = highlight
            // Broad, low-opacity edges supply a small halo without a bitmap or blur layer.
            paint.strokeWidth = density * 9f
            paint.alpha = 10
            canvas.drawPath(ribbon, paint)
            paint.strokeWidth = density * 0.65f
            paint.alpha = if (layer == 0) 240 else 140
            canvas.drawPath(ribbon, paint)
        }
        paint.shader = highlight
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 0.6f
        paint.alpha = 55
        canvas.drawLine(left, center, left + span, center, paint)
        paint.shader = null
        paint.alpha = 255
        if (animating && isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun appendCurve(path: Path, points: FloatArray, left: Float, step: Float, reverse: Boolean = false) {
        val direction = if (reverse) -1 else 1
        val thirdStep = step * direction / 3f
        var from = if (reverse) SAMPLES else 0
        repeat(SAMPLES) {
            val to = from + direction
            path.cubicTo(
                left + from * step + thirdStep, points[from] + tangent(points, from) * direction / 3f,
                left + to * step - thirdStep, points[to] - tangent(points, to) * direction / 3f,
                left + to * step, points[to],
            )
            from = to
        }
    }

    private fun tangent(points: FloatArray, index: Int): Float = when (index) {
        0 -> points[1] - points[0]
        SAMPLES -> points[SAMPLES] - points[SAMPLES - 1]
        else -> (points[index + 1] - points[index - 1]) * 0.5f
    }

    private companion object {
        // Cubic interpolation keeps smooth edges with fewer path vertices for the GPU.
        const val SAMPLES = 32
        const val TWO_PI = (PI * 2).toFloat()
    }
}
