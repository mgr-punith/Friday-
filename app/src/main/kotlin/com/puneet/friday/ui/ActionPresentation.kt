package com.puneet.friday.ui

import com.puneet.friday.actions.PhoneAction
import com.puneet.friday.actions.RingerMode
import java.util.Locale

/** Describes the returned action, without claiming Android has completed it. */
object ActionPresentation {
    fun describe(action: PhoneAction): String = when (action) {
        is PhoneAction.CallContact -> "Call ${action.name}"
        is PhoneAction.CallNumber -> "Call ${action.number}"
        is PhoneAction.SetAlarm -> "Set alarm · ${String.format(Locale.ENGLISH, "%02d:%02d", action.hour, action.minute)}"
        is PhoneAction.SetTimer -> "Set timer · ${duration(action.seconds)}"
        is PhoneAction.OpenApp -> "Open ${action.name}"
        is PhoneAction.CloseApp -> "Go to home screen"
        is PhoneAction.SetRingerMode -> when (action.mode) {
            RingerMode.SILENT -> "Turn on silent mode"
            RingerMode.VIBRATE -> "Turn on vibrate mode"
            RingerMode.NORMAL -> "Turn on normal sound mode"
        }
    }

    private fun duration(seconds: Int): String {
        val parts = mutableListOf<String>()
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val remaining = seconds % 60
        if (hours > 0) parts.add("$hours ${if (hours == 1) "hour" else "hours"}")
        if (minutes > 0) parts.add("$minutes ${if (minutes == 1) "minute" else "minutes"}")
        if (remaining > 0) parts.add("$remaining ${if (remaining == 1) "second" else "seconds"}")
        return parts.joinToString(" ")
    }
}
