package com.puneet.friday

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow

/** Explicit disclosures always precede sensitive system permission prompts. */
class PermissionsActivity : Activity() {
    private val permissionButtons = linkedMapOf<String, SettingsRow>()
    private lateinit var soundAccessButton: SettingsRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugTools.onAssistantVisible(this)
        val content = AppUi.screen(this, "Permissions")
        content.addView(
            AppUi.notice(this, DebugTools.permissionSummary()),
            AppUi.params(this, 20),
        )
        val access = SettingsGroup(this)

        val permissions = listOf(
            PermissionInfo(
                Manifest.permission.RECORD_AUDIO,
                "Microphone",
                R.drawable.ic_microphone_01,
                DebugTools.microphoneDisclosure(),
            ),
            PermissionInfo(
                Manifest.permission.READ_CONTACTS,
                "Contacts",
                R.drawable.ic_account,
                "Friday reads contact names and phone numbers on this phone to match the person you asked to call. Your contact list and contact details stay on this phone. Friday sends the recorded command to its Vercel backend and NVIDIA or Groq for speech recognition. Groq chooses the phone action. Friday does not store audio or transcripts.",
            ),
            PermissionInfo(
                Manifest.permission.CALL_PHONE,
                "Phone calls",
                R.drawable.ic_phone,
                "Friday uses phone access to place calls you request. An exact contact with one saved number can dial from the visible assistant when your phone is unlocked. Other calls ask you to confirm. Friday does not read or upload call history.",
            ),
        )
        permissions.forEach { permissionInfo ->
            val button = SettingsRow(this, permissionInfo.label, icon = permissionInfo.icon) {
                showDisclosure(permissionInfo)
            }
            permissionButtons[permissionInfo.permission] = button
            access.addRow(button)
        }
        soundAccessButton = SettingsRow(this, "Silent mode", icon = R.drawable.ic_sound) { showSoundAccessDisclosure() }
        access.addRow(soundAccessButton)
        content.addView(access, AppUi.params(this, 16))

        content.addView(
            AppUi.button(this, "Done", AppUi.ButtonTone.PRIMARY, radius = 24) {
                getSharedPreferences("friday", MODE_PRIVATE).edit().putBoolean("setup_seen", true).apply()
                setResult(RESULT_OK)
                finish()
            },
            AppUi.params(this, 8),
        )
    }

    override fun onResume() {
        super.onResume()
        updatePermissionLabels()
    }

    override fun onDestroy() {
        DebugTools.onAssistantHidden(this)
        super.onDestroy()
    }

    private fun showDisclosure(info: PermissionInfo) {
        if (checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED) return
        FridayDialog.show(
            this,
            info.label,
            info.disclosure,
            positive = "Agree and allow",
            negative = "Not now",
        ) {
            val preferences = getSharedPreferences("friday", MODE_PRIVATE)
            val askedBefore = preferences.getBoolean("asked_" + info.permission, false)
            if (askedBefore && !shouldShowRequestPermissionRationale(info.permission)) {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                preferences.edit().putBoolean("asked_" + info.permission, true).apply()
                requestPermissions(arrayOf(info.permission), PERMISSION_REQUEST)
            }
        }
    }

    private fun showSoundAccessDisclosure() {
        if (hasNotificationPolicyAccess()) return
        FridayDialog.show(
            this,
            "Silent mode access",
            "Android requires this settings access before Friday can switch silent mode on. Friday uses it only when you ask to change sound mode.",
            positive = "Open settings",
            negative = "Not now",
        ) {
            openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) updatePermissionLabels()
    }

    private fun updatePermissionLabels() {
        permissionButtons.forEach { (permission, button) ->
            val allowed = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            button.setValue(if (allowed) "Allowed" else "Not allowed")
        }
        soundAccessButton.setValue(if (hasNotificationPolicyAccess()) "Allowed" else "Not allowed")
    }

    private fun hasNotificationPolicyAccess(): Boolean =
        getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            FridayDialog.show(this, "Settings unavailable", "Open Android Settings and allow this access for Friday.")
        }
    }

    private data class PermissionInfo(
        val permission: String,
        val label: String,
        val icon: Int,
        val disclosure: String,
    )

    private companion object {
        const val PERMISSION_REQUEST = 100
    }
}
