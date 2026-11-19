package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Button
import com.example.assistant.entry.EntryPointPolicy
import com.example.assistant.entry.ReadyNotification
import com.example.assistant.ui.AppUi
import com.example.assistant.ui.FridayDialog

class SettingsPanel(private val activity: Activity, onPermissions: () -> Unit) {
    private lateinit var readyNotificationButton: Button
    private lateinit var assistantRoleButton: Button

    private val screen = AppUi.scrollContent(activity, "Settings", "Set up Friday on this phone.")
    val view = screen.first

    init {
        val content = screen.second
        content.addView(AppUi.sectionLabel(activity, "Quick access"), AppUi.params(activity, 12))
        readyNotificationButton = AppUi.navigationButton(activity, "Ready notification", "") { toggleReadyNotification() }
        content.addView(readyNotificationButton, AppUi.params(activity, 8))
        assistantRoleButton = AppUi.navigationButton(activity, "Power button assistant", "") { requestAssistantRole() }
        content.addView(assistantRoleButton, AppUi.params(activity, 24))
        content.addView(AppUi.sectionLabel(activity, "Phone access"), AppUi.params(activity, 12))
        content.addView(AppUi.navigationButton(activity, "Permissions", "Microphone, contacts, calls and silent mode") {
            onPermissions()
        }, AppUi.params(activity, 24))
        content.addView(AppUi.sectionLabel(activity, "Tools"), AppUi.params(activity, 12))
        content.addView(AppUi.navigationButton(activity, "Try a phone action", "Check calls, alarms, apps and sound mode") {
            activity.startActivity(Intent(activity, ActionDebugActivity::class.java))
        }, AppUi.params(activity, 24))
        content.addView(AppUi.text(activity, "Friday listens after you tap. Background listening is off.", 13f, secondary = true),
            AppUi.params(activity, 0))
    }

    fun refresh() {
        updateReadyNotificationButton()
        updateAssistantRoleButton()
    }

    private fun toggleReadyNotification() {
        when (readyNotificationState()) {
            EntryPointPolicy.ReadyNotificationState.ON -> {
                ReadyNotification.setEnabled(activity, false)
                updateReadyNotificationButton()
                return
            }
            EntryPointPolicy.ReadyNotificationState.BLOCKED -> {
                openNotificationSettings()
                return
            }
            EntryPointPolicy.ReadyNotificationState.OFF -> Unit
        }
        if (!notificationsAllowed()) {
            FridayDialog.show(
                activity,
                "Ready notification",
                "Friday keeps one silent notification with a Tap to speak shortcut. The microphone stays off until you tap it.",
                positive = "Allow notifications",
                negative = "Not now",
            ) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), READY_NOTIFICATION_PERMISSION_REQUEST)
            }
            return
        }
        ReadyNotification.setEnabled(activity, true)
        updateReadyNotificationButton()
    }

    fun onPermissionResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != READY_NOTIFICATION_PERMISSION_REQUEST) return false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            ReadyNotification.setEnabled(activity, true)
            updateReadyNotificationButton()
        } else {
            FridayDialog.show(
                activity,
                "Notifications off",
                "Allow Friday notifications in Android Settings to keep the Tap to speak shortcut.",
                positive = "Open settings",
                negative = "Not now",
            ) { openNotificationSettings() }
        }
        return true
    }

    private fun updateReadyNotificationButton() {
        if (!::readyNotificationButton.isInitialized) return
        val detail = when (readyNotificationState()) {
            EntryPointPolicy.ReadyNotificationState.OFF -> "Off · Tap to turn on"
            EntryPointPolicy.ReadyNotificationState.ON -> "On · Tap to turn off"
            EntryPointPolicy.ReadyNotificationState.BLOCKED -> "Blocked · Tap to manage"
        }
        AppUi.setNavigationText(
            readyNotificationButton,
            "Ready notification",
            detail,
        )
    }

    private fun readyNotificationState(): EntryPointPolicy.ReadyNotificationState =
        EntryPointPolicy.readyNotificationState(ReadyNotification.isEnabled(activity), notificationsAllowed())

    private fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openNotificationSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
        } catch (_: RuntimeException) {
            showSettingsError("Notification settings unavailable")
        }
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                activity.startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                return
            }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: RuntimeException) {
            showSettingsError("Assistant settings unavailable")
        }
    }

    private fun updateAssistantRoleButton() {
        if (!::assistantRoleButton.isInitialized) return
        val selected = Build.VERSION.SDK_INT >= 29 &&
            activity.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        AppUi.setNavigationText(
            assistantRoleButton,
            "Power button assistant",
            if (selected) "Selected · Long-press power to speak" else "Choose Friday as your assistant",
        )
    }

    private fun showSettingsError(message: String) {
        FridayDialog.show(activity, "Settings unavailable", message)
    }

    private companion object {
        const val READY_NOTIFICATION_PERMISSION_REQUEST = 202
    }
}
