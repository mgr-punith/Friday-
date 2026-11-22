package com.puneet.friday

import android.app.Activity
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.view.WindowManager
import android.widget.LinearLayout
import com.puneet.friday.debug.wake.WakeTrialActivity
import com.puneet.friday.debug.wake.WakeTrialService
import com.puneet.friday.debug.wake.WakeAssistantBridge
import com.puneet.friday.debug.wake.WakeUsageActivity
import com.puneet.friday.ui.AppUi
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
    fun addStartOptions(activity: Activity, content: LinearLayout) {
        content.addView(AppUi.navigationButton(activity, "Start Friday", "") {
            activity.startActivity(Intent(activity, WakeUsageActivity::class.java))
        }.apply {
            text = "Start Friday"
            contentDescription = "Start Friday"
            minHeight = AppUi.dp(activity, 56)
        }, AppUi.params(activity, 16))
    }
    fun addSettings(activity: Activity, content: LinearLayout) {
        content.addView(AppUi.navigationButton(activity, "Wake detector test", "Hey Friday · Command trial") {
            activity.startActivity(Intent(activity, WakeTrialActivity::class.java))
        }, AppUi.params(activity, 16))
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
    fun beginCommandCapture() = WakeTrialService.beginCommandCapture()
    fun endCommandCapture() = WakeTrialService.endCommandCapture()
    fun beginSpeechPlayback(speaker: Any) = WakeTrialService.beginSpeechPlayback(speaker)
    fun endSpeechPlayback(speaker: Any) = WakeTrialService.endSpeechPlayback(speaker)
    fun listeningSummary() = ""
    fun permissionSummary() = "Friday records commands after a tap or a wake in the opt-in Hey Friday test. Wake detection stays on this phone."
    fun microphoneDisclosure() = "Friday sends short command recordings to its Vercel backend and Google Gemini to choose a phone action. Friday does not store command audio. In the opt-in Hey Friday test, wake detection runs on this phone without saving or uploading ambient audio. Stop test turns wake listening off."
}
