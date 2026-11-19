package com.example.assistant.entry

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.assistant.AssistantActivity

object FridayEntryPoints {
    const val EXTRA_START_LISTENING = "start_listening"

    fun listenIntent(context: Context): Intent = Intent(context, AssistantActivity::class.java).apply {
        putExtra(EXTRA_START_LISTENING, true)
        addFlags(EntryPointPolicy.notificationTapIntentFlags())
    }

    fun listenPendingIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        listenIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
