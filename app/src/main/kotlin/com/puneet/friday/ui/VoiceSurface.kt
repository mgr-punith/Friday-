package com.puneet.friday.ui

import android.app.Activity
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.puneet.friday.R
import com.puneet.friday.voice.PcmProcessor

/** One voice surface, shared by the launcher screen and contextual assistant panel. */
@SuppressLint("ViewConstructor") // Created with callbacks in Kotlin, never inflated from XML.
class VoiceSurface(
    activity: Activity,
    private val compact: Boolean,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onPermissions: () -> Unit,
    onReplay: () -> Unit,
    private val onSettings: () -> Unit = {},
) : FrameLayout(activity) {
    val orb = AssistantOrbView(activity, ribbon = compact).apply {
        if (!compact) {
            contentDescription = "Orb. Start listening"
            setOnClickListener { if (mic.isEnabled) mic.performClick() }
        }
    }
    val mic = MicButtonView(activity).apply {
        setMonochrome(compact)
        contentDescription = "Start listening"
        setOnClickListener { onMic() }
    }
    val title = AppUi.text(activity, "Talk to Friday", if (compact) 17f else 19f)
    val detail = AppUi.text(activity, "Tap the microphone to start.", if (compact) 14f else 12f, secondary = true)
    val controlLabel = AppUi.text(activity, "Tap to speak", if (compact) 14f else 11f, secondary = true)
    val permission = AppUi.button(activity, "Manage permissions", AppUi.ButtonTone.SECONDARY, onClick = onPermissions)
    val cancel = AppUi.iconButton(activity, R.drawable.ic_x_close, "Close command", onCancel)
    val replay = AppUi.button(activity, "Speak again", AppUi.ButtonTone.QUIET, onClick = onReplay)
    private val request = AppUi.text(activity, "", 12f, secondary = true)
    private val requestPanel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val statusScroll = ScrollView(activity)

    private val toast = if (compact) null else AppUi.text(activity, "", 14f).apply {
        gravity = Gravity.CENTER
        maxWidth = dp(360)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = AppUi.surface(activity, 18, R.color.settings_surface, R.color.settings_divider)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = View.GONE
    }
    private val dismissToast = Runnable { toast?.visibility = View.GONE }

    init {
        title.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        detail.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        permission.visibility = View.GONE
        replay.visibility = View.GONE
        requestPanel.visibility = View.GONE
        requestPanel.addView(AppUi.text(activity, "Understood", 11f, secondary = true), AppUi.params(activity, 4))
        requestPanel.addView(request, AppUi.params(activity, 0))
        if (compact) buildPanel(activity) else buildHome(activity)
    }

    private fun buildPanel(activity: Activity) {
        background = AppUi.surface(activity, 24, R.color.surface, R.color.settings_divider)
        clipToOutline = true
        elevation = dp(10).toFloat()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        content.addView(orb, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(72)))
        title.textSize = 13f
        title.gravity = Gravity.CENTER
        title.setTextColor(activity.getColor(R.color.text_secondary))
        content.addView(title, AppUi.params(activity, 0))

        detail.visibility = View.GONE
        detail.gravity = Gravity.CENTER
        content.addView(detail, AppUi.params(activity, 4))
        controlLabel.visibility = View.GONE
        requestPanel.setPadding(dp(12), dp(8), dp(12), dp(8))
        content.addView(requestPanel, AppUi.params(activity, 4))
        statusScroll.addView(content)
        addView(statusScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildHome(activity: Activity) {
        setBackgroundColor(activity.getColor(R.color.screen_background))
        mic.setLabel("Speak")
        title.visibility = View.INVISIBLE
        detail.visibility = View.GONE
        val landscape = resources.configuration.screenHeightDp < 480 &&
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            AppUi.pageHeader(activity, "Friday",
                trailing = AppUi.roundIconButton(activity, R.drawable.ic_settings_01, "Settings", onSettings)),
            AppUi.params(activity, 0),
        )

        val status = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val orbSize = if (landscape) 120 else minOf(320, (resources.configuration.screenWidthDp * 0.80f).toInt())
        title.gravity = Gravity.CENTER
        status.addView(title, AppUi.params(activity, 8))
        detail.gravity = Gravity.CENTER
        status.addView(detail, AppUi.params(activity, 12))
        status.addView(permission, AppUi.params(activity, 8))
        status.addView(replay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val controls = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        controls.addView(mic, LinearLayout.LayoutParams(dp(144), dp(56)))
        cancel.visibility = View.GONE
        cancel.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.control_primary_text))
        cancel.background = AppUi.surface(activity, 28, R.color.text_primary, null)
        controls.addView(cancel, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(8) })

        statusScroll.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val voiceStage = object : FrameLayout(activity) {
            override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
                super.onSizeChanged(width, height, oldWidth, oldHeight)
                // Reserve the text area below a fixed orb, so state changes cannot move it.
                val top = height / 2 + dp(orbSize) / 2 + dp(4)
                statusScroll.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (height - top).coerceAtLeast(1)).apply {
                    topMargin = top
                }
            }
        }.apply {
            addView(orb, LayoutParams(dp(orbSize), dp(orbSize)).apply { gravity = Gravity.CENTER })
            addView(statusScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(96)).apply { gravity = Gravity.BOTTOM })
        }
        if (landscape) {
            val stage = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            stage.addView(voiceStage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            stage.addView(controls, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            column.addView(voiceStage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            column.addView(controls, AppUi.params(activity, 0))
        }
        val page = FrameLayout(activity).apply {
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            toast?.let {
                addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = dp(76)
                    marginStart = dp(16)
                    marginEnd = dp(16)
                })
            }
        }
        addView(page, LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(560)),
            LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER })
    }

    fun showToast(message: String) {
        val banner = toast ?: return
        removeCallbacks(dismissToast)
        banner.text = message
        banner.visibility = View.VISIBLE
        postDelayed(dismissToast, 2_800)
    }

    fun clearToast() {
        removeCallbacks(dismissToast)
        toast?.visibility = View.GONE
    }

    fun setVoiceState(state: AssistantOrbView.State) {
        orb.setState(state)
    }

    fun setStatus(value: String) {
        title.text = value
    }

    fun setAudioLevel(level: PcmProcessor.Level) {
        orb.setAudioSignal(level.rms, level.low, level.mid, level.high)
    }

    override fun onDetachedFromWindow() {
        clearToast()
        super.onDetachedFromWindow()
    }

    fun showRequest(value: String?) {
        if (!compact) return
        request.text = value.orEmpty()
        requestPanel.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
        statusScroll.post {
            statusScroll.fullScroll(View.FOCUS_UP)
        }
    }

    private fun dp(value: Int): Int = AppUi.dp(context, value)
}
