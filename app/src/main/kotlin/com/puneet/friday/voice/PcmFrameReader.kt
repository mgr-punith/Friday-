package com.puneet.friday.voice

internal class MicrophoneUnavailable : IllegalStateException("Microphone unavailable. Try again.")

/** AudioRecord may return part of a frame, no samples yet, or an Android error code. */
internal object PcmFrameReader {
    const val FRAME_SAMPLES = 320
    const val FRAME_MS = 20L
    private const val STALL_MS = 2_000L

    fun readFrames(
        read: (ShortArray, Int, Int) -> Int,
        nowMs: () -> Long,
        cancelled: () -> Boolean,
        waitForAudio: (Long) -> Unit,
        onFrame: (ShortArray) -> Boolean,
    ) {
        val frame = ShortArray(FRAME_SAMPLES)
        var offset = 0
        var lastFrameMs = nowMs()
        while (!cancelled()) {
            val count = read(frame, offset, frame.size - offset)
            // A manual finish may arrive during the read that completes the last frame.
            if (cancelled() && count != frame.size - offset) return
            if (count < 0) throw MicrophoneUnavailable()
            offset += count
            if (offset == frame.size) {
                if (!onFrame(frame)) return
                lastFrameMs = nowMs()
                offset = 0
            } else {
                if (nowMs() - lastFrameMs >= STALL_MS) throw MicrophoneUnavailable()
                waitForAudio(FRAME_MS)
            }
        }
    }
}
