package com.puneet.friday.ui

import android.app.Activity
import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.animation.PathInterpolator
import com.puneet.friday.R
import com.puneet.friday.voice.InformationAnswer
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
    val title = if (compact) AppUi.compactVoiceText(activity, "Talk to Friday").apply {
        fontVariationSettings = "'wght' 500"
    } else AppUi.text(activity, "Talk to Friday", 19f)
    val detail = if (compact) AppUi.compactVoiceText(activity, "Tap the microphone to start.")
        else AppUi.text(activity, "Tap the microphone to start.", 12f, secondary = true)
    val controlLabel = AppUi.text(activity, "Tap to speak", if (compact) 14f else 11f, secondary = true)
    val permission = AppUi.button(activity, "Manage permissions", AppUi.ButtonTone.SECONDARY, onClick = onPermissions)
    val cancel = AppUi.iconButton(activity, R.drawable.ic_x_close, "Close command", onCancel)
    val replay = AppUi.button(activity, "Speak again", AppUi.ButtonTone.QUIET, onClick = onReplay)
    private val statusScroll = ScrollView(activity)
    private val progressIndicator = if (compact) CompactProgressIndicator(activity) else null
    private var panelText: LinearLayout? = null
    private var answerView: CompactAnswerView? = null
    private var pillHeight = 0
    private var targetHeight = 0
    private var sizeTransition: ValueAnimator? = null
    private val clip = Rect()
    private var voiceState = AssistantOrbView.State.IDLE
    private var expandedProgress = false

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
        if (compact) buildPanel(activity) else buildHome(activity)
    }

    private fun buildPanel(activity: Activity) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(orb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        title.gravity = Gravity.START
        content.addView(title, AppUi.params(activity, 0))
        detail.visibility = View.GONE
        detail.gravity = Gravity.START
        content.addView(detail, AppUi.params(activity, 0))
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            alpha = 0f
            addView(requireNotNull(progressIndicator), LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginEnd = dp(10)
            })
            addView(content, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(row, panelParams())
        panelText = row
        orb.onWakePillReveal {
            row.alpha = it
            answerView?.alpha = it
        }
        updatePanel()
    }

    private fun panelParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
        topMargin = dp(2)
        bottomMargin = topMargin
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val label = answerView ?: panelText
        if (label == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        measureChildWithMargins(label, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val width = resolveSize(label.measuredWidth + (label.layoutParams as LayoutParams).let {
            it.leftMargin + it.rightMargin
        }, widthMeasureSpec)
        resizePill(label.measuredHeight)
        applyPillHeight(label)
        // Leave room for the listening dome while it morphs into the first progress pill.
        val minimum = if (answerView == null) dp(128) else 0
        val height = resolveSize(maxOf(minimum, maxOf(pillHeight, targetHeight) + 2 * dp(2)), heightMeasureSpec)
        orb.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    private fun resizePill(height: Int) {
        if (height == targetHeight) return
        sizeTransition?.cancel()
        targetHeight = height
        if (pillHeight == 0 || answerView == null || !isShown || !ValueAnimator.areAnimatorsEnabled()) {
            pillHeight = height
            return
        }
        sizeTransition = ValueAnimator.ofInt(pillHeight, height).apply {
            duration = 220
            interpolator = PathInterpolator(.2f, .8f, .2f, 1f)
            addUpdateListener {
                pillHeight = it.animatedValue as Int
                answerView?.let(::applyPillHeight)
                if (pillHeight == targetHeight) post { requestLayout() }
            }
            start()
        }
    }

    private fun applyPillHeight(label: View) {
        clip.set(0, 0, label.measuredWidth, pillHeight)
        label.clipBounds = clip
        orb.setWakeLabelHeight(pillHeight)
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
        voiceState = state
        if (state != AssistantOrbView.State.PROCESSING) expandedProgress = false
        orb.setState(state)
        updatePanel()
    }

    fun expandProgress() {
        if (voiceState != AssistantOrbView.State.PROCESSING || expandedProgress) return
        expandedProgress = true
        updatePanel()
    }

    fun setStatus(value: String) {
        title.text = value
        if (compact && orb.importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_YES) orb.contentDescription = value
    }

    fun showCompactAnswer(answer: InformationAnswer, onToggle: (Boolean) -> Unit,
                          onSource: (String) -> Unit, onReport: () -> Unit, onStop: () -> Unit) {
        clearCompactAnswer()
        val view = CompactAnswerView(context as Activity, answer, onToggle, onSource, onReport, onStop)
        view.alpha = panelText?.alpha ?: 1f
        answerView = view
        addView(view, panelParams())
        setVoiceState(AssistantOrbView.State.SUCCESS)
    }

    fun expandAnswerDetails(open: Boolean) { answerView?.setExpanded(open) }

    fun setAnswerSpeaking(active: Boolean) { answerView?.setSpeaking(active) }

    fun clearCompactAnswer() {
        val answer = answerView ?: return
        sizeTransition?.cancel()
        sizeTransition = null
        targetHeight = pillHeight
        answerView = null
        removeView(answer)
        updatePanel()
    }

    private fun updatePanel() {
        val text = panelText ?: return
        val shape = when (voiceState) {
            AssistantOrbView.State.IDLE, AssistantOrbView.State.PREVIEW, AssistantOrbView.State.LISTENING -> WakeOrbView.Shape.GLASS
            AssistantOrbView.State.PROCESSING ->
                if (expandedProgress) WakeOrbView.Shape.EXPANDED_PROGRESS else WakeOrbView.Shape.PROCESSING
            else -> WakeOrbView.Shape.STATUS
        }
        orb.setWakeShape(shape)
        val processing = voiceState == AssistantOrbView.State.PROCESSING
        val listening = shape == WakeOrbView.Shape.GLASS
        progressIndicator?.visibility = if (processing) View.VISIBLE else View.GONE
        text.visibility = when {
            answerView != null -> View.GONE
            listening -> View.INVISIBLE
            else -> View.VISIBLE
        }
        orb.importantForAccessibility = if (listening) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        orb.accessibilityLiveRegion = if (listening) ACCESSIBILITY_LIVE_REGION_POLITE else ACCESSIBILITY_LIVE_REGION_NONE
        orb.contentDescription = if (listening) title.text else null
        if (shape == WakeOrbView.Shape.STATUS && detail.visibility == View.VISIBLE) title.visibility = View.GONE
        val stageWidth = minOf(resources.displayMetrics.widthPixels - dp(24), dp(440))
        val side = if (listening) 0 else (stageWidth * (1f - shape.widthFraction) / 2f).toInt()
        text.minimumHeight = (stageWidth * .23f).toInt().coerceIn(dp(56), dp(90))
        val start = if (processing) dp(34) - side else dp(26)
        if (processing) text.setPaddingRelative(start, dp(11), dp(22), dp(11))
        else text.setPaddingRelative(start, dp(8), dp(22), dp(14))
        text.layoutParams = (text.layoutParams as LayoutParams).apply {
            marginStart = side
            marginEnd = side
        }
    }

    fun setAudioLevel(level: PcmProcessor.Level) {
        orb.setAudioSignal(level.rms, level.low, level.mid, level.high)
    }

    override fun onDetachedFromWindow() {
        clearToast()
        sizeTransition?.end()
        sizeTransition = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = AppUi.dp(context, value)
}
