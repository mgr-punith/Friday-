package com.puneet.friday.actions

sealed interface PhoneAction {
    data class CallContact(val name: String) : PhoneAction
    data class CallNumber(val number: String) : PhoneAction
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : PhoneAction
    data class SetTimer(val seconds: Int) : PhoneAction
    data class OpenApp(val name: String) : PhoneAction
    data object OpenMusic : PhoneAction
    data class PlayMedia(val query: String, val appName: String? = null) : PhoneAction
    data class ControlMedia(val operation: MediaOperation) : PhoneAction
    data class CloseApp(val name: String? = null) : PhoneAction
    data class SetRingerMode(val mode: RingerMode) : PhoneAction
    data class Flashlight(val on: Boolean) : PhoneAction
    data class SetVolume(val stream: VolumeStream, val percent: Int) : PhoneAction
    data class OpenSettings(val target: SettingsTarget, val state: SettingState? = null) : PhoneAction
    data class Sequence(val steps: List<PhoneAction>) : PhoneAction
}

enum class MediaOperation(val wireName: String, val label: String, val requested: String) {
    PAUSE("pause", "Pause playback", "Pause requested"),
    RESUME("resume", "Resume playback", "Resume requested"),
    NEXT("next", "Next track", "Next track requested"),
    PREVIOUS("previous", "Previous track", "Previous track requested"),
}

enum class SettingsTarget {
    AIRPLANE_MODE,
}

enum class SettingState {
    ON,
    OFF,
}

enum class VolumeStream(val label: String) {
    MEDIA("Media"), RING("Ring"), ALARM("Alarm"),
}

enum class RingerMode {
    SILENT,
    VIBRATE,
    NORMAL,
}

enum class RequiredAccess {
    MICROPHONE,
    CONTACTS,
    PHONE,
    NOTIFICATION_POLICY,
}

object RingerModePolicy {
    fun requiredAccess(mode: RingerMode, notificationPolicyGranted: Boolean): RequiredAccess? =
        if (mode == RingerMode.SILENT && !notificationPolicyGranted) {
            RequiredAccess.NOTIFICATION_POLICY
        } else {
            null
        }
}

sealed interface ActionResult {
    data class Success(val confirmation: String) : ActionResult
    data class MediaRequest(val confirmation: String) : ActionResult
    data class Handoff(val instruction: String, val music: Boolean = false) : ActionResult
    data class NeedsPermission(val which: RequiredAccess) : ActionResult
    data object NeedsUnlock : ActionResult
    data class Failed(val reason: String) : ActionResult
}

/** Validate before resolving data or starting any Android side effect. */
fun PhoneAction.validationError(): String? = when (this) {
    is PhoneAction.CallContact -> if (validName(name)) null else "Enter a contact name"
    is PhoneAction.CallNumber -> if (normalizePhoneNumber(number) != null) null else "Enter a valid phone number"
    is PhoneAction.SetAlarm -> when {
        hour !in 0..23 || minute !in 0..59 -> "Enter a valid alarm time"
        label != null && (label.length > 80 || label.any(Char::isISOControl)) -> "Enter a shorter alarm label"
        else -> null
    }
    is PhoneAction.SetTimer -> if (seconds > 0) null else "Enter a timer longer than zero"
    is PhoneAction.OpenApp -> if (validName(name)) null else "Enter an app name"
    is PhoneAction.PlayMedia -> when {
        query.isBlank() || query.length > 160 || query.any(Char::isISOControl) -> "Enter a song or artist"
        appName != null && (appName.isBlank() || appName.length > 100 || appName.any(Char::isISOControl)) -> "Enter a music app name"
        else -> null
    }
    is PhoneAction.SetVolume -> if (percent in 0..100) null else "Use a volume from 0 to 100 percent"
    is PhoneAction.Sequence -> when {
        steps.size !in 2..3 || steps.any { it is PhoneAction.Sequence } -> "Use two or three commands"
        steps.dropLast(1).any { !PhoneCapabilityRegistry.canPrecedeAnother(it) } ->
            "Put the command that leaves Friday last"
        else -> steps.firstNotNullOfOrNull { it.validationError() }
    }
    is PhoneAction.CloseApp, is PhoneAction.SetRingerMode, is PhoneAction.Flashlight,
    is PhoneAction.OpenSettings, is PhoneAction.ControlMedia, PhoneAction.OpenMusic -> null
}

/** Allows formatted phone numbers, while rejecting USSD and post-dial instructions. */
fun normalizePhoneNumber(value: String): String? {
    val trimmed = buildString {
        for (character in value.trim()) {
            val digit = character.digitToIntOrNull()
            append(when {
                digit != null -> '0' + digit
                character.isWhitespace() && !character.isISOControl() -> ' '
                character.category == CharCategory.DASH_PUNCTUATION -> '-'
                else -> character
            })
        }
    }
    if (!PHONE_CHARACTERS.matches(trimmed)) return null
    val digits = trimmed.filter { it in '0'..'9' }
    if (digits.length !in 3..15) return null
    return if (trimmed.startsWith('+')) "+$digits" else digits
}

private fun validName(value: String): Boolean = value.length <= 120 &&
    value.none(Char::isISOControl) && NameMatcher.normalize(value).isNotEmpty()

private val PHONE_CHARACTERS = Regex("\\+?[0-9 ()-]+")
