package com.example.assistant.actions

/** Only supported phone actions may run before the phone is unlocked. */
object LockedActionPolicy {
    fun requiresUnlock(action: PhoneAction, locked: Boolean): Boolean {
        if (!locked) return false
        return when (action) {
            is PhoneAction.CallContact, is PhoneAction.CallNumber,
            is PhoneAction.SetTimer, is PhoneAction.SetRingerMode -> false
            else -> true
        }
    }
}
