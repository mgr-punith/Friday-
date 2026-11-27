package com.puneet.friday.debug.wake

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.puneet.friday.R
import com.puneet.friday.auth.AccountStore
import com.puneet.friday.auth.SignInActivity
import com.puneet.friday.entry.AssistantSettings
import com.puneet.friday.entry.ReadyNotification
import com.puneet.friday.debug.wake.WakeUsagePolicy.AssistantSetup
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.AnchoredPopup
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow

/** Entry choice for the existing opt-in trial; selecting a row never starts audio. */
open class WakeUsageActivity : Activity() {
    protected open val fromModeSwitch = false
    private var applySwitchOnResume = false
    private var selected = WakeUsagePolicy.Mode.TAP
    private val options = mutableMapOf<WakeUsagePolicy.Mode, RadioButton>()
    private lateinit var apply: Button
    private lateinit var status: TextView
    private lateinit var assistantFallback: Button
    private lateinit var backgroundSettings: SettingsGroup
    private lateinit var sensitivity: SettingsRow
    private lateinit var audioTrace: SettingsRow
    private val help by lazy { AnchoredPopup(this) }
    private val continuation = WakeUsagePolicy.Continuation()
    private var postResumed = false
    private var connectionDeadline = 0L
    private var connectionTimedOut = false
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
        override fun run() {
            if (!postResumed || isFinishing) return
            if (connectionDeadline != 0L) checkConnection()
            if (!isFinishing && postResumed) {
                render()
                main.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySwitchOnResume = fromModeSwitch && savedInstanceState == null
        selected = if (applySwitchOnResume) WakeUsagePolicy.Mode.HEY_FRIDAY else
            WakeUsagePolicy.Mode.from(savedInstanceState?.getString("selection") ?: WakeUsageStore.mode(this).value)
        continuation.pending = savedInstanceState?.getBoolean("resume_setup") ?: false
        connectionDeadline = savedInstanceState?.getLong("connection_deadline") ?: 0L
        connectionTimedOut = savedInstanceState?.getBoolean("connection_timeout") ?: false
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
                connectionDeadline = 0L
                connectionTimedOut = false
                continuation.pending = false
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
            if (!WakeUsageStore.useTapToSpeak(this)) { showError("Couldn't save your choice. Try again."); return }
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
                positive = "Agree and allow", negative = "Not now",
            ) { WakeUsageStore.consent(this); checkPermissions() }
        } else checkPermissions()
    }

    private fun missingPermissions() = buildList {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationsAllowed(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(WakeTrialService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun checkPermissions() {
        if (selected != WakeUsagePolicy.Mode.HEY_FRIDAY) return
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSIONS)
            return
        }
        if (!notificationsAllowed()) {
            offerAccessSettings()
            return
        }
        assistantSelected = AssistantSettings.isSelected(this)
        when (assistantSetup()) {
            AssistantSetup.SELECT -> openAssistantSettings()
            AssistantSetup.WAIT -> {
                connectionDeadline = SystemClock.elapsedRealtime() + CONNECTION_TIMEOUT_MS
                render()
            }
            AssistantSetup.RECONNECT -> FridayDialog.show(this, "Reconnect Friday",
                "In Android’s Digital assistant settings, select None, then select Friday again.",
                positive = "Open settings", negative = "Cancel") { openAssistantSettings() }
            AssistantSetup.READY -> startHeyFriday()
        }
    }

    private fun assistantSetup() = WakeUsagePolicy.assistantSetup(assistantSelected, WakeAssistantBridge.isReady(this), connectionTimedOut)

    private fun openAssistantSettings(from: AssistantSettings.Destination = AssistantSettings.Destination.ASSISTANT) {
        connectionTimedOut = false
        if (AssistantSettings.open(this, from, ASSISTANT_SETTINGS) == null) {
            showAssistantFallback = true
            showError("Open Android Settings → Default apps → Digital assistant and choose Friday.")
        }
    }

    private fun offerAccessSettings() {
        val needsMic = Manifest.permission.RECORD_AUDIO in missingPermissions()
        val detail = if (needsMic) "Allow microphone access under Permissions to use Hey Friday."
            else "Allow Hey Friday notifications so you can pause listening."
        FridayDialog.show(this, if (needsMic) "Microphone access" else "Notification access", detail,
            positive = "Open settings", negative = "Not now") {
            val manager = getSystemService(NotificationManager::class.java)
            val settings = when {
                needsMic -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                manager?.areNotificationsEnabled() == true &&
                    manager.getNotificationChannel(WakeTrialService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE ->
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, WakeTrialService.CHANNEL_ID)
                else -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            try {
                startActivityForResult(settings, ACCESS_SETTINGS)
            } catch (_: RuntimeException) {
                showError("Open Friday in Android’s app settings. $detail")
            }
        }
    }

    private fun startHeyFriday() {
        if (!AccountStore.isSignedIn(this)) {
            startActivityForResult(Intent(this, SignInActivity::class.java), SIGN_IN)
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
    }
    override fun onPostResume() {
        super.onPostResume()
        postResumed = true
        if (applySwitchOnResume) {
            applySwitchOnResume = false
            useSelection()
        }
        continueSetupIfResumed()
        main.post(refresh)
    }

    private fun continueSetupIfResumed() {
        if (continuation.takeIfResumed(postResumed && !isFinishing)) {
            checkPermissions()
        }
    }

    private fun checkConnection() {
        assistantSelected = AssistantSettings.isSelected(this)
        val ready = WakeAssistantBridge.isReady(this)
        if (!ready && assistantSelected && SystemClock.elapsedRealtime() < connectionDeadline) return
        connectionDeadline = 0L
        connectionTimedOut = assistantSelected && !ready
        if (ready) {
            checkPermissions()
        } else if (!assistantSelected) {
            showError("Choose Friday under Default assistant to use Hey Friday.")
        }
    }

    override fun onPause() {
        postResumed = false
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
        outState.putBoolean("resume_setup", continuation.pending)
        outState.putLong("connection_deadline", connectionDeadline)
        outState.putBoolean("connection_timeout", connectionTimedOut)
        outState.putBoolean("assistant_fallback", showAssistantFallback)
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != PERMISSIONS) return
        if (results.isEmpty()) return
        if (missingPermissions().isNotEmpty()) {
            offerAccessSettings()
            return
        }
        continuation.pending = true
        continueSetupIfResumed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ASSISTANT_SETTINGS -> if (!AssistantSettings.isSelected(this)) {
                showAssistantFallback = true
                showError("Choose Friday under Default assistant to use Hey Friday.")
                return
            }
            SIGN_IN -> if (!AccountStore.isSignedIn(this)) {
                showError("Sign in to use Hey Friday.")
                return
            }
            ACCESS_SETTINGS -> if (missingPermissions().isNotEmpty() || !notificationsAllowed()) {
                showError("Allow microphone and notification access to use Hey Friday.")
                return
            }
            else -> return
        }
        continuation.pending = true
        continueSetupIfResumed()
    }

    private fun render() {
        val state = WakeTrialService.session.snapshot()
        val higherSensitivity = WakeUsageStore.higherSensitivity(this)
        val tracking = WakeAudioTrace.isActive()
        val waitingForAssistant = connectionDeadline != 0L
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
            waitingForAssistant -> "Waiting for Android to connect to Friday…"
            assistantSetup == AssistantSetup.RECONNECT -> "Android hasn’t connected to Friday. Tap Reconnect Friday."
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
            waitingForAssistant -> "Connecting…"
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

    private companion object {
        const val PERMISSIONS = 763
        const val ACCESS_SETTINGS = 764
        const val SIGN_IN = 765
        const val ASSISTANT_SETTINGS = 766
        const val CONNECTION_TIMEOUT_MS = 5_000L
    }
}

/** Only the in-app switch may apply a choice as soon as this screen resumes. */
class WakeSwitchActivity : WakeUsageActivity() {
    override val fromModeSwitch = true
}
