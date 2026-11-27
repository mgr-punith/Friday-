package com.puneet.friday.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import com.puneet.friday.R
import java.util.function.Consumer

/** Shared Settings-style card for confirmations, disclosures and choices. */
object FridayDialog {
    private const val PADDING = 20

    fun show(
        activity: Activity,
        title: String,
        message: String,
        positive: String = "OK",
        negative: String? = null,
        destructive: Boolean = false,
        onCancel: () -> Unit = {},
        onPositive: () -> Unit = {},
    ): Dialog = create(activity, title, onCancel) { dialog, body, footer, width ->
        body.addView(AppUi.text(activity, message, 14f, secondary = true).apply {
            setLineSpacing(0f, 1.24f)
        }, AppUi.params(activity, 0))
        val positiveTone = if (destructive) AppUi.ButtonTone.DESTRUCTIVE else AppUi.ButtonTone.PRIMARY
        val accept = AppUi.button(activity, positive, positiveTone, radius = 24) {
            dialog.dismiss()
            onPositive()
        }
        val buttons = if (negative == null) listOf(accept) else listOf(
            AppUi.button(activity, negative, AppUi.ButtonTone.SECONDARY, radius = 24) {
                dialog.dismiss()
                onCancel()
            },
            accept,
        )

        val gap = AppUi.dp(activity, 8)
        val stacked = buttons.size > 1 && buttons.any { button ->
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            button.measure(unspecified, unspecified)
            button.measuredWidth > (width - gap) / 2
        }
        footer.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        buttons.forEachIndexed { index, button ->
            val params = if (stacked) AppUi.params(activity, 0).apply {
                if (index > 0) topMargin = gap
            } else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = gap
            }
            footer.addView(button, params)
        }
    }

    fun choices(
        activity: Activity,
        title: String,
        labels: List<String>,
        onCancel: () -> Unit = {},
        onChoice: (Int) -> Unit,
    ): Dialog = create(activity, title, onCancel) { dialog, body, footer, _ ->
        val choices = SettingsGroup(activity)
        labels.forEachIndexed { index, label ->
            choices.addRow(SettingsRow(activity, label, showChevron = false) {
                dialog.dismiss()
                onChoice(index)
            })
        }
        body.addView(choices, AppUi.params(activity, 0))
        footer.addView(AppUi.button(activity, "Cancel", AppUi.ButtonTone.SECONDARY, radius = 24) {
            dialog.dismiss()
            onCancel()
        }, AppUi.params(activity, 0))
    }

    internal fun create(
        activity: Activity,
        title: String,
        onCancel: () -> Unit,
        gravity: Int = Gravity.CENTER,
        trailing: View? = null,
        glass: Boolean = false,
        actions: (Dialog, LinearLayout, LinearLayout, Int) -> Unit,
    ): Dialog {
        val dialog = if (glass) Dialog(activity, R.style.Theme_Friday_GlassDialog) else Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val visible = Rect()
        activity.window.decorView.getWindowVisibleDisplayFrame(visible)
        val metrics = activity.resources.displayMetrics
        val windowWidth = visible.width().takeIf { it > 0 } ?: metrics.widthPixels
        val windowHeight = visible.height().takeIf { it > 0 } ?: metrics.heightPixels
        val margin = AppUi.dp(activity, 16)
        val width = minOf(windowWidth - margin * 2, AppUi.dp(activity, 400)).coerceAtLeast(1)
        val padding = AppUi.dp(activity, PADDING)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            if (!glass) background = AppUi.surface(context, 24, R.color.settings_surface, R.color.settings_divider)
        }
        val heading = AppUi.text(activity, title, 18f, bold = true).apply {
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        if (trailing == null) content.addView(heading, AppUi.params(activity, 10))
        else content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER_VERTICAL
            addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(trailing, LinearLayout.LayoutParams(AppUi.dp(activity, 48), AppUi.dp(activity, 48)))
        }, AppUi.params(activity, if (glass) 5 else 10))
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(activity).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body)
        }
        content.addView(scroller, AppUi.params(activity, 20))
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(footer, AppUi.params(activity, 0))
        actions(dialog, body, footer, width - padding * 2)

        dialog.setContentView(content)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancel() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (glass) .13f else .6f)
            setGravity(gravity)
            if (gravity and Gravity.TOP == Gravity.TOP) attributes = attributes.apply { y = margin }
        }
        if (glass) dialog.window?.let { styleGlass(activity, content, it) }
        dialog.show()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxHeight = (windowHeight - margin * 2).coerceAtLeast(1)
        if (content.measuredHeight > maxHeight) {
            scroller.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { bottomMargin = padding }
            dialog.window?.setLayout(width, maxHeight)
        } else {
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun styleGlass(activity: Activity, content: View, window: Window) {
        val radius = AppUi.dp(activity, 30).toFloat()
        fun apply(blurred: Boolean) {
            window.setBackgroundDrawable(GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (blurred) intArrayOf(0xD13F454A.toInt(), 0xE3181C20.toInt(), 0xDC33393E.toInt())
                else intArrayOf(0xF033393F.toInt(), 0xF8181C20.toInt(), 0xF1272D33.toInt()),
            ).apply {
                cornerRadius = radius
                setStroke(AppUi.dp(activity, 1), 0x66DDE3E7)
            })
            if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(if (blurred) AppUi.dp(activity, 26) else 0)
        }

        apply(false)
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = activity.getSystemService(WindowManager::class.java) ?: return
            val listener = Consumer<Boolean> { apply(it) }
            content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    manager.addCrossWindowBlurEnabledListener(activity.mainExecutor, listener)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    manager.removeCrossWindowBlurEnabledListener(listener)
                }
            })
        }
    }
}
