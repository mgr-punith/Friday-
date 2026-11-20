package com.example.assistant.debug.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** ADB-only handoff previews. This does not start the listener or run a phone action. */
class WakeTrialTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val outcome = intent.getStringExtra(EXTRA_OUTCOME) ?: return
        if (outcome !in SAFE_OUTCOMES) return
        WakeTrialService.simulateWakeForTest(outcome = outcome)
    }

    private companion object {
        const val ACTION = "com.example.assistant.debug.TRIGGER_WAKE_TEST"
        const val EXTRA_OUTCOME = "debug_wake_outcome"
        val SAFE_OUTCOMES = setOf("lock-call", "lock-app", "lock-timer", "lock-sound", "no-command", "missing-app")
    }
}
