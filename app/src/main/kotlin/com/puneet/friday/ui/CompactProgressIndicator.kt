package com.puneet.friday.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.puneet.friday.R
import kotlin.math.min

/** A small activity indicator beside the compact assistant's processing text. */
internal class CompactProgressIndicator(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * resources.displayMetrics.density
        color = context.getColor(R.color.text_primary)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - pen.strokeWidth
        if (radius <= 0f) return
        pen.alpha = 64
        canvas.drawCircle(cx, cy, radius, pen)
        val moving = ValueAnimator.areAnimatorsEnabled()
        val angle = if (moving) (drawingTime % 1000L) * .36f else 0f
        pen.alpha = 235
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, angle - 90f, 105f, false, pen)
        if (moving && isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }
}
