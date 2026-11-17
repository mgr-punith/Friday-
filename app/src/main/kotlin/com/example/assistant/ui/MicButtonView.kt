package com.example.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import com.example.assistant.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MicButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = dp(64)
        minimumHeight = dp(64)
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        isSelected = isActive
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) * 0.41f
        val pressedScale = if (isPressed) 0.95f else 1f
        val pulse = if (active && ValueAnimator.areAnimatorsEnabled()) {
            ((sin(SystemClock.uptimeMillis() / 180.0) + 1.0) * 0.5).toFloat()
        } else {
            0f
        }

        canvas.save()
        canvas.scale(pressedScale, pressedScale, centerX, centerY)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = context.getColor(R.color.mic_glow)
        canvas.drawCircle(centerX, centerY, radius * (1.12f + pulse * 0.08f), paint)

        paint.shader = RadialGradient(
            centerX - radius * 0.3f,
            centerY - radius * 0.35f,
            radius * 1.45f,
            intArrayOf(
                context.getColor(R.color.mic_button_highlight),
                context.getColor(R.color.mic_button),
                context.getColor(R.color.mic_button_deep),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.shader = null
        drawMicrophone(canvas, centerX, centerY, radius)
        if (hasFocus()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(2f, radius * 0.08f)
            paint.color = context.getColor(R.color.focus_ring)
            canvas.drawCircle(centerX, centerY, radius * 1.1f, paint)
        }
        canvas.restore()

        if (active && ValueAnimator.areAnimatorsEnabled() && isShown) {
            postInvalidateDelayed(40L)
        }
    }

    private fun drawMicrophone(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val color = context.getColor(R.color.mic_icon)
        val bodyHalfWidth = radius * 0.16f
        val bodyTop = centerY - radius * 0.4f
        val bodyBottom = centerY + radius * 0.08f
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(
            centerX - bodyHalfWidth,
            bodyTop,
            centerX + bodyHalfWidth,
            bodyBottom,
            bodyHalfWidth,
            bodyHalfWidth,
            paint,
        )

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(2.5f, radius * 0.09f)
        paint.color = color
        val pickupRadius = radius * 0.3f
        canvas.drawArc(
            centerX - pickupRadius,
            centerY - radius * 0.18f,
            centerX + pickupRadius,
            centerY + radius * 0.37f,
            0f,
            180f,
            false,
            paint,
        )
        canvas.drawLine(centerX, centerY + radius * 0.37f, centerX, centerY + radius * 0.55f, paint)
        canvas.drawLine(
            centerX - radius * 0.22f,
            centerY + radius * 0.55f,
            centerX + radius * 0.22f,
            centerY + radius * 0.55f,
            paint,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
