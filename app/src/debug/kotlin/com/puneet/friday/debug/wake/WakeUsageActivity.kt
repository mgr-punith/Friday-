package com.puneet.friday.debug.wake

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.puneet.friday.R
import com.puneet.friday.entry.AssistantSettings
import com.puneet.friday.entry.ReadyNotification
import com.puneet.friday.debug.wake.WakeUsagePolicy.AssistantSetup
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.AnchoredPopup
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow

/** Entry choice for the existing opt-in trial; selecting a row never starts audio. */
class WakeUsageActivity : Activity() {
    private var selected = WakeUsagePolicy.Mode.TAP
    private val options = mutableMapOf<WakeUsagePolicy.Mode, RadioButton>()
    private lateinit var apply: Button
    private lateinit var status: TextView
    private lateinit var assistantFallback: Button
    private lateinit var backgroundSettings: SettingsGroup
    private lateinit var sensitivity: SettingsRow
    private lateinit var audioTrace: SettingsRow
    private val help by lazy { AnchoredPopup(this) }
    private var pendingPermission = false
    private var pendingAssistantSettings = false
    private var waitingForAssistant = false
    private var assistantSelected = false
    private var showAssistantFallback = false
    private var message: String? = null
    private data class RenderState(val mode: WakeUsagePolicy.Mode, val phase: WakeTrialSession.Phase,
        val message: String?,
        val assistantSetup: AssistantSetup, val waitingForAssistant: Boolean,
        val assistantFallback: Boolean, val higherSensitivity: Boolean,
        val tracking: Boolean)
    private val modelAvailable by lazy { FridayKeywordModel.available(assets) }
    private var rendered: RenderState? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = WakeUsagePolicy.Mode.from(savedInstanceState?.getString("selection") ?: WakeUsageStore.mode(this).value)
        pendingPermission = savedInstanceState?.getBoolean("permission") ?: false
        pendingAssistantSettings = savedInstanceState?.getBoolean("assistant_settings") ?: false
        showAssistantFallback = savedInstanceState?.getBoolean("assistant_fallback") ?: false
        assistantSelected = AssistantSettings.isSelected(this)
        val (scroll, content) = AppUi.groupedContent(this, "Start Friday")
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(this, 60), 1f))
        val choices = SettingsGroup(this)
        addOption(choices, WakeUsagePolicy.Mode.HEY_FRIDAY, "Hey Friday",
            "Friday listens for “Hey Friday” on this phone. Pause it from the notification.")
        addOption(choices, WakeUsagePolicy.Mode.TAP, "Tap to speak",
            "Open Friday and tap the microphone to give a command.")
        content.addView(choices, AppUi.params(this, 16))
        status = AppUi.text(this, "", 12f, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status, AppUi.params(this, 16))
        apply = AppUi.button(this, "Use Tap to speak", AppUi.ButtonTone.PRIMARY) { useSelection() }
        content.addView(apply, AppUi.params(this, 16))
        assistantFallback = AppUi.button(this, "Open Default apps") {
            openAssistantSettings(AssistantSettings.Destination.DEFAULT_APPS)
        }
        content.addView(assistantFallback, AppUi.params(this, 8))
        sensitivity = SettingsRow(this, "Wake sensitivity") { chooseSensitivity() }
        audioTrace = SettingsRow(this, "Wake audio tracker") { showAudioTraceOptions() }
        backgroundSettings = SettingsGroup(this).apply {
            addRow(sensitivity)
            addRow(audioTrace)
            addRow(SettingsRow(this@WakeUsageActivity, "Background listening") {
                startActivity(Intent(this@WakeUsageActivity, WakeBackgroundActivity::class.java))
            })
        }
        content.addView(backgroundSettings, AppUi.params(this, 24))
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        render()
    }

    private fun addOption(content: SettingsGroup, mode: WakeUsagePolicy.Mode, label: String, explanation: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = AppUi.dp(context, 68)
            setPadding(AppUi.dp(context, 8), AppUi.dp(context, 4), AppUi.dp(context, 4), AppUi.dp(context, 4))
        }
        val radio = RadioButton(this).apply {
            text = label
            textSize = 14f
            typeface = resources.getFont(R.font.geist)
            setTextColor(getColor(R.color.text_primary))
            buttonTintList = ColorStateList.valueOf(getColor(R.color.accent))
            minHeight = AppUi.dp(context, 48)
            setPadding(AppUi.dp(context, 4), AppUi.dp(context, 8), AppUi.dp(context, 8), AppUi.dp(context, 8))
            setOnClickListener {
                selected = mode
                waitingForAssistant = false
                message = null
                render()
            }
        }
        row.addView(radio, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = AppUi.dp(this@WakeUsageActivity, 8)
        })
        val info = AppUi.iconButton(this, R.drawable.ic_info_circle, "About $label") {}
        info.setOnClickListener { help.showHelp(info, explanation) }
        row.addView(info, LinearLayout.LayoutParams(AppUi.dp(this, 48), AppUi.dp(this, 48)))
        options[mode] = radio
        content.addRow(row)
    }

    private fun useSelection() {
        message = null
        if (selected == WakeUsagePolicy.Mode.TAP) {
            if (!WakeUsageStore.select(this, selected)) { showError("Couldn't save your choice. Try again."); return }
            WakeTrialService.stopForAssistant()
            WakeUsageNotification.cancel(this)
            finish()
            return
        }
        if (!modelAvailable) return
        val state = WakeTrialService.session.snapshot()
        if (state.active && !WakeUsagePolicy.canAdopt(state, WakeUsageStore.threshold(this))) {
            showError("Stop the wake detector test, then try again.")
            return
        }
        if (!WakeUsageStore.consented(this)) {
            FridayDialog.show(this, "Use Hey Friday?",
                "Friday checks for “Hey Friday” on this phone. After a wake, it sends your command audio to its Vercel backend and NVIDIA or Groq for speech recognition. Groq chooses the phone action. Friday does not save command recordings or transcripts.",
                positive = "Allow Hey Friday", negative = "Not now",
            ) { WakeUsageStore.consent(this); checkPermissions() }
        } else checkPermissions()
    }

    private fun checkPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS)
        else checkAssistant()
    }

    private fun checkAssistant() {
        if (selected != WakeUsagePolicy.Mode.HEY_FRIDAY) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.areNotificationsEnabled() != true ||
            manager.getNotificationChannel(WakeTrialService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            showError("Allow Friday notifications in Android Settings, then try again.")
            return
        }
        assistantSelected = AssistantSettings.isSelected(this)
        when (assistantSetup()) {
            AssistantSetup.SELECT -> openAssistantSettings()
            AssistantSetup.RECONNECT -> FridayDialog.show(this, "Reconnect Friday",
                "In Android’s Digital assistant settings, select None, then select Friday again.",
                positive = "Open settings", negative = "Cancel") { openAssistantSettings() }
            AssistantSetup.READY -> startHeyFriday()
        }
    }

    private fun assistantSetup() = WakeUsagePolicy.assistantSetup(assistantSelected, WakeAssistantBridge.isReady(this))

    private fun openAssistantSettings(from: AssistantSettings.Destination = AssistantSettings.Destination.ASSISTANT) {
        pendingAssistantSettings = true
        if (AssistantSettings.open(this, from) == null) {
            pendingAssistantSettings = false
            showAssistantFallback = true
            showError("Open Android Settings → Default apps → Digital assistant and choose Friday.")
        }
    }

    private fun startHeyFriday() {
        if (!com.puneet.friday.auth.AccountStore.isSignedIn(this)) {
            startActivity(Intent(this, com.puneet.friday.auth.SignInActivity::class.java))
            showError("Sign in before starting Hey Friday.")
            return
        }
        if (!WakeUsageStore.select(this, WakeUsagePolicy.Mode.HEY_FRIDAY)) {
            showError("Couldn't save your choice. Try again.")
            return
        }
        try {
            startForegroundService(Intent(this, WakeTrialService::class.java).setAction(WakeTrialService.START)
                .putExtra(WakeTrialService.USAGE_REVISION, WakeUsageStore.revision(this))
                .putExtra(WakeTrialService.COMMAND_MODE, true))
            ReadyNotification.setEnabled(this, false)
            finish()
        } catch (_: RuntimeException) {
            showError("Couldn't start the microphone. Try again.")
        }
    }

    private fun showError(value: String) { message = value; render() }

    private fun chooseSensitivity() {
        if (WakeTrialService.session.snapshot().active) {
            FridayDialog.show(this, "Wake sensitivity", "Pause Hey Friday from its notification to change sensitivity.")
            return
        }
        FridayDialog.choices(
            this, "Wake sensitivity", listOf("Standard", "Higher · May wake during other speech"),
        ) { choice ->
            WakeUsageStore.setHigherSensitivity(this, choice == 1)
            render()
        }
    }

    private fun showAudioTraceOptions() {
        val tracking = WakeAudioTrace.isActive()
        val count = WakeAudioTrace.count(this)
        val action = if (tracking) "Stop tracking" else "Start tracking"
        val choices = if (count == 0) listOf(action) else listOf(action, "Delete recorded clips ($count)")
        FridayDialog.choices(this, "Wake audio tracker", choices) { selection ->
            when {
                selection == 1 -> WakeAudioTrace.clear(this)
                tracking -> WakeAudioTrace.stop()
                else -> FridayDialog.show(
                    this, "Collect wake clips?",
                    "For 15 minutes, Friday can save up to two seconds of audio before each wake. It keeps the last 16 clips in private storage on this phone. You can delete them here. USB or wireless debugging can retrieve them.",
                    positive = "Start tracking", negative = "Cancel",
                ) { WakeAudioTrace.start(); render() }
            }
            render()
        }
    }

    override fun onStart() { super.onStart(); WakeTrialService.assistantVisible(this) }
    override fun onResume() {
        super.onResume()
        assistantSelected = AssistantSettings.isSelected(this)
        if (assistantSelected) showAssistantFallback = false
        main.post(refresh)
    }
    override fun onPostResume() {
        super.onPostResume()
        if (pendingPermission) { pendingPermission = false; checkAssistant() }
        else if (pendingAssistantSettings) {
            pendingAssistantSettings = false
            if (selected != WakeUsagePolicy.Mode.HEY_FRIDAY) return
            assistantSelected = AssistantSettings.isSelected(this)
            if (assistantSelected) resumeWhenAssistantConnects()
            else {
                showAssistantFallback = true
                showError("Friday isn't selected. Choose Friday under Default assistant.")
            }
        }
    }

    private fun resumeWhenAssistantConnects() {
        waitingForAssistant = true
        val deadline = android.os.SystemClock.elapsedRealtime() + 1_500L
        fun checkReady() {
            if (!waitingForAssistant || selected != WakeUsagePolicy.Mode.HEY_FRIDAY) return
            when {
                WakeAssistantBridge.isReady(this) -> {
                    waitingForAssistant = false
                    checkPermissions()
                }
                android.os.SystemClock.elapsedRealtime() < deadline -> main.postDelayed({ checkReady() }, 100L)
                else -> {
                    waitingForAssistant = false
                    showError("Select None, then Friday in Android’s Digital assistant settings.")
                }
            }
        }
        checkReady()
    }

    override fun onPause() {
        waitingForAssistant = false
        help.dismiss()
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }
    override fun onStop() { WakeTrialService.assistantHidden(this); super.onStop() }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selection", selected.value)
        outState.putBoolean("permission", pendingPermission)
        outState.putBoolean("assistant_settings", pendingAssistantSettings)
        outState.putBoolean("assistant_fallback", showAssistantFallback)
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != PERMISSIONS) return
        pendingPermission = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        if (!pendingPermission) showError("Allow microphone and notifications in Android Settings, then try again.")
    }

    private fun render() {
        val state = WakeTrialService.session.snapshot()
        val higherSensitivity = WakeUsageStore.higherSensitivity(this)
        val tracking = WakeAudioTrace.isActive()
        val assistantSetup = assistantSetup()
        val key = RenderState(selected, state.phase, message, assistantSetup, waitingForAssistant, showAssistantFallback,
            higherSensitivity, tracking)
        if (key == rendered) return
        rendered = key
        for ((mode, radio) in options) radio.isChecked = selected == mode
        val hey = selected == WakeUsagePolicy.Mode.HEY_FRIDAY
        val value = message ?: when {
            !hey -> ""
            !modelAvailable -> FridayKeywordModel.MISSING
            waitingForAssistant -> "Connecting to Android’s assistant"
            assistantSetup == AssistantSetup.RECONNECT -> "Reconnect Friday to use Hey Friday."
            WakeUsageStore.mode(this) == selected -> WakeUsagePolicy.status(state)
            else -> ""
        }
        if (status.text.toString() != value) status.text = value
        status.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        backgroundSettings.visibility = if (hey && modelAvailable) View.VISIBLE else View.GONE
        sensitivity.setValue(if (higherSensitivity) "Higher" else "Standard")
        audioTrace.setValue(if (tracking) "On" else "Off")
        assistantFallback.visibility = if (hey && modelAvailable && !assistantSelected && showAssistantFallback)
            View.VISIBLE else View.GONE
        apply.text = when {
            selected == WakeUsagePolicy.Mode.TAP -> "Use Tap to speak"
            state.phase == WakeTrialSession.Phase.STOPPING -> "Stopping…"
            modelAvailable && assistantSetup == AssistantSetup.SELECT -> "Set default assistant"
            modelAvailable && assistantSetup == AssistantSetup.RECONNECT -> "Reconnect Friday"
            WakeUsageStore.mode(this) == selected && WakeUsagePolicy.canAdopt(state, WakeUsageStore.threshold(this)) -> "Use Hey Friday"
            WakeUsageStore.mode(this) == selected -> "Resume Hey Friday"
            else -> "Use Hey Friday"
        }
        apply.isEnabled = state.phase != WakeTrialSession.Phase.STOPPING &&
            (!hey || modelAvailable && !waitingForAssistant)
        apply.alpha = if (apply.isEnabled) 1f else 0.5f
    }

    private companion object { const val PERMISSIONS = 763 }
}
