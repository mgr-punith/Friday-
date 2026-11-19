package com.example.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import com.example.assistant.R
import kotlin.math.max
import kotlin.math.min

class MicButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val microphoneIcon: Drawable = requireNotNull(context.getDrawable(R.drawable.ic_microphone_01)).mutate()
    private var active = false
    private var stopIcon = false

    init {
        isClickable = true
        isFocusable = true
        isSoundEffectsEnabled = false
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

    fun showStop(value: Boolean) {
        if (stopIcon == value) return
        stopIcon = value
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) * 0.41f
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = context.getColor(if (isPressed) R.color.control_pressed else if (active) R.color.control_primary else R.color.surface)
        paint.alpha = if (isEnabled) 255 else 100
        canvas.drawCircle(centerX, centerY, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, dp(1).toFloat())
        paint.color = context.getColor(if (active) R.color.accent else R.color.border)
        canvas.drawCircle(centerX, centerY, radius, paint)

        if (stopIcon) {
            paint.style = Paint.Style.FILL
            paint.color = context.getColor(if (active) R.color.control_primary_text else R.color.text_primary)
            val half = dp(8).toFloat()
            canvas.drawRoundRect(centerX - half, centerY - half, centerX + half, centerY + half,
                dp(3).toFloat(), dp(3).toFloat(), paint)
        } else drawMicrophone(canvas, centerX, centerY, radius)

        if (hasFocus()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = context.getColor(R.color.focus_ring)
            canvas.drawCircle(centerX, centerY, radius * 1.12f, paint)
        }

        paint.alpha = 255
    }

    private fun drawMicrophone(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val color = context.getColor(if (active) R.color.mic_icon else R.color.text_primary)
        val size = (radius * 1.08f).toInt()
        microphoneIcon.setTint(color)
        microphoneIcon.alpha = if (isEnabled) 255 else 100
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
