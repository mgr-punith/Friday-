package com.puneet.friday.entry

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.voice.VoiceInteractionService

/** Public settings routes for selecting Friday on Android 8 and newer. */
object AssistantSettings {
    enum class Destination(val action: String) {
        ASSISTANT(Settings.ACTION_VOICE_INPUT_SETTINGS),
        DEFAULT_APPS(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        SETTINGS(Settings.ACTION_SETTINGS),
    }

    fun isSelected(context: Context): Boolean = try {
        VoiceInteractionService.isActiveService(context, ComponentName(context, FridayVoiceInteractionService::class.java))
    } catch (_: RuntimeException) {
        false
    }

    fun open(activity: Activity, from: Destination = Destination.ASSISTANT): Destination? =
        firstAvailable(from) { destination ->
            // AOSP's assistant role is non-requestable, so a role-request popup can close without a chooser.
            // Launch the public settings action instead; OEMs may omit or restrict individual screens.
            activity.startActivity(Intent(destination.action))
        }

    internal fun firstAvailable(
        from: Destination = Destination.ASSISTANT,
        launch: (Destination) -> Unit,
    ): Destination? {
        for (destination in Destination.entries.drop(from.ordinal)) {
            try {
                launch(destination)
                return destination
            } catch (_: RuntimeException) {
                // Missing activities and OEM security restrictions must not crash setup.
            }
        }
        return null
    }
}
