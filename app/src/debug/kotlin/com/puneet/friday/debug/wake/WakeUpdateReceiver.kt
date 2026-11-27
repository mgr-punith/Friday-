package com.puneet.friday.debug.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the resume control after an app update. The user starts the microphone. */
class WakeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (WakeUsagePolicy.showResumeAfterUpdate(WakeUsageStore.mode(context), WakeUsageStore.consented(context),
                FridayKeywordModel.available(context.assets), WakeTrialService.session.snapshot().active)) {
            WakeUsageNotification.showPaused(context, openSetup = true)
        }
    }
}
