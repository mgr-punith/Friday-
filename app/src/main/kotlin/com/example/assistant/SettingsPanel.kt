package com.example.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.assistant.entry.AssistantSettings
import com.example.assistant.entry.EntryPointPolicy
import com.example.assistant.entry.ReadyNotification
import com.example.assistant.ui.AppUi
import com.example.assistant.ui.FridayDialog

class SettingsPanel(private val activity: Activity, onPermissions: () -> Unit) {
    private lateinit var readyNotificationButton: Button
    private lateinit var assistantButton: Button
    private lateinit var assistantFallback: Button
    private lateinit var listeningSummary: TextView
    private var pendingAssistantSettings = false
    private var showAssistantFallback = false

    private val screen = AppUi.scrollContent(activity, "Settings", "Set up Friday on this phone.")
    val view = screen.first

    init {
        val content = screen.second
        DebugTools.addStartOptions(activity, content)
        content.addView(AppUi.sectionLabel(activity, "Quick access"), AppUi.params(activity, 8))
        readyNotificationButton = AppUi.navigationButton(activity, "Ready notification", "") { toggleReadyNotification() }
        content.addView(readyNotificationButton, AppUi.params(activity, 8))
        val assistantControls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        assistantButton = AppUi.navigationButton(activity, "Default assistant", "") { openAssistantSettings() }
        assistantControls.addView(assistantButton, AppUi.params(activity, 0))
        assistantFallback = AppUi.button(activity, "Open Default apps") {
            openAssistantSettings(AssistantSettings.Destination.DEFAULT_APPS)
        }.apply { visibility = View.GONE }
        assistantControls.addView(assistantFallback, AppUi.params(activity, 0).apply { topMargin = AppUi.dp(activity, 8) })
        content.addView(assistantControls, AppUi.params(activity, 16))
        content.addView(AppUi.sectionLabel(activity, "Phone access"), AppUi.params(activity, 8))
        content.addView(AppUi.navigationButton(activity, "Permissions", "Microphone, contacts, calls and silent mode") {
            onPermissions()
        }, AppUi.params(activity, 16))
        content.addView(AppUi.sectionLabel(activity, "Tools"), AppUi.params(activity, 8))
        content.addView(AppUi.navigationButton(activity, "Try a phone action", "Check calls, alarms, apps and sound mode") {
            activity.startActivity(Intent(activity, ActionDebugActivity::class.java))
        }, AppUi.params(activity, 16))
        DebugTools.addSettings(activity, content)
        listeningSummary = AppUi.text(activity, DebugTools.listeningSummary(), 12f, secondary = true)
        content.addView(listeningSummary, AppUi.params(activity, 0))
    }

    fun refresh() {
        updateReadyNotificationButton()
        updateAssistantButton()
        listeningSummary.text = DebugTools.listeningSummary()
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

    private fun openAssistantSettings(from: AssistantSettings.Destination = AssistantSettings.Destination.ASSISTANT) {
        pendingAssistantSettings = true
        if (AssistantSettings.open(activity, from) == null) {
            pendingAssistantSettings = false
            showAssistantFallback = true
            updateAssistantButton()
            showSettingsError("This phone doesn't allow Friday to open assistant settings.")
        }
    }

    private fun updateAssistantButton() {
        if (!::assistantButton.isInitialized) return
        val selected = AssistantSettings.isSelected(activity)
        if (pendingAssistantSettings) {
            showAssistantFallback = !selected
            pendingAssistantSettings = false
        }
        if (selected) showAssistantFallback = false
        AppUi.setNavigationText(
            assistantButton,
            "Default assistant",
            if (selected) "Friday selected" else "Choose Friday in Android Settings",
        )
        assistantFallback.visibility = if (showAssistantFallback) View.VISIBLE else View.GONE
    }

    private fun showSettingsError(message: String) {
        FridayDialog.show(activity, "Settings unavailable", message)
    }

    private companion object {
        const val READY_NOTIFICATION_PERMISSION_REQUEST = 202
    }
}
