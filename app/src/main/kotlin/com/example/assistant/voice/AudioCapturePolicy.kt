package com.example.assistant.voice

internal object AudioCapturePolicy {
    fun acceptsManualStop(sampleCount: Int): Boolean = sampleCount >= MIN_MANUAL_STOP_SAMPLES

    fun reviewedSampleCount(sampleCount: Int, manualStop: Boolean): Int = if (manualStop) {
        (sampleCount - MANUAL_STOP_GUARD_SAMPLES).coerceAtLeast(0)
    } else {
        sampleCount.coerceAtLeast(0)
    }

    private const val MIN_MANUAL_STOP_SAMPLES = WavEncoder.SAMPLE_RATE * 1_500 / 1_000
    private const val MANUAL_STOP_GUARD_SAMPLES = WavEncoder.SAMPLE_RATE * 360 / 1_000
}
