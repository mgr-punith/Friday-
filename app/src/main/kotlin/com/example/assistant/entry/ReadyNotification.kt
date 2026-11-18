package com.example.assistant.entry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.example.assistant.R

object ReadyNotification {
    const val PREFERENCES = "friday_entry_points"
    const val ENABLED = "ready_notification_enabled"
    const val CHANNEL_ID = "friday_ready"
    const val NOTIFICATION_ID = 41

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(ENABLED, enabled)
            .commit()
        if (enabled) show(context) else cancel(context)
    }

    fun restore(context: Context) {
        if (isEnabled(context)) show(context)
    }

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Friday ready",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "A silent shortcut that opens Friday listening"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_friday_status)
            .setContentTitle("Friday ready")
            .setContentText("Tap to speak")
            .setContentIntent(FridayEntryPoints.listenPendingIntent(context, NOTIFICATION_ID))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun preferences(context: Context): SharedPreferences {
        val deviceContext = context.createDeviceProtectedStorageContext()
        val devicePreferences = deviceContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!devicePreferences.contains(ENABLED) && !context.isDeviceProtectedStorage) {
            val legacyPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (legacyPreferences.contains(ENABLED)) {
                devicePreferences.edit()
                    .putBoolean(ENABLED, legacyPreferences.getBoolean(ENABLED, false))
                    .commit()
            }
        }
        return devicePreferences
    }
}
