package com.puneet.friday.actions

/** Phone-side capability policy for validation, lock checks and ordered execution. */
object PhoneCapabilityRegistry {
    const val ACTION_PROTOCOL_VERSION = 5
    const val BASE_ACTION_PROTOCOL_VERSION = 4

    enum class SequencePosition { PREPARATORY, TERMINAL }

    data class Capability(
        val id: String,
        val minimumProtocolVersion: Int,
        val requiresUnlock: Boolean,
        val sequencePosition: SequencePosition,
    )

    fun capability(action: PhoneAction): Capability = when (action) {
        is PhoneAction.CallContact -> Capability("call_contact", 1, false, SequencePosition.TERMINAL)
        is PhoneAction.CallNumber -> Capability("call_number", 1, false, SequencePosition.TERMINAL)
        is PhoneAction.SetAlarm -> Capability("set_alarm", 1, true, SequencePosition.TERMINAL)
        is PhoneAction.SetTimer -> Capability("set_timer", 1, false, SequencePosition.TERMINAL)
        is PhoneAction.OpenApp -> Capability("open_app", 1, true, SequencePosition.TERMINAL)
        PhoneAction.OpenMusic -> Capability("open_music", 2, true, SequencePosition.TERMINAL)
        is PhoneAction.PlayMedia -> Capability("play_media", 4, true, SequencePosition.TERMINAL)
        is PhoneAction.ControlMedia -> Capability("control_media", 5, false, SequencePosition.TERMINAL)
        is PhoneAction.CloseApp -> Capability("close_app", 1, true, SequencePosition.TERMINAL)
        is PhoneAction.SetRingerMode -> Capability("set_ringer_mode", 1, false, SequencePosition.PREPARATORY)
        is PhoneAction.Flashlight -> Capability("flashlight", 2, true, SequencePosition.PREPARATORY)
        is PhoneAction.SetVolume -> Capability("set_volume", 2, true, SequencePosition.PREPARATORY)
        is PhoneAction.OpenSettings -> Capability("open_settings", 3, true, SequencePosition.TERMINAL)
        is PhoneAction.Sequence -> Capability("run_actions", 2, action.steps.any(::requiresUnlock), SequencePosition.TERMINAL)
    }

    fun requiresUnlock(action: PhoneAction): Boolean = capability(action).requiresUnlock

    fun canPrecedeAnother(action: PhoneAction): Boolean =
        capability(action).sequencePosition == SequencePosition.PREPARATORY
}
