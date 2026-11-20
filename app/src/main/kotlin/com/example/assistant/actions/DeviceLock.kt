package com.example.assistant.actions

import android.app.KeyguardManager
import android.content.Context

/** Missing or unavailable lock state must not permit a protected action. */
object DeviceLock {
    fun isLocked(context: Context): Boolean = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        keyguard == null || keyguard.isKeyguardLocked || keyguard.isDeviceLocked
    } catch (_: RuntimeException) {
        true
    }

    fun requiresUnlock(context: Context, action: PhoneAction): Boolean =
        LockedActionPolicy.requiresUnlock(action, isLocked(context))
}
