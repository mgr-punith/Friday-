package com.puneet.friday.debug.wake

/** Access under the service's capture lock; identities keep overlapping Activities independent. */
internal class WakeCaptureGate {
    private val visible = mutableSetOf<Any>()
    private var commands = 0
    private val speakers = mutableSetOf<Any>()

    fun visible(owner: Any) { visible.add(owner) }
    fun hidden(owner: Any) { visible.remove(owner) }
    fun beginCommand() { commands++ }
    fun endCommand() { commands = (commands - 1).coerceAtLeast(0) }
    fun speaking(owner: Any) { speakers.add(owner) }
    fun silent(owner: Any) { speakers.remove(owner) }
    val speechActive: Boolean get() = speakers.isNotEmpty()
    val commandActive: Boolean get() = commands > 0
    val visibleCount: Int get() = visible.size
    val occupied: Boolean get() = commands > 0 || visible.isNotEmpty() || speechActive

    fun canCapture(state: WakeTrialSession.Snapshot): Boolean = !occupied &&
        (state.phase == WakeTrialSession.Phase.STARTING || state.phase == WakeTrialSession.Phase.PAUSED)
}
