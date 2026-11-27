package com.puneet.friday.debug.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.puneet.friday.R
import com.puneet.friday.entry.FridayEntryPoints

/** Standard system decoration with a visible, accessible 48 dp icon control. */
internal object WakeUsageNotification {
    // A foreground-service cancellation must never target the paused control.
    const val PAUSED_NOTIFICATION_ID = 63
    fun build(context: Context, state: WakeTrialSession.Snapshot, openSetup: Boolean = false): Notification {
        val active = state.active
        val stopping = state.phase == WakeTrialSession.Phase.STOPPING
        val intent = Intent(context, WakeTrialService::class.java)
            .setAction(if (active) WakeTrialService.PAUSE_USAGE else WakeTrialService.START)
            .putExtra(WakeTrialService.USAGE_REVISION, WakeUsageStore.revision(context))
            .putExtra(WakeTrialService.COMMAND_MODE, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = state.pendingWake?.let { WakeAssistantBridge.tag(FridayEntryPoints.listenIntent(context), it) }
            ?: Intent(context, WakeUsageActivity::class.java)
        val open = PendingIntent.getActivity(context, 73, openIntent, flags)
        val toggle = when {
            active -> PendingIntent.getService(context, 71, intent, flags)
            openSetup || !WakeAssistantBridge.isReady(context) -> open
            else -> PendingIntent.getForegroundService(context, 72, intent, flags)
        }
        val label = if (active) "Pause Hey Friday" else "Resume Hey Friday"
        val icon = if (active) R.drawable.ic_pause_circle else R.drawable.ic_play_circle
        val status = WakeUsagePolicy.status(state)
        val views = RemoteViews(context.packageName, R.layout.notification_wake_usage).apply {
            setTextViewText(R.id.wake_usage_status, "Hey Friday · $status")
            setImageViewResource(R.id.wake_usage_toggle, icon)
            setContentDescription(R.id.wake_usage_toggle, label)
            setBoolean(R.id.wake_usage_toggle, "setEnabled", !stopping)
            // View.setAlpha is not remotable on older Android versions.
            setInt(R.id.wake_usage_toggle, "setImageAlpha", if (stopping) 128 else 255)
            if (!stopping) setOnClickPendingIntent(R.id.wake_usage_toggle, toggle)
            setOnClickPendingIntent(R.id.wake_usage_status, open)
        }
        return Notification.Builder(context, WakeTrialService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_friday_status)
            .setContentTitle("Hey Friday")
            .setContentText(status)
            .setContentIntent(open)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 31 && active) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            .build()
    }

    fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(WakeTrialService.CHANNEL_ID, "Hey Friday", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Pause or resume local wake listening"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    fun showPaused(context: Context, openSetup: Boolean = false) {
        if (WakeUsageStore.mode(context) != WakeUsagePolicy.Mode.HEY_FRIDAY) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            ensureChannel(manager)
            manager.notify(PAUSED_NOTIFICATION_ID, build(context, WakeTrialSession.Snapshot(), openSetup))
        } catch (_: SecurityException) { /* The user can revoke notifications while stopping. */ }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(WakeTrialService.NOTIFICATION_ID)
        hidePaused(context)
    }

    fun hidePaused(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(PAUSED_NOTIFICATION_ID)
    }
}
