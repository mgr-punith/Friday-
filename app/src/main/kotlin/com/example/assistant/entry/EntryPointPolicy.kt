package com.example.assistant.entry

object EntryPointPolicy {
    const val RECOGNITION_ERROR_CLIENT = 5

    enum class ReadyNotificationState { OFF, ON, BLOCKED }

    fun shouldAutoListen(requested: Boolean, setupSeen: Boolean, microphoneGranted: Boolean): Boolean =
        requested && setupSeen && microphoneGranted

    fun shouldRequestSetup(setupSeen: Boolean, promptOpen: Boolean): Boolean = !setupSeen && !promptOpen

    fun shouldRestoreReadyNotification(enabled: Boolean, notificationsAllowed: Boolean): Boolean =
        enabled && notificationsAllowed

    fun restoresReadyNotification(action: String?): Boolean = action == android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED ||
        action == android.content.Intent.ACTION_BOOT_COMPLETED ||
        action == android.content.Intent.ACTION_MY_PACKAGE_REPLACED

    fun readyNotificationState(enabled: Boolean, notificationsAllowed: Boolean): ReadyNotificationState = when {
        !enabled -> ReadyNotificationState.OFF
        notificationsAllowed -> ReadyNotificationState.ON
        else -> ReadyNotificationState.BLOCKED
    }

    fun notificationTapIntentFlags(): Int =
        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun supportsTileSubtitle(apiLevel: Int): Boolean = apiLevel >= 29

    fun usesPendingIntentTileLaunch(apiLevel: Int): Boolean = apiLevel >= 34
}
