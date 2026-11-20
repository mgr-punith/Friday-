package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import com.example.assistant.ui.AppUi
import com.example.assistant.ui.FridayDialog

/** Explicit disclosures always precede sensitive system permission prompts. */
class PermissionsActivity : Activity() {
    private val permissionButtons = linkedMapOf<String, Button>()
    private lateinit var soundAccessButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugTools.onAssistantVisible(this)
        val content = AppUi.screen(
            this,
            "Permissions",
            "Choose which phone actions Friday can use.",
        )
        content.addView(
            AppUi.notice(this, DebugTools.permissionSummary()),
            AppUi.params(this, 20),
        )
        content.addView(AppUi.sectionLabel(this, "ACCESS"), AppUi.params(this, 8))

        val permissions = listOf(
            PermissionInfo(
                Manifest.permission.RECORD_AUDIO,
                "Microphone",
                "Voice input",
                DebugTools.microphoneDisclosure(),
            ),
            PermissionInfo(
                Manifest.permission.READ_CONTACTS,
                "Contacts",
                "Find a person to call",
                "Friday reads contact names and phone numbers on this phone to match the person you asked to call. Your contact list and contact details stay on this phone. Friday sends the recorded command to its Vercel backend and Google Gemini, and does not store the audio.",
            ),
            PermissionInfo(
                Manifest.permission.CALL_PHONE,
                "Phone calls",
                "Place a confirmed call",
                "Friday uses phone access to place a call you request from the visible assistant. You confirm the resolved number before Android starts the call. Friday does not read or upload call history.",
            ),
        )
        permissions.forEach { permissionInfo ->
            val button = AppUi.navigationButton(this, permissionInfo.label, permissionInfo.detail) {
                showDisclosure(permissionInfo)
            }
            permissionButtons[permissionInfo.permission] = button
            content.addView(button, AppUi.params(this, 8))
        }
        soundAccessButton = AppUi.navigationButton(
            this,
            "Silent mode",
            "Required by Android to change ringer mode",
        ) {
            showSoundAccessDisclosure()
        }
        content.addView(soundAccessButton, AppUi.params(this, 8))

        content.addView(
            AppUi.button(this, "Done", AppUi.ButtonTone.PRIMARY) {
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
            val info = when (permission) {
                Manifest.permission.RECORD_AUDIO -> "Microphone" to "Voice input"
                Manifest.permission.READ_CONTACTS -> "Contacts" to "Find a person to call"
                else -> "Phone calls" to "Place a confirmed call"
            }
            AppUi.setNavigationText(
                button,
                info.first,
                "${info.second} · ${if (allowed) "Allowed" else "Not allowed"}",
            )
        }
        AppUi.setNavigationText(
            soundAccessButton,
            "Silent mode",
            "Required by Android to change ringer mode · ${if (hasNotificationPolicyAccess()) "Allowed" else "Not allowed"}",
        )
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
        val detail: String,
        val disclosure: String,
    )

    private companion object {
        const val PERMISSION_REQUEST = 100
    }
}
