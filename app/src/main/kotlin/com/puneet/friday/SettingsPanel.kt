package com.puneet.friday

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.puneet.friday.actions.AndroidActionExecutor
import com.puneet.friday.actions.MusicApps
import com.puneet.friday.actions.DeviceLock
import com.puneet.friday.entry.AssistantSettings
import com.puneet.friday.entry.EntryPointPolicy
import com.puneet.friday.entry.ReadyNotification
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.AnswerConsent
import com.puneet.friday.ui.AnchoredPopup
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow
import com.puneet.friday.auth.AccountStore

class SettingsPanel(private val activity: Activity, onAccount: () -> Unit, onBack: () -> Unit, onPermissions: () -> Unit) {
    private val popup = AnchoredPopup(activity)
    private val about = AppUi.roundIconButton(activity, R.drawable.ic_info_circle, "About Friday") {}
    private val accountButton: SettingsRow
    private val refreshStartMode: () -> Unit
    private val readyNotificationButton: SettingsRow
    private val assistantButton: SettingsRow
    private val assistantFallback: SettingsRow
    private val assistantFallbackGroup: SettingsGroup
    private val playerExecutor = AndroidActionExecutor(activity)
    private val musicButton: SettingsRow
    private var musicDialog: android.app.Dialog? = null
    private var playerLookup = 0
    private val answersButton: SettingsRow
    private var answerConsentDialog: android.app.Dialog? = null
    private var pendingAssistantSettings = false
    private var showAssistantFallback = false

    private val screen = AppUi.groupedContent(activity, "Settings", onBack = { dismissPopups(); onBack() }, trailing = about)
    val view = screen.first

    init {
        val content = screen.second
        accountButton = SettingsRow(activity, "Account", icon = R.drawable.ic_account, onClick = onAccount)
        content.addView(SettingsGroup(activity).apply { addRow(accountButton) }, AppUi.params(activity, 16))
        val access = SettingsGroup(activity)
        refreshStartMode = DebugTools.addStartOptions(activity, access)
        assistantButton = SettingsRow(activity, "Default assistant", icon = R.drawable.ic_assistant) { openAssistantSettings() }
        access.addRow(assistantButton)
        readyNotificationButton = SettingsRow(activity, "Ready notification", showChevron = false, icon = R.drawable.ic_notification) {
            toggleReadyNotification()
        }
        access.addRow(readyNotificationButton)
        content.addView(access, AppUi.params(activity, 16))
        assistantFallback = SettingsRow(activity, "Open Default apps", icon = R.drawable.ic_apps) {
            openAssistantSettings(AssistantSettings.Destination.DEFAULT_APPS)
        }
        assistantFallbackGroup = SettingsGroup(activity).apply { addRow(assistantFallback); visibility = View.GONE }
        content.addView(assistantFallbackGroup, AppUi.params(activity, 16))
        val phone = SettingsGroup(activity).apply {
            addRow(SettingsRow(activity, "Permissions", icon = R.drawable.ic_permissions, onClick = onPermissions))
        }
        answersButton = SettingsRow(activity, "Short answers", showChevron = false, icon = R.drawable.ic_info_circle) {
            if (AnswerConsent.isAllowed(activity)) {
                AnswerConsent.setAllowed(activity, false)
                refresh()
            } else {
                answerConsentDialog = AnswerConsent.show(activity) { answerConsentDialog = null; refresh() }
            }
        }
        musicButton = SettingsRow(activity, "Default music app", icon = R.drawable.ic_sound) { chooseMusicApp() }
        phone.addRow(musicButton)
        phone.addRow(answersButton)
        content.addView(phone, AppUi.params(activity, 16))
        val debug = SettingsGroup(activity)
        DebugTools.addSettings(activity, debug)
        if (debug.childCount > 0) content.addView(debug, AppUi.params(activity, 16))
        about.setOnClickListener { showAbout() }
    }

    fun refresh() {
        val account = AccountStore(activity).session()
        accountButton.setValue(account?.let { it.displayName.ifBlank { it.email }.ifBlank { "Signed in" } } ?: "Signed out")
        accountButton.isEnabled = account != null
        refreshStartMode()
        val preferred = activity.getSharedPreferences(MusicApps.PREFS, Activity.MODE_PRIVATE).getString(MusicApps.PREFERRED, null)
        val playerLabel = preferred?.let { pkg ->
            try { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)) }
            catch (_: PackageManager.NameNotFoundException) { null }
        }
        musicButton.setValue(playerLabel?.toString() ?: "Choose an app")
        updateReadyNotificationButton()
        updateAssistantButton()
        answersButton.setValue(if (AnswerConsent.isAllowed(activity)) "On" else "Off")
    }

    private fun chooseMusicApp() {
        if (DeviceLock.isLocked(activity)) return
        val request = ++playerLookup
        Thread {
            val apps = try { playerExecutor.musicApps() } catch (_: RuntimeException) { emptyList() }
            activity.runOnUiThread {
                if (request != playerLookup || activity.isFinishing || activity.isDestroyed || !view.isShown ||
                    DeviceLock.isLocked(activity)) return@runOnUiThread
                if (apps.isEmpty()) {
                    musicDialog = FridayDialog.show(activity, "Default music app", "Friday couldn't find an installed music player.")
                } else {
                    val labels = apps.map { app ->
                        if (apps.count { it.label == app.label } > 1) "${app.label} · ${app.packageName}" else app.label
                    }
                    musicDialog = FridayDialog.choices(activity, "Default music app", labels + "Clear default") { index ->
                        if (!DeviceLock.isLocked(activity)) {
                            playerExecutor.setPreferredPlayer(apps.getOrNull(index))
                            refresh()
                        }
                    }
                }
            }
        }.start()
    }

    fun dismissPopups() {
        playerLookup++
        musicDialog?.dismiss()
        musicDialog = null
        popup.dismiss()
        answerConsentDialog?.dismiss()
        answerConsentDialog = null
    }

    private fun showAbout() {
        val menu = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(SettingsRow(activity, "Open-source licenses", showChevron = false, icon = R.drawable.ic_document) {
                popup.dismiss()
                activity.startActivity(Intent(activity, LicensesActivity::class.java))
            }, AppUi.params(activity, 0))
            addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.settings_divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, AppUi.dp(activity, 1))))
            addView(SettingsRow(activity, "Friday for Android", showChevron = false, icon = R.drawable.ic_friday_status).apply {
                setValue(BuildConfig.VERSION_NAME)
            }, AppUi.params(activity, 0))
        }
        popup.show(about, menu)
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
        val detail = when (readyNotificationState()) {
            EntryPointPolicy.ReadyNotificationState.OFF -> "Off"
            EntryPointPolicy.ReadyNotificationState.ON -> "On"
            EntryPointPolicy.ReadyNotificationState.BLOCKED -> "Blocked"
        }
        readyNotificationButton.setValue(detail)
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
        val selected = AssistantSettings.isSelected(activity)
        if (pendingAssistantSettings) {
            showAssistantFallback = !selected
            pendingAssistantSettings = false
        }
        if (selected) showAssistantFallback = false
        assistantButton.setValue(if (selected) "Friday" else "Not selected")
        assistantFallbackGroup.visibility = if (showAssistantFallback) View.VISIBLE else View.GONE
    }

    private fun showSettingsError(message: String) {
        FridayDialog.show(activity, "Settings unavailable", message)
    }

    private companion object {
        const val READY_NOTIFICATION_PERMISSION_REQUEST = 202
    }
}
