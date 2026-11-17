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
    private lateinit var dndButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = AppUi.screen(
            this,
            "Permissions",
            "Choose which phone actions Friday can use.",
        )
        content.addView(
            AppUi.notice(this, "This build does not record audio or listen in the background."),
            AppUi.params(this, 20),
        )
        content.addView(AppUi.sectionLabel(this, "ACCESS"), AppUi.params(this, 8))

        val permissions = listOf(
            PermissionInfo(
                Manifest.permission.RECORD_AUDIO,
                "Microphone",
                "Voice input",
                "Friday will use your microphone to record short voice commands when you start the assistant. Voice audio will be sent to Friday’s backend on AWS in Mumbai and processed by Google’s Gemini. Audio is not stored after processing. This build does not capture audio.",
            ),
            PermissionInfo(
                Manifest.permission.READ_CONTACTS,
                "Contacts",
                "Find a person to call",
                "Friday reads contact names and phone numbers to match who you ask to call. Matching happens on this phone. Your contact list and contact details never leave this device. In the future voice loop, short command audio will be sent to Friday’s backend on AWS in Mumbai and processed by Google’s Gemini; it is not stored after processing.",
            ),
            PermissionInfo(
                Manifest.permission.CALL_PHONE,
                "Phone calls",
                "Place a confirmed call",
                "Friday uses phone access to place a call you request from the visible assistant. Friday asks you to confirm before starting a call. Call history is not read or uploaded. In the future voice loop, short command audio will be sent to Friday’s backend on AWS in Mumbai and processed by Google’s Gemini; it is not stored after processing.",
            ),
        )
        permissions.forEach { permissionInfo ->
            val button = AppUi.navigationButton(this, permissionInfo.label, permissionInfo.detail) {
                showDisclosure(permissionInfo)
            }
            permissionButtons[permissionInfo.permission] = button
            content.addView(button, AppUi.params(this, 8))
        }

        dndButton = AppUi.navigationButton(this, "Sound mode", "Change silent, vibrate or normal mode") {
            FridayDialog.show(
                this,
                "Sound mode access",
                "Do Not Disturb access lets Friday switch silent mode when you request it. Android manages this access in Settings.",
                positive = "Open settings",
                negative = "Not now",
            ) { openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
        content.addView(dndButton, AppUi.params(this, 24))
        content.addView(
            AppUi.button(this, "Done", AppUi.ButtonTone.PRIMARY) {
                getSharedPreferences("friday", MODE_PRIVATE).edit().putBoolean("setup_seen", true).apply()
                finish()
            },
            AppUi.params(this, 8),
        )
    }

    override fun onResume() {
        super.onResume()
        updatePermissionLabels()
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
        if (::dndButton.isInitialized) {
            val allowed = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
            AppUi.setNavigationText(
                dndButton,
                "Sound mode",
                "Silent mode · ${if (allowed) "Allowed" else "Not allowed"}",
            )
        }
    }

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
