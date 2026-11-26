package com.puneet.friday.debug.wake

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Keep lifecycle cancellation inside the recorder's existing RuntimeException handling. */
internal object WakeTrialHandoff {
    fun awaitStopped(done: CountDownLatch?, timeoutMs: Long = 2_000) {
        if (done == null) return
        try {
            check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "wake_microphone_still_stopping" }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("wake_microphone_handoff_cancelled", error)
        }
    }
}

internal class WakeAssistantRetry {
    private var attempt = 0

    fun nextDelayMs(): Long? = RETRY_DELAYS_MS.getOrNull(attempt++)

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(150, 300, 600, 900)
    }
}
