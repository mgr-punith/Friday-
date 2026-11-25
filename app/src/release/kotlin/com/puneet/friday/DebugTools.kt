package com.puneet.friday

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow

/** Release has no wake trial, native runtime, microphone service or developer entry point. */
internal object DebugTools {
    fun stopListening(activity: Activity) = Unit
    fun configureLockedWindow(activity: Activity) = Unit
    fun hideFromLockScreen(activity: Activity) = Unit
    fun recoverAfterResult(activity: Activity, delayMs: Long) = false
    fun onKeyguardLaunch(service: VoiceInteractionService) = Unit
    fun addStartOptions(activity: Activity, content: SettingsGroup): SettingsRow? = null
    fun startMode(activity: Activity) = "Tap to speak"
    fun licenseAssets(): List<Pair<String, String>> = emptyList()
    fun addSettings(activity: Activity, content: SettingsGroup) = Unit
    fun onAssistantVisible(activity: Activity) = Unit
    fun onAssistantHidden(activity: Activity) = Unit
    fun onAssistantInteraction(activity: Activity) = false
    fun cancelErrorRecovery(activity: Activity) = Unit
    fun recoverAfterError(activity: Activity) = false
    fun onVoiceServiceReady(service: VoiceInteractionService) = Unit
    fun onVoiceServiceStopped(service: VoiceInteractionService) = Unit
    fun prepareAssistantIntent(args: Bundle?, intent: Intent): Intent? = intent
    fun allowAutoListen(activity: Activity, intent: Intent) = true
    fun isWakeEntry(intent: Intent) = false
    fun playWakeCue() = Unit
    fun beginCommandCapture() = Unit
    fun endCommandCapture() = Unit
    fun beginSpeechPlayback(speaker: Any) = Unit
    fun endSpeechPlayback(speaker: Any) = Unit
    fun permissionSummary() = "Friday records after you tap the microphone. Background listening is off."
    fun microphoneDisclosure() = "Friday records a short request after you tap the microphone. It sends that audio to its Vercel backend and Groq to understand phone commands and answer questions. For current information, Groq sends search queries to Exa. Friday does not store audio or transcripts. Background listening is off."
}
