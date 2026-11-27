package com.puneet.friday.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.puneet.friday.R
import com.puneet.friday.voice.AnswerSource
import com.puneet.friday.voice.InformationAnswer

/** Native, scrollable answer card. Links open only after an explicit user tap. */
class AnswerDialog(
    activity: Activity,
    answer: InformationAnswer,
    speechAvailable: Boolean,
    onStop: () -> Unit,
    onOptions: (Boolean) -> Unit,
    onSource: (String) -> Unit,
    onReport: () -> Unit,
    onClose: () -> Unit,
) {
    private lateinit var stop: Button
    private var speaking = false
    private var hasSpeech = speechAvailable
    private val menu = AnchoredPopup(activity)
    private val options = AppUi.roundIconButton(activity, R.drawable.ic_more_vertical, "Answer options") {}.apply {
        background = glassControl(activity)
    }
    private val dialog: Dialog = FridayDialog.create(activity, "Quick answer", {}, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        trailing = options, glass = true) { _, body, footer, _ ->
        if (answer.kind == "knowledge") body.addView(AppUi.text(activity, "General information", 12f, secondary = true), AppUi.params(activity, 6))
        body.addView(AppUi.text(activity, answer.text, 17f).apply {
            setLineSpacing(0f, 1.28f)
            setTextIsSelectable(true)
        }, AppUi.params(activity, 12))
        if (answer.continuation != null) body.addView(
            AppUi.text(activity, "Friday will listen for your reply.", 12f, secondary = true), AppUi.params(activity, 8),
        )
        if (answer.sources.isNotEmpty()) {
            body.addView(AppUi.text(activity, "Sources", 12f, secondary = true), AppUi.params(activity, 4))
            answer.sources.forEach { source ->
                body.addView(answerSourceButton(activity, source, onSource), AppUi.params(activity, 6))
            }
        }
        stop = AppUi.button(activity, "Stop reading", AppUi.ButtonTone.SECONDARY) { onStop() }
            .apply {
                visibility = View.GONE
                background = glassControl(activity)
            }
        footer.orientation = LinearLayout.VERTICAL
        footer.addView(stop, AppUi.params(activity, 8))
        options.setOnClickListener {
            val report = SettingsRow(activity, "Report this answer", showChevron = false, icon = R.drawable.ic_report) {
                menu.dismiss()
                onReport()
            }
            menu.dismiss()
            onOptions(true)
            menu.show(options, report, widthDp = 232, label = "Answer options", onDismiss = { onOptions(false) })
        }
    }.apply { setOnDismissListener { menu.dismiss(); onClose() } }

    fun setSpeaking(active: Boolean) {
        speaking = active
        stop.visibility = if (active && hasSpeech) View.VISIBLE else View.GONE
    }
    fun setSpeechAvailable(available: Boolean) {
        hasSpeech = available
        stop.visibility = if (speaking && available) View.VISIBLE else View.GONE
    }
    fun dismiss() = dialog.dismiss()

}

internal fun answerSourceButton(activity: Activity, source: AnswerSource, open: (String) -> Unit): Button =
    AppUi.button(activity, java.net.URI(source.url).host, AppUi.ButtonTone.QUIET) { open(source.url) }.apply {
        contentDescription = "Open source, ${source.title}"
        background = glassControl(activity)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(AppUi.dp(activity, 16), paddingTop, AppUi.dp(activity, 16), paddingBottom)
    }

internal fun glassControl(activity: Activity): RippleDrawable = RippleDrawable(
    ColorStateList.valueOf(0x28FFFFFF),
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x29FFFFFF, 0x10FFFFFF)).apply {
        cornerRadius = AppUi.dp(activity, 24).toFloat()
        setStroke(AppUi.dp(activity, 1), 0x40FFFFFF)
    }, null,
)
