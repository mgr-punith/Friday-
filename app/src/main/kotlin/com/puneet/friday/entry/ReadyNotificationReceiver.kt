package com.puneet.friday.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReadyNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (EntryPointPolicy.restoresReadyNotification(intent.action)) {
            ReadyNotification.restore(context)
        }
    }
}
