package com.puneet.friday.debug.wake

import java.security.MessageDigest

/** In-memory, generation-scoped results. No audio or transcripts enter this state. */
internal class WakeTrialSession {
    enum class Phase { STOPPED, STARTING, LISTENING, HANDOFF, PAUSED, STOPPING }
    data class Snapshot(
        val generation: Long = 0,
        val phase: Phase = Phase.STOPPED,
        val threshold: Float = DEFAULT_THRESHOLD,
        val commandMode: Boolean = false,
        val detections: Int = 0,
        val syntheticDetections: Int = 0,
        val openedCommands: Int = 0,
        val detectionTimesMs: List<Long> = emptyList(),
        val pendingWake: String? = null,
        val handoffRequested: Boolean = false,
        val startedMs: Long = 0,
        val createdAtMs: Long = 0,
        val elapsedMs: Long = 0,
        val audioMs: Long = 0,
        val computeMs: Long = 0,
        val lastDetectionMs: Long? = null,
        val message: String = "Microphone off",
    ) {
        val active: Boolean get() = phase != Phase.STOPPED
    }

    private var value = Snapshot()

    @Synchronized fun snapshot(): Snapshot = value

    @Synchronized fun begin(threshold: Float, commandMode: Boolean = false, nowMs: Long = System.currentTimeMillis()): Long? {
        require(threshold in THRESHOLDS)
        if (value.active) return null
        value = Snapshot(generation = value.generation + 1, phase = Phase.STARTING, threshold = threshold,
            commandMode = commandMode, createdAtMs = nowMs,
            message = "Starting detector")
        return value.generation
    }

    @Synchronized fun listening(generation: Long, nowMs: Long): Boolean {
        if (generation != value.generation || value.phase !in listOf(Phase.STARTING, Phase.PAUSED)) return false
        value = value.copy(phase = Phase.LISTENING, startedMs = value.startedMs.takeIf { it > 0 } ?: nowMs,
            message = "Listening for Hey Friday")
        return true
    }

    @Synchronized fun startingMicrophone(generation: Long, retry: Boolean = false): Boolean {
        val from = if (retry) Phase.LISTENING else Phase.PAUSED
        if (generation != value.generation || (value.phase != Phase.STARTING && value.phase != from)) return false
        value = value.copy(phase = Phase.STARTING, message = "Starting microphone")
        return true
    }

    @Synchronized fun detected(generation: Long, elapsedMs: Long, synthetic: Boolean = false): Boolean {
        if (generation != value.generation || value.phase != Phase.LISTENING) return false
        val count = value.detections + 1
        value = value.copy(detections = count, lastDetectionMs = elapsedMs,
            syntheticDetections = value.syntheticDetections + if (synthetic) 1 else 0,
            detectionTimesMs = (value.detectionTimesMs + elapsedMs).takeLast(64),
            phase = if (value.commandMode) Phase.HANDOFF else Phase.LISTENING,
            pendingWake = if (value.commandMode) "${value.createdAtMs}:$generation:$count" else null,
            handoffRequested = false,
            message = if (value.commandMode) "Opening Friday" else value.message)
        return true
    }

    @Synchronized fun isPendingWake(token: String): Boolean =
        value.commandMode && value.phase == Phase.HANDOFF && value.pendingWake == token

    @Synchronized fun consumeWake(token: String): Boolean {
        if (!isPendingWake(token)) return false
        value = value.copy(phase = Phase.PAUSED, pendingWake = null, openedCommands = value.openedCommands + 1,
            message = "Paused while Friday is open")
        return true
    }

    @Synchronized fun claimHandoff(generation: Long): String? {
        if (generation != value.generation || value.phase != Phase.HANDOFF || value.handoffRequested) return null
        val token = value.pendingWake ?: return null
        value = value.copy(handoffRequested = true)
        return token
    }

    @Synchronized fun awaitingTap(generation: Long) {
        if (value.generation == generation && value.phase == Phase.HANDOFF) {
            value = value.copy(message = "Wake heard. Tap the notification to speak.")
        }
    }

    @Synchronized fun expireWake(generation: Long, token: String): Boolean {
        if (generation != value.generation || !isPendingWake(token)) return false
        value = value.copy(phase = Phase.PAUSED, pendingWake = null, handoffRequested = false,
            message = "Restarting wake listening")
        return true
    }

    @Synchronized fun pause(generation: Long): Boolean {
        if (generation != value.generation || !value.commandMode || !value.active || value.phase == Phase.STOPPING) return false
        value = value.copy(phase = Phase.PAUSED, pendingWake = null, message = "Paused while Friday is open")
        return true
    }

    @Synchronized fun addCompute(generation: Long, audioMs: Long, computeMs: Long) {
        if (generation == value.generation) value = value.copy(audioMs = value.audioMs + audioMs,
            computeMs = value.computeMs + computeMs)
    }

    @Synchronized fun stopping(generation: Long, message: String) {
        if (generation == value.generation && value.active) value = value.copy(phase = Phase.STOPPING, pendingWake = null, message = message)
    }

    @Synchronized fun finish(generation: Long, elapsedMs: Long, message: String) {
        if (generation != value.generation) return
        value = value.copy(phase = Phase.STOPPED, pendingWake = null, elapsedMs = elapsedMs, message = message)
    }

    companion object {
        // Adjustments relative to the bundled model's calibrated cutoff. 1f uses it as-is.
        const val STRICT_THRESHOLD = 2f
        const val DEFAULT_THRESHOLD = 1f
        const val HIGHER_SENSITIVITY_THRESHOLD = 0.9f
        val THRESHOLDS = listOf(STRICT_THRESHOLD, DEFAULT_THRESHOLD, HIGHER_SENSITIVITY_THRESHOLD)
    }
}

internal object WakeTrialModel {
    const val BYTES = 60_832
    const val SHA256 = "04aaffa5f111704db18ec593251a3dfbb009638009e081c9c87830755c3563a6"
    const val RELATIVE_PATH = "wake-trial/hey_friday.tflite"
    const val DOWNLOAD_URL = "https://cdn.jsdelivr.net/gh/JohnnyPrimus/Custom_V2_MicroWakeWords@" +
        "2987c5ae4e4c8e7ac277bdede0904d99b4ed477a/models/hey_friday/hey_friday.tflite"

    fun accepts(bytes: ByteArray): Boolean = bytes.size == BYTES &&
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == SHA256
}
