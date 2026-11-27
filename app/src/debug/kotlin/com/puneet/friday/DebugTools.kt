package com.puneet.friday

import android.app.Activity
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.view.WindowManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import com.puneet.friday.debug.wake.WakeTrialActivity
import com.puneet.friday.debug.wake.WakeTrialService
import com.puneet.friday.debug.wake.WakeAssistantBridge
import com.puneet.friday.debug.wake.WakeUsageActivity
import com.puneet.friday.debug.wake.WakeSwitchActivity
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow
import com.puneet.friday.debug.wake.WakeUsagePolicy
import com.puneet.friday.debug.wake.WakeUsageStore
import com.puneet.friday.actions.DeviceLock

internal object DebugTools {
    fun stopListening(activity: Activity) { activity.stopService(Intent(activity, WakeTrialService::class.java)) }
    fun configureLockedWindow(activity: Activity) {
        if (!DeviceLock.isLocked(activity)) return
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    fun hideFromLockScreen(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    fun recoverAfterResult(activity: Activity, delayMs: Long) = WakeAssistantBridge.recoverAfterResult(activity, delayMs)
    fun onKeyguardLaunch(service: VoiceInteractionService) {
        try { service.showSession(Bundle.EMPTY, 0) } catch (_: RuntimeException) { Unit }
    }
    fun addStartOptions(activity: Activity, content: SettingsGroup): () -> Unit {
        val details = SettingsRow(activity, "Start Friday", icon = R.drawable.ic_microphone_01) {
            activity.startActivity(Intent(activity, WakeUsageActivity::class.java))
        }
        val toggle = Switch(activity).apply {
            contentDescription = "Hey Friday"
            showText = false
            gravity = Gravity.CENTER
            minHeight = AppUi.dp(activity, 56)
            setPadding(0, 0, AppUi.dp(activity, 12), 0)
        }
        val refresh = {
            val enabled = WakeUsageStore.mode(activity) == WakeUsagePolicy.Mode.HEY_FRIDAY
            details.setValue(if (enabled) "Hey Friday" else "Tap to speak")
            toggle.isChecked = enabled
        }
        refresh()
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked != (WakeUsageStore.mode(activity) == WakeUsagePolicy.Mode.HEY_FRIDAY)) {
                if (checked) {
                    activity.startActivity(Intent(activity, WakeSwitchActivity::class.java))
                } else if (!WakeUsageStore.useTapToSpeak(activity)) {
                    FridayDialog.show(activity, "Start Friday", "Couldn't save your choice. Try again.")
                }
                refresh()
            }
        }
        content.addRow(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(AppUi.dp(activity, 68), LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        return refresh
    }
    fun licenseAssets() = listOf("Hey Friday wake detector" to "wake-trial-licenses.txt")
    fun addSettings(activity: Activity, content: SettingsGroup) {
        content.addRow(SettingsRow(activity, "Try a phone action", icon = R.drawable.ic_phone_action) {
            activity.startActivity(Intent(activity, ActionDebugActivity::class.java))
        })
        content.addRow(SettingsRow(activity, "Wake detector test", icon = R.drawable.ic_waveform) {
            activity.startActivity(Intent(activity, WakeTrialActivity::class.java))
        })
    }

    fun onAssistantVisible(activity: Activity) =
        WakeTrialService.assistantVisible(activity, WakeAssistantBridge.hasPendingEntry(activity.intent))
    fun onAssistantHidden(activity: Activity) {
        WakeAssistantBridge.forgetPanel(activity)
        WakeTrialService.assistantHidden(activity)
    }
    fun onAssistantInteraction(activity: Activity): Boolean = WakeAssistantBridge.interacted(activity)
    fun cancelErrorRecovery(activity: Activity) = WakeAssistantBridge.cancelErrorRecovery(activity)
    fun recoverAfterError(activity: Activity): Boolean = WakeAssistantBridge.recoverAfterError(activity)
    fun onVoiceServiceReady(service: VoiceInteractionService) = WakeAssistantBridge.ready(service)
    fun onVoiceServiceStopped(service: VoiceInteractionService) = WakeAssistantBridge.stopped(service)
    fun prepareAssistantIntent(args: Bundle?, intent: Intent): Intent? = WakeAssistantBridge.prepare(args, intent)
    fun allowAutoListen(activity: Activity, intent: Intent): Boolean = WakeAssistantBridge.allowEntry(activity, intent)
    fun isWakeEntry(intent: Intent): Boolean = WakeAssistantBridge.hasPendingEntry(intent)
    fun playWakeCue() = WakeTrialService.playWakeCue()
    fun beginCommandCapture() = WakeTrialService.beginCommandCapture()
    fun endCommandCapture() = WakeTrialService.endCommandCapture()
    fun beginSpeechPlayback(speaker: Any) = WakeTrialService.beginSpeechPlayback(speaker)
    fun endSpeechPlayback(speaker: Any) = WakeTrialService.endSpeechPlayback(speaker)
    fun permissionSummary() = "Friday records commands after a tap or a wake in the opt-in Hey Friday test. Wake detection stays on this phone."
    fun microphoneDisclosure() = "Friday sends short recordings to its Vercel backend and NVIDIA or Groq for speech recognition. Groq chooses phone actions and answers questions. For current information, Groq sends search queries to Exa. Friday does not store command recordings or transcripts. Hey Friday runs on this phone. The optional wake audio tracker saves short wake clips in private storage on this phone."
}
