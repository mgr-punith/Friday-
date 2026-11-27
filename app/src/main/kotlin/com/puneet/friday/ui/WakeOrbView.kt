package com.puneet.friday.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Draws the compact assistant's glass orb and its processing and result shapes. */
internal class WakeOrbView(context: Context) : View(context) {
    enum class Shape(val widthFraction: Float, val loading: Boolean = false, val corner: Float = .49f) {
        GLASS(.40f), PROCESSING(.94f, true, .42f), EXPANDED_PROGRESS(1f, true, .42f), STATUS(1f)
    }

    private val density = resources.displayMetrics.density
    private val gutter = AppUi.dp(context, 2)
    private val motion = VoiceMotion()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val shell = Path()
    private val light = Path()
    private val glassMatrix = Matrix()
    private val dome = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0xFF080809.toInt(), Color.BLACK, 0xFF111518.toInt(), 0x9C63727A.toInt(), 0x69919CA2),
        floatArrayOf(0f, .37f, .55f, .78f, 1f), Shader.TileMode.CLAMP)
    private val outline = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0x52696F74, 0x15282A2B, 0x91F3FAFD.toInt()),
        floatArrayOf(0f, .40f, 1f), Shader.TileMode.CLAMP)
    private val seam = LinearGradient(-.5f, 0f, .5f, 0f,
        intArrayOf(0x00333435, 0xB5A4ADB2.toInt(), 0xFFFFFFFF.toInt(), 0xA396A6AD.toInt(), 0x00333435),
        floatArrayOf(.03f, .26f, .51f, .76f, .97f), Shader.TileMode.CLAMP)
    private val pill = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(0xFA0A0B0C.toInt(), 0xF2171D20.toInt(), 0xC12C373C.toInt(), 0x8C586B74.toInt()),
        floatArrayOf(0f, .29f, .76f, 1f), Shader.TileMode.CLAMP)
    private val glint = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xC2ECF7FF.toInt(), 0x525C7C8A, 0x005C7C8A),
        floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
    private val resultReflection = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xFFF8F5EA.toInt(), 0xD7D8DCE2.toInt(), 0x679EAAA9, 0x009EAAA9),
        floatArrayOf(0f, .20f, .60f, 1f), Shader.TileMode.CLAMP)

    private var shape = Shape.GLASS
    private var fraction = Shape.GLASS.widthFraction
    private var glass = 1f
    private var heightRatio = 1f
    private var pillCorner = Shape.GLASS.corner
    private var resultGlow = 0f
    private var labelAlpha = 0f
    private var pillDepth = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private var animating = false
    private var transition: ValueAnimator? = null
    private var labelHeight = 0
    var onPillReveal: ((Float) -> Unit)? = null

    /** Visible pill height in px, including its content padding, excluding the host's 2dp top/bottom gutters. */
    fun setLabelHeight(height: Int) {
        if (labelHeight == height) return
        labelHeight = height
        invalidate()
    }

    fun setShape(next: Shape) {
        if (shape == next) return
        val startLabel = if (shape.loading && next.loading) labelAlpha else 0f
        shape = next
        transition?.cancel()
        transition = null
        val startWidth = fraction
        val startGlass = glass
        val startHeight = heightRatio
        val startCorner = pillCorner
        val startGlow = resultGlow
        val endGlass = if (next == Shape.GLASS) 1f else 0f
        val endHeight = if (next == Shape.GLASS) 1f else 0f
        val endGlow = if (next == Shape.STATUS) 1f else 0f
        fun update(progress: Float) {
            fraction = startWidth + (next.widthFraction - startWidth) * progress
            glass = startGlass + (endGlass - startGlass) * progress
            heightRatio = startHeight + (endHeight - startHeight) * progress
            pillCorner = startCorner + (next.corner - startCorner) * progress
            resultGlow = startGlow + (endGlow - startGlow) * progress
            val revealed = ((progress - .82f) / .18f).coerceIn(0f, 1f)
            val alpha = if (next == Shape.GLASS) 0f else startLabel + (1f - startLabel) * revealed
            if (labelAlpha != alpha) {
                labelAlpha = alpha
                onPillReveal?.invoke(alpha)
            }
            invalidate()
        }
        if (!isShown || !ValueAnimator.areAnimatorsEnabled()) {
            update(1f)
        } else {
            update(0f)
            transition = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = PathInterpolator(.77f, 0f, .175f, 1f)
                addUpdateListener { update(it.animatedValue as Float) }
                start()
            }
        }
    }

    fun setAudioSignal(energy: Float, low: Float, mid: Float, high: Float) {
        motion.setTarget(energy, low, mid, high)
        if (animating) invalidate()
    }

    fun setAnimating(value: Boolean) {
        if (animating == value) return
        animating = value
        lastFrame = 0L
        if (!value) motion.reset()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        endTransition()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) endTransition()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) endTransition()
    }

    private fun endTransition() {
        transition?.end()
        transition = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 4f * density || height <= 2 * gutter) return
        val moving = animating && ValueAnimator.areAnimatorsEnabled()
        if (moving) {
            val now = SystemClock.elapsedRealtimeNanos()
            val seconds = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(.05f)
            lastFrame = now
            motion.advance(seconds)
            phase = (phase + seconds * 5.5f) % TWO_PI
        }
        val available = width - 4f * density
        val diameter = minOf(available * Shape.GLASS.widthFraction, 146f * density)
        val visualWidth = available * fraction - (available * Shape.GLASS.widthFraction - diameter) * glass
        val glassHeight = diameter * .83f
        val pillHeight = labelHeight.coerceIn(1, height - 2 * gutter).toFloat()
        val visualHeight = pillHeight + (glassHeight - pillHeight) * heightRatio

        canvas.save()
        canvas.translate(width / 2f, gutter.toFloat())
        canvas.scale(visualWidth, visualHeight)
        val capRadius = minOf(pillCorner * minOf(visualHeight, 74f * density), .49f * visualWidth)
        val capX = capRadius / visualWidth
        val capY = capRadius / visualHeight
        val cornerX = capX + (.48f - capX) * glass
        val cornerY = capY + (.49f - capY) * glass
        bounds.set(-.5f, 0f, .5f, 1f)
        shell.rewind()
        shell.addRoundRect(bounds, cornerX, cornerY, Path.Direction.CW)
        if (glass < 1f) {
            // Keep the see-through edge shallow as the dark body grows for wrapped text.
            val depth = minOf(.54f, 24f * density / visualHeight)
            if (depth != pillDepth) {
                pillDepth = depth
                glassMatrix.setScale(1f, depth)
                glassMatrix.postTranslate(0f, 1f - depth)
                pill.setLocalMatrix(glassMatrix)
            }
            fill(canvas, shell, pill, 1f - glass)
        }
        if (glass > 0f) {
            fill(canvas, shell, dome, glass)
            canvas.save()
            canvas.clipPath(shell)
            drawSeam(canvas)
            canvas.restore()
        }
        if (resultGlow > 0f) {
            canvas.save()
            canvas.clipPath(shell)
            val reflectionHeight = minOf(.23f, 16f * density / visualHeight)
            val centerY = 1f - 2f * density / visualHeight
            val opacity = resultGlow * (200 + 35 * motion.energy)
            drawGlint(canvas, 0f, centerY, .46f, reflectionHeight, opacity.toInt(), resultReflection)
            canvas.restore()
        }
        paint.shader = outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .008f
        paint.alpha = 205
        canvas.drawPath(shell, paint)
        canvas.restore()
        if (moving && isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawSeam(canvas: Canvas) {
        val strength = sqrt(motion.energy)
        val lift = strength * .085f
        val peak = .55f + lift * sin(phase)
        val edge = .56f + lift * .40f * sin(phase - 1.8f)
        drawGlint(canvas, .08f * strength * sin(phase), peak, .46f, .12f + .12f * strength, (glass * (112 + 96 * strength)).toInt())
        light.rewind()
        light.moveTo(-.47f, edge)
        light.cubicTo(-.28f, .59f + lift * sin(phase + 1.6f), -.15f, peak - lift * motion.low, 0f, peak)
        light.cubicTo(.20f, peak + lift * motion.mid, .31f, .58f - lift * sin(phase + motion.high), .47f, edge)
        light.cubicTo(.27f, edge + .021f, .12f, peak + .044f + .021f * strength, 0f, peak + .029f)
        light.cubicTo(-.21f, peak + .038f, -.30f, edge + .017f, -.47f, edge)
        light.close()
        fill(canvas, light, seam, glass)
    }

    private fun drawGlint(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, alpha: Int, shader: Shader = glint) {
        paint.style = Paint.Style.FILL
        paint.shader = shader
        paint.alpha = alpha
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    private fun fill(canvas: Canvas, path: Path, shader: Shader, opacity: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = shader
        paint.alpha = (255f * opacity).toInt()
        canvas.drawPath(path, paint)
    }

    private companion object {
        const val TWO_PI = (2 * PI).toFloat()
    }
}
