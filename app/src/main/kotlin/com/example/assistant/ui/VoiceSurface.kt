package com.example.assistant.ui

import android.app.Activity
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.assistant.R

/** One voice surface, shared by the launcher screen and contextual assistant panel. */
@SuppressLint("ViewConstructor") // Created with callbacks in Kotlin, never inflated from XML.
class VoiceSurface(
    activity: Activity,
    compact: Boolean,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onPermissions: () -> Unit,
    onClose: () -> Unit,
    onReplay: () -> Unit,
) : FrameLayout(activity) {
    val orb = AssistantOrbView(activity)
    val mic = MicButtonView(activity).apply {
        contentDescription = "Start listening"
        setOnClickListener { onMic() }
    }
    val title = AppUi.text(activity, "Talk to Friday", if (compact) 17f else 19f)
    val detail = AppUi.text(activity, "Tap the microphone to start.", 12f, secondary = true)
    val controlLabel = AppUi.text(activity, "Tap to speak", 11f, secondary = true)
    val permission = AppUi.button(activity, "Manage permissions", AppUi.ButtonTone.SECONDARY, onPermissions)
    val cancel = AppUi.button(activity, "Cancel", AppUi.ButtonTone.QUIET, onCancel)
    val replay = AppUi.button(activity, "Speak again", AppUi.ButtonTone.QUIET, onReplay)
    private val request = AppUi.text(activity, "", 12f, secondary = true)
    private val requestPanel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val statusScroll = ScrollView(activity)

    init {
        background = AppUi.surface(activity, if (compact) 28 else 0, R.color.screen_background, null).apply {
            if (compact) cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
        }
        clipToOutline = compact
        val landscape = resources.configuration.screenHeightDp < 480 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(8), 0)
        }
        header.addView(AppUi.text(activity, "Friday", 15f, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (compact) {
            header.addView(AppUi.iconButton(activity, R.drawable.ic_x_close, "Close assistant", onClose),
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(if (compact || landscape) 0 else 8), dp(16), dp(if (compact || landscape) 8 else 16))
        }
        val orbSize = if (landscape) 96 else if (compact) 120 else 168
        body.addView(orb, LinearLayout.LayoutParams(dp(orbSize), dp(orbSize)))
        title.gravity = Gravity.CENTER
        title.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(title, AppUi.params(activity, 8))
        detail.gravity = Gravity.CENTER
        detail.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.addView(detail, AppUi.params(activity, if (landscape) 8 else 16))
        requestPanel.apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = AppUi.surface(activity, 16, R.color.surface_muted, null)
            addView(AppUi.text(activity, "Understood", 11f, secondary = true), AppUi.params(activity, 6))
            addView(request, AppUi.params(activity, 0))
            visibility = View.GONE
        }
        body.addView(requestPanel, AppUi.params(activity, 16))
        permission.visibility = View.GONE
        body.addView(permission, AppUi.params(activity, 16))
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        controls.addView(mic, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        controlLabel.gravity = Gravity.CENTER
        controls.addView(controlLabel, AppUi.params(activity, 0).apply { topMargin = dp(8) })
        val secondaryControls = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        cancel.visibility = if (landscape) View.GONE else View.INVISIBLE
        replay.visibility = View.GONE
        secondaryControls.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        secondaryControls.addView(replay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        controls.addView(secondaryControls, AppUi.params(activity, 0).apply { topMargin = dp(4) })

        statusScroll.apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (landscape) {
            val stage = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            stage.addView(statusScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            stage.addView(controls, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            column.addView(statusScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            column.addView(controls, AppUi.params(activity, 0))
        }
        addView(column, LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(560)),
            ViewGroup.LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER })
    }

    fun showRequest(value: String?) {
        request.text = value.orEmpty()
        requestPanel.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
        statusScroll.post {
            statusScroll.fullScroll(if (value.isNullOrBlank()) View.FOCUS_UP else View.FOCUS_DOWN)
        }
    }

    private fun dp(value: Int): Int = AppUi.dp(context, value)
}
