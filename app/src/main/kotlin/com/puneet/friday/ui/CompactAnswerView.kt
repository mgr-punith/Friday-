package com.puneet.friday.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.puneet.friday.R
import com.puneet.friday.voice.InformationAnswer

/** The answer and its controls share the compact assistant's glass background. */
@SuppressLint("ViewConstructor") // Constructed with a specific answer and its actions.
internal class CompactAnswerView(
    activity: Activity,
    answer: InformationAnswer,
    onToggle: (Boolean) -> Unit,
    onSource: (String) -> Unit,
    onReport: () -> Unit,
    onStop: () -> Unit,
) : ScrollView(activity) {
    private val body = AppUi.text(activity, answer.text, 16f).apply {
        setLineSpacing(0f, 1.16f)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val arrow = AppUi.iconButton(activity, R.drawable.ic_chevron_right, "Answer details") {
        onToggle(!expanded)
    }
    private val controls = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(26), 0, dp(26), dp(20))
        visibility = GONE
    }
    private val stop = AppUi.button(activity, "Stop reading", AppUi.ButtonTone.QUIET, onClick = onStop).apply {
        background = glassControl(activity)
        visibility = GONE
    }
    private var expanded = false

    init {
        val paragraph = FrameLayout(activity).apply {
            minimumHeight = dp(72)
            addView(body, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            addView(arrow, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(4)
            })
        }
        if (answer.kind == "knowledge") controls.addView(
            AppUi.text(activity, "General information", 13f, secondary = true), AppUi.params(activity, 8),
        )
        if (answer.continuation != null) controls.addView(
            AppUi.text(activity, "Friday will listen for your reply.", 13f, secondary = true), AppUi.params(activity, 8),
        )
        if (answer.sources.isNotEmpty()) {
            controls.addView(AppUi.text(activity, "Sources", 13f, secondary = true), AppUi.params(activity, 4))
            answer.sources.forEach {
                controls.addView(answerSourceButton(activity, it, onSource), AppUi.params(activity, 6))
            }
        }
        controls.addView(stop, AppUi.params(activity, 6))
        controls.addView(AppUi.button(activity, "Report this answer", AppUi.ButtonTone.QUIET, onClick = onReport)
            .apply { background = glassControl(activity) }, AppUi.params(activity, 0))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(paragraph, AppUi.params(activity, 0))
            addView(controls, AppUi.params(activity, 0))
        })
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val end = dp(54)
        val single = Layout.getDesiredWidth(body.text, body.paint) <= MeasureSpec.getSize(widthMeasureSpec) - 2 * end
        body.gravity = if (single) Gravity.CENTER else Gravity.START
        body.setPaddingRelative(if (single) end else dp(26), dp(22), end, dp(22))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setExpanded(open: Boolean) {
        if (expanded == open) return
        expanded = open
        body.setTextIsSelectable(open)
        controls.visibility = if (open) VISIBLE else GONE
        arrow.rotation = if (open) 90f else 0f
        arrow.contentDescription = if (open) "Hide answer details" else "Answer details"
        if (!open) scrollTo(0, 0)
    }

    fun setSpeaking(speaking: Boolean) {
        stop.visibility = if (speaking) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int) = AppUi.dp(context, value)
}
