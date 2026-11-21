package com.puneet.friday.debug.wake

/** One pending error dismissal, scoped to the wake run that opened this panel. */
internal class WakeErrorRecovery(private val run: Long) {
    private var revision = 0L
    private var pending: Long? = null

    fun schedule(state: WakeTrialSession.Snapshot): Long? {
        cancel()
        if (!canRecover(state)) return null
        return revision.also { pending = it }
    }

    fun cancel() {
        revision++
        pending = null
    }

    fun consume(ticket: Long, state: WakeTrialSession.Snapshot): Boolean {
        if (pending != ticket) return false
        pending = null
        return canRecover(state)
    }

    private fun canRecover(state: WakeTrialSession.Snapshot): Boolean =
        state.generation == run && state.commandMode && state.phase == WakeTrialSession.Phase.PAUSED
}
