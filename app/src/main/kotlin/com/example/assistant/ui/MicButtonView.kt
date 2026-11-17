package com.example.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
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
    private val microphoneIcon: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_microphone_01)).mutate()
    private var active = false

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = dp(56)
        minimumHeight = dp(56)
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
        val pulse = if (active && ValueAnimator.areAnimatorsEnabled()) {
            ((sin(SystemClock.uptimeMillis() / 210.0) + 1.0) * 0.5).toFloat()
        } else 0f

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = context.getColor(if (active) R.color.mic_button else R.color.surface)
        canvas.drawCircle(centerX, centerY, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, dp(1).toFloat())
        paint.color = context.getColor(if (active) R.color.accent else R.color.border)
        canvas.drawCircle(centerX, centerY, radius, paint)

        if (active && pulse > 0f) {
            paint.alpha = (55 + pulse * 35).toInt()
            paint.strokeWidth = dp(2).toFloat()
            paint.color = context.getColor(R.color.accent)
            canvas.drawCircle(centerX, centerY, radius * (1.08f + pulse * 0.03f), paint)
            paint.alpha = 255
        }
        drawMicrophone(canvas, centerX, centerY, radius)

        if (hasFocus()) {
            paint.strokeWidth = dp(2).toFloat()
            paint.color = context.getColor(R.color.focus_ring)
            canvas.drawCircle(centerX, centerY, radius * 1.12f, paint)
        }

        if (active && ValueAnimator.areAnimatorsEnabled() && isShown) {
            postInvalidateDelayed(40L)
        }
    }

    private fun drawMicrophone(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val color = context.getColor(if (active) R.color.mic_icon else R.color.text_primary)
        val size = (radius * 1.08f).toInt()
        microphoneIcon.setTint(color)
        microphoneIcon.setBounds(
            (centerX - size / 2f).toInt(),
            (centerY - size / 2f).toInt(),
            (centerX + size / 2f).toInt(),
            (centerY + size / 2f).toInt(),
        )
        microphoneIcon.draw(canvas)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
