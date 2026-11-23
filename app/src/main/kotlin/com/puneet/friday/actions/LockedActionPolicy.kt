package com.puneet.friday.actions

/** Only supported phone actions may run before the phone is unlocked. */
object LockedActionPolicy {
    fun requiresUnlock(action: PhoneAction, locked: Boolean): Boolean {
        if (!locked) return false
        return PhoneCapabilityRegistry.requiresUnlock(action)
    }
}
