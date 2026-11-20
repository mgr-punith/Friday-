package com.example.assistant.debug.wake

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.util.Log
import com.example.assistant.AssistantActivity
import com.example.assistant.actions.DeviceLock
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Uses the selected system assistant; never launches a background Activity directly. */
internal object WakeAssistantBridge {
    private const val TOKEN = "friday_debug_wake_token"
    private const val ERROR_DISPLAY_MS = 3_000L
    private var assistant: WeakReference<VoiceInteractionService>? = null
    private val main = Handler(Looper.getMainLooper())
    private val panels = WeakHashMap<Activity, ErrorPanel>()

    private class ErrorPanel(run: Long, val openedLocked: Boolean) {
        val recovery = WakeErrorRecovery(run)
        var dismiss: Runnable? = null
    }

    fun ready(service: VoiceInteractionService) { assistant = WeakReference(service) }

    fun stopped(service: VoiceInteractionService) {
        if (assistant?.get() === service) assistant = null
    }

    fun request(context: Context, token: String, testOutcome: String? = null): Boolean {
        val service = assistant?.get() ?: return false
        return try {
            if (!VoiceInteractionService.isActiveService(context, ComponentName(context, service.javaClass))) return false
            service.showSession(Bundle().apply {
                putString(TOKEN, token)
                if (testOutcome in TEST_OUTCOMES) putString("debug_ui_state", testOutcome)
            }, 0)
            true
        } catch (_: RuntimeException) { false }
    }

    fun tag(intent: Intent, token: String): Intent = intent.putExtra(TOKEN, token)

    fun hasPendingEntry(intent: Intent): Boolean =
        intent.getStringExtra(TOKEN)?.let(WakeTrialService.session::isPendingWake) == true

    fun prepare(args: Bundle?, intent: Intent): Intent? {
        val token = args?.getString(TOKEN) ?: return intent
        if (!WakeTrialService.session.isPendingWake(token)) return null
        val outcome = args.getString("debug_ui_state")
        return tag(intent, token).apply {
            if (outcome in TEST_OUTCOMES) putExtra("debug_ui_state", outcome)
        }
    }

    fun allowEntry(activity: Activity, intent: Intent): Boolean {
        forgetPanel(activity)
        val token = intent.getStringExtra(TOKEN) ?: return true
        intent.removeExtra(TOKEN)
        val accepted = WakeTrialService.session.consumeWake(token)
        if (accepted && activity is AssistantActivity) {
            panels[activity] = ErrorPanel(WakeTrialService.session.snapshot().generation, DeviceLock.isLocked(activity))
        }
        return accepted
    }

    fun recoverAfterError(activity: Activity): Boolean = scheduleDismissal(activity, ERROR_DISPLAY_MS)

    fun recoverAfterResult(activity: Activity, delayMs: Long): Boolean {
        if (panels[activity]?.openedLocked != true) return false
        return scheduleDismissal(activity, delayMs)
    }

    private fun scheduleDismissal(activity: Activity, delayMs: Long): Boolean {
        cancelErrorRecovery(activity)
        val panel = panels[activity] ?: return false
        val ticket = panel.recovery.schedule(WakeTrialService.session.snapshot()) ?: return false
        val reference = WeakReference(activity)
        val dismiss = Runnable {
            panel.dismiss = null
            val current = reference.get() ?: return@Runnable
            if (panels[current] !== panel || current.isFinishing || current.isDestroyed) return@Runnable
            if (panel.recovery.consume(ticket, WakeTrialService.session.snapshot())) {
                panels.remove(current)
                Log.i("FridayWakeTrial", "event=wake_panel_dismissed")
                current.finish()
            }
        }
        panel.dismiss = dismiss
        main.postDelayed(dismiss, delayMs)
        return true
    }

    fun cancelErrorRecovery(activity: Activity) {
        val panel = panels[activity] ?: return
        panel.recovery.cancel()
        panel.dismiss?.let(main::removeCallbacks)
        panel.dismiss = null
    }

    fun forgetPanel(activity: Activity) {
        cancelErrorRecovery(activity)
        panels.remove(activity)
    }

    fun interacted(activity: Activity): Boolean {
        val wasPending = panels[activity]?.dismiss != null
        forgetPanel(activity)
        return wasPending
    }

    private val TEST_OUTCOMES = setOf("no-command", "missing-app", "permission", "lock-call", "lock-app", "lock-timer", "lock-sound")
}
