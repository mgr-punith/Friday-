package com.example.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.assistant.R

/** Friday's compact modal surface, with native focus and dismissal behavior. */
object FridayDialog {
    fun show(
        activity: Activity,
        title: String,
        message: String,
        positive: String = "OK",
        negative: String? = null,
        onCancel: () -> Unit = {},
        onPositive: () -> Unit = {},
    ): Dialog = create(activity, title, message, onCancel) { dialog, _, footer ->
        val actions = LinearLayout(activity).apply {
            val needsStack = activity.resources.configuration.fontScale >= 1.3f ||
                positive.length + (negative?.length ?: 0) > 24
            orientation = if (needsStack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (negative != null) {
            actions.addView(
                AppUi.button(activity, negative, AppUi.ButtonTone.QUIET) {
                    dialog.dismiss()
                    onCancel()
                },
                LinearLayout.LayoutParams(
                    if (actions.orientation == LinearLayout.VERTICAL) ViewGroup.LayoutParams.MATCH_PARENT
                    else ViewGroup.LayoutParams.WRAP_CONTENT,
                    AppUi.dp(activity, 48),
                ).apply {
                    if (actions.orientation == LinearLayout.HORIZONTAL) marginEnd = AppUi.dp(activity, 8)
                    else bottomMargin = AppUi.dp(activity, 4)
                },
            )
        }
        actions.addView(
            AppUi.button(activity, positive, AppUi.ButtonTone.PRIMARY) {
                dialog.dismiss()
                onPositive()
            },
            LinearLayout.LayoutParams(
                if (actions.orientation == LinearLayout.VERTICAL) ViewGroup.LayoutParams.MATCH_PARENT
                else ViewGroup.LayoutParams.WRAP_CONTENT,
                AppUi.dp(activity, 48),
            ),
        )
        footer.addView(actions, AppUi.params(activity, 0))
    }

    fun choices(
        activity: Activity,
        title: String,
        labels: List<String>,
        onCancel: () -> Unit = {},
        onChoice: (Int) -> Unit,
    ): Dialog = create(activity, title, "Choose one to continue.", onCancel) { dialog, content, footer ->
        labels.forEachIndexed { index, label ->
            content.addView(
                AppUi.button(activity, label, AppUi.ButtonTone.SECONDARY) {
                    dialog.dismiss()
                    onChoice(index)
                },
                AppUi.params(activity, 8),
            )
        }
        footer.addView(
            AppUi.button(activity, "Cancel", AppUi.ButtonTone.QUIET) {
                dialog.dismiss()
                onCancel()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AppUi.dp(activity, 48)).apply {
                gravity = Gravity.END
            },
        )
    }

    private fun create(
        activity: Activity,
        title: String,
        message: String,
        onCancel: () -> Unit,
        actions: (Dialog, LinearLayout, LinearLayout) -> Unit,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                AppUi.dp(context, 22), AppUi.dp(context, 20),
                AppUi.dp(context, 22), AppUi.dp(context, 18),
            )
            background = AppUi.surface(context, 16, R.color.surface, R.color.border)
        }
        content.addView(AppUi.text(activity, title, 20f, bold = true), AppUi.params(activity, 8))
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            AppUi.text(activity, message, 15f, secondary = true).apply {
                importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_YES
            },
            AppUi.params(activity, 16),
        )
        val scroller = ScrollView(activity).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body)
        }
        content.addView(scroller, AppUi.params(activity, 4))
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(footer, AppUi.params(activity, 0))
        actions(dialog, body, footer)

        dialog.setContentView(content)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onCancel() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.72f)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
        val metrics = activity.resources.displayMetrics
        val dialogWidth = minOf(metrics.widthPixels - AppUi.dp(activity, 40), AppUi.dp(activity, 400))
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxHeight = (metrics.heightPixels * 0.82f).toInt()
        if (content.measuredHeight > maxHeight) {
            scroller.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { bottomMargin = AppUi.dp(activity, 4) }
            dialog.window?.setLayout(dialogWidth, maxHeight)
        } else {
            dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }
}
