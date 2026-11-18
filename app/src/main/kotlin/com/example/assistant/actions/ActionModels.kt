package com.example.assistant.actions

sealed interface PhoneAction {
    data class CallContact(val name: String) : PhoneAction
    data class CallNumber(val number: String) : PhoneAction
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : PhoneAction
    data class SetTimer(val seconds: Int) : PhoneAction
    data class OpenApp(val name: String) : PhoneAction
    data class CloseApp(val name: String? = null) : PhoneAction
    data class SetRingerMode(val mode: RingerMode) : PhoneAction
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
}

sealed interface ActionResult {
    data class Success(val confirmation: String) : ActionResult
    data class NeedsPermission(val which: RequiredAccess) : ActionResult
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
    is PhoneAction.CloseApp, is PhoneAction.SetRingerMode -> null
}

/** Allows formatted phone numbers, while rejecting USSD and post-dial instructions. */
fun normalizePhoneNumber(value: String): String? {
    val trimmed = value.trim()
    if (!PHONE_CHARACTERS.matches(trimmed)) return null
    val digits = trimmed.filter { it in '0'..'9' }
    if (digits.length !in 3..15) return null
    return if (trimmed.startsWith('+')) "+$digits" else digits
}

private fun validName(value: String): Boolean = value.length <= 120 &&
    value.none(Char::isISOControl) && NameMatcher.normalize(value).isNotEmpty()

private val PHONE_CHARACTERS = Regex("\\+?[0-9 ()-]+")
