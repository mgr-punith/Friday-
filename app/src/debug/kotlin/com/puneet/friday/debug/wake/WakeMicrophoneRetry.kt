package com.puneet.friday.debug.wake

/** Brief outages in one user-started run can recover after the old recorder releases. */
internal class WakeMicrophoneRetry {
    private var attempt = 0

    fun nextDelayMs(audioMsSinceOpen: Long): Long? {
        if (audioMsSinceOpen >= 5_000) attempt = 0
        return DELAYS_MS.getOrNull(attempt++)
    }

    private companion object {
        val DELAYS_MS = longArrayOf(250, 1_000)
    }
}
