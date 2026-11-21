package com.puneet.friday.debug.wake

/** A saved entry choice never grants permission to restart microphone capture. */
internal object WakeUsagePolicy {
    enum class Mode(val value: String) {
        TAP("tap"), HEY_FRIDAY("hey_friday");

        companion object {
            fun from(value: String?): Mode = entries.firstOrNull { it.value == value } ?: TAP
        }
    }

    fun accepts(mode: Mode, currentRevision: Long, requestedRevision: Long): Boolean =
        mode == Mode.HEY_FRIDAY && currentRevision > 0 && requestedRevision == currentRevision

    fun canAdopt(state: WakeTrialSession.Snapshot): Boolean = state.active && state.commandMode &&
        state.threshold == WakeTrialSession.DEFAULT_THRESHOLD && state.phase != WakeTrialSession.Phase.STOPPING

    fun retainsNotification(controlled: Boolean, mode: Mode): Boolean = controlled && mode == Mode.HEY_FRIDAY

    fun status(state: WakeTrialSession.Snapshot): String = when (state.phase) {
        WakeTrialSession.Phase.STOPPED -> "Paused"
        WakeTrialSession.Phase.STARTING -> "Starting"
        WakeTrialSession.Phase.LISTENING -> "Listening"
        WakeTrialSession.Phase.HANDOFF -> "Tap to speak"
        WakeTrialSession.Phase.PAUSED -> "Friday is open"
        WakeTrialSession.Phase.STOPPING -> "Stopping"
    }
}
