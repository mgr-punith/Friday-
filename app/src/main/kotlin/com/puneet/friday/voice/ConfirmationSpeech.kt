package com.puneet.friday.voice

import com.puneet.friday.actions.PhoneAction
import com.puneet.friday.actions.RingerMode

data class ConfirmationSpeech(val text: String, val speechId: String, val issuedAt: Long, val token: String)

object ConfirmationSpeechPayload {
    fun parse(fields: Map<String, Any?>?, action: PhoneAction): ConfirmationSpeech? {
        if (fields == null || fields.keys != setOf("text", "speechId", "issuedAt", "token")) return null
        val text = fields["text"] as? String ?: return null
        if (text != textFor(action)) return null
        val id = (fields["speechId"] as? String)?.takeIf(UUID::matches) ?: return null
        val issuedAt = fields["issuedAt"] as? Number ?: return null
        val seconds = issuedAt.toLong()
        if (seconds < 0 || seconds.toDouble() != issuedAt.toDouble()) return null
        val token = (fields["token"] as? String)?.takeIf(TOKEN::matches) ?: return null
        return ConfirmationSpeech(text, id, seconds, token)
    }

    private fun textFor(action: PhoneAction): String? = when (action) {
        is PhoneAction.SetRingerMode -> when (action.mode) {
            RingerMode.SILENT -> "Silent on"
            RingerMode.VIBRATE -> "Vibrate on"
            RingerMode.NORMAL -> "Normal mode"
        }
        is PhoneAction.Flashlight -> if (action.on) "Flashlight on" else "Flashlight off"
        is PhoneAction.SetVolume -> "${action.stream.label} volume set"
        is PhoneAction.CloseApp -> "Went to home screen"
        is PhoneAction.SetAlarm -> "Alarm requested"
        is PhoneAction.SetTimer -> "Timer requested"
        is PhoneAction.OpenApp -> "Opening app"
        is PhoneAction.OpenSettings -> "Check settings"
        is PhoneAction.OpenMusic -> if (action.play) "Music requested" else "Music app opened"
        is PhoneAction.PlayMedia -> "Music requested"
        is PhoneAction.ControlMedia -> action.operation.requested
        is PhoneAction.Sequence -> {
            val phrases = action.steps.map { textFor(it) ?: return null }
            phrases.joinToString(". ")
        }
        is PhoneAction.CallContact, is PhoneAction.CallNumber -> null
    }

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
}
