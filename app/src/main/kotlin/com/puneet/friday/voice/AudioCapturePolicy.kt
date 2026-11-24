package com.puneet.friday.voice

internal object AudioCapturePolicy {
    fun commandDetector(afterWake: Boolean): SpeechEndDetector =
        if (afterWake) SpeechEndDetector(calibrationFrames = 4, inputGain = WAKE_SPEECH_GAIN)
        else SpeechEndDetector()

    fun hasCommandEnergy(processor: PcmProcessor, samples: ShortArray, afterWake: Boolean): Boolean =
        processor.hasSpeech(samples, inputGain = if (afterWake) WAKE_SPEECH_GAIN else 1f)

    fun canStopAfterSilence(afterWake: Boolean, sampleCount: Int): Boolean =
        !afterWake || sampleCount >= MIN_WAKE_LISTEN_SAMPLES

    fun acceptsManualStop(sampleCount: Int): Boolean = sampleCount >= MIN_MANUAL_STOP_SAMPLES

    fun reviewedSampleCount(sampleCount: Int, manualStop: Boolean): Int = if (manualStop) {
        (sampleCount - MANUAL_STOP_GUARD_SAMPLES).coerceAtLeast(0)
    } else {
        sampleCount.coerceAtLeast(0)
    }

    private const val MIN_MANUAL_STOP_SAMPLES = WavEncoder.SAMPLE_RATE * 1_500 / 1_000
    private const val WAKE_SPEECH_GAIN = 1.6f
    private const val MIN_WAKE_LISTEN_SAMPLES = WavEncoder.SAMPLE_RATE * 2
    private const val MANUAL_STOP_GUARD_SAMPLES = WavEncoder.SAMPLE_RATE * 360 / 1_000
}
