package com.puneet.friday.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import com.puneet.friday.R

/** One focusable native popup for menus and help. It never changes a setting. */
class AnchoredPopup(private val activity: Activity) {
    private var window: PopupWindow? = null

    fun showHelp(anchor: View, explanation: String) {
        val text = AppUi.text(activity, explanation, 14f).apply {
            setTextColor(activity.getColor(R.color.help_text))
            setLineSpacing(0f, 1.2f)
            setPadding(AppUi.dp(context, 14), AppUi.dp(context, 12), AppUi.dp(context, 14), AppUi.dp(context, 12))
        }
        show(anchor, text, withPointer = true)
    }

    fun show(
        anchor: View,
        content: View,
        widthDp: Int = 280,
        withPointer: Boolean = false,
        label: String = if (withPointer) "Help" else "About Friday",
        onDismiss: () -> Unit = {},
    ) {
        dismiss()
        if (!anchor.isAttachedToWindow || !anchor.isShown || activity.isFinishing || activity.isDestroyed) return
        val visible = Rect()
        anchor.getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val margin = AppUi.dp(activity, 16)
        val width = minOf(AppUi.dp(activity, widthDp), visible.width() - margin * 2)
        val maxHeight = visible.height() - margin * 2
        if (width <= 0 || maxHeight <= 0) return
        val body = BubbleFrame(activity, withPointer).apply {
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = label
        }
        val scroll = ScrollView(activity).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        body.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.configure(PopupPlacement.Side.ABOVE, width / 2)
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val placement = PopupPlacement.calculate(
            PopupPlacement.Bounds(visible.left, visible.top, visible.right, visible.bottom),
            PopupPlacement.Bounds(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height),
            width, body.measuredHeight, margin, AppUi.dp(activity, 4), AppUi.dp(activity, 22),
        ) ?: return
        body.configure(placement.side, placement.pointerX)
        val popup = PopupWindow(body, placement.width, placement.height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = AppUi.dp(activity, 8).toFloat()
            animationStyle = 0
            setOnDismissListener {
                window = null
                if (anchor.isAttachedToWindow && anchor.isShown) {
                    anchor.requestFocusFromTouch()
                }
                onDismiss()
            }
        }
        body.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                popup.dismiss()
                true
            } else false
        }
        window = popup
        val screen = IntArray(2)
        val inWindow = IntArray(2)
        anchor.rootView.getLocationOnScreen(screen)
        anchor.rootView.getLocationInWindow(inWindow)
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT,
            placement.left - screen[0] + inWindow[0], placement.top - screen[1] + inWindow[1])
        body.requestFocus()
    }

    fun dismiss() { window?.dismiss(); window = null }

    private class BubbleFrame(context: Context, private val pointer: Boolean) : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.help_background)
        }
        private val tip = if (pointer) AppUi.dp(context, 8) else 0
        private val radius = AppUi.dp(context, 16).toFloat()
        private var side = PopupPlacement.Side.ABOVE
        private var pointerX = 0
        private val triangle = Path()

        init {
            if (pointer) setWillNotDraw(false) else {
                background = AppUi.surface(context, 20, R.color.settings_surface, R.color.settings_divider)
                clipToOutline = true
            }
        }

        fun configure(side: PopupPlacement.Side, pointerX: Int) {
            this.side = side
            this.pointerX = pointerX
            setPadding(0, if (side == PopupPlacement.Side.BELOW) tip else 0, 0,
                if (side == PopupPlacement.Side.ABOVE) tip else 0)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!pointer) return
            val top = if (side == PopupPlacement.Side.BELOW) tip.toFloat() else 0f
            val bottom = height.toFloat() - if (side == PopupPlacement.Side.ABOVE) tip else 0
            canvas.drawRoundRect(0f, top, width.toFloat(), bottom, radius, radius, paint)
            val x = pointerX.toFloat()
            val base = if (side == PopupPlacement.Side.ABOVE) bottom else top
            val end = if (side == PopupPlacement.Side.ABOVE) height.toFloat() else 0f
            triangle.reset()
            triangle.moveTo(x - tip, base)
            triangle.lineTo(x, end)
            triangle.lineTo(x + tip, base)
            triangle.close()
            canvas.drawPath(triangle, paint)
        }
    }
}
