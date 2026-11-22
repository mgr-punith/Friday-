package com.puneet.friday.ui

/** Compact assistant panels yield back to the wake listener after a successful result. */
object CompletionPolicy {
    fun closeWhenLeaving(compact: Boolean, hasResult: Boolean): Boolean = compact && hasResult

    fun closeDelay(compact: Boolean, hasResult: Boolean, speaking: Boolean, speechAvailable: Boolean, interacted: Boolean): Long? {
        if (!compact || !hasResult || interacted) return null
        return if (speaking || speechAvailable) 12_000 else 0
    }

    fun spokenConfirmation(message: String): String =
        if (message.startsWith("Calling ") || message.startsWith("Opening ") || message.startsWith("Timer request sent") || message.startsWith("Alarm request sent")) message else "Done. $message"
}
