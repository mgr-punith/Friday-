package com.puneet.friday.voice

internal object AudioCapturePolicy {
    fun commandDetector(afterWake: Boolean): SpeechEndDetector =
        if (afterWake) SpeechEndDetector(calibrationFrames = 4, inputGain = WAKE_SPEECH_GAIN)
        else SpeechEndDetector()

    fun hasCommandEnergy(processor: PcmProcessor, samples: ShortArray, afterWake: Boolean): Boolean =
        processor.hasSpeech(samples, inputGain = if (afterWake) WAKE_SPEECH_GAIN else 1f)

    fun canStopAfterSilence(afterWake: Boolean, sampleCount: Int, answerReply: Boolean = false): Boolean = when {
        answerReply -> sampleCount >= MIN_REPLY_LISTEN_SAMPLES
        afterWake -> sampleCount >= MIN_WAKE_LISTEN_SAMPLES
        else -> true
    }

    fun acceptsManualStop(sampleCount: Int): Boolean = sampleCount >= MIN_MANUAL_STOP_SAMPLES

    fun reviewedStartSample(samples: ShortArray, sampleCount: Int, startSample: Int, processor: PcmProcessor): Int {
        val count = sampleCount.coerceIn(0, samples.size)
        val start = startSample.coerceIn(0, count)
        val frameSamples = WavEncoder.SAMPLE_RATE / 50
        val earliest = (start - VOICED_ONSET_LOOKBACK_SAMPLES).coerceAtLeast(0)
        var offset = start / frameSamples * frameSamples
        var recoveredStart = start
        var voicedFrames = 0
        var gapFrames = 0
        while (offset - frameSamples >= earliest) {
            val frameStart = offset - frameSamples
            val level = processor.analyze(samples.copyOfRange(frameStart, offset))
            if (level.rms >= 0.0025f && level.voicing >= 0.55f) {
                recoveredStart = frameStart
                voicedFrames++
                gapFrames = 0
            } else if (++gapFrames >= 4) {
                break
            }
            offset = frameStart
        }
        return if (voicedFrames >= 4) (recoveredStart - VOICED_ONSET_PADDING_SAMPLES).coerceAtLeast(earliest) else start
    }

    fun reviewedSampleCount(
        sampleCount: Int,
        manualStop: Boolean,
        samples: ShortArray? = null,
        processor: PcmProcessor? = null,
        hasClearCommand: Boolean = false,
    ): Int {
        val count = sampleCount.coerceAtLeast(0).coerceAtMost(samples?.size ?: Int.MAX_VALUE)
        if (!manualStop || !hasClearCommand || samples == null || processor == null) return count
        val frameSamples = WavEncoder.SAMPLE_RATE / 50
        val start = ((count - MANUAL_STOP_REVIEW_SAMPLES).coerceAtLeast(0) / frameSamples) * frameSamples
        var quietFrames = 0
        var tapStart = -1
        var tapFrames = 0
        var impulse = false
        var offset = start
        while (offset < count) {
            val end = minOf(count, offset + frameSamples)
            val frame = samples.copyOfRange(offset, end)
            val level = processor.analyze(frame)
            if (level.rms < 0.009f) {
                quietFrames++
            } else if (level.voicing < 0.20f) {
                if (tapStart < 0 && quietFrames >= 4) { tapStart = offset; tapFrames = 0; impulse = false }
                if (tapStart >= 0) {
                    tapFrames++
                    val peak = frame.maxOf { kotlin.math.abs(it.toInt()) }
                    impulse = impulse || peak >= maxOf(1_500f, level.rms * Short.MAX_VALUE * 6f)
                    if (tapFrames > 3) { tapStart = -1; impulse = false }
                }
                quietFrames = 0
            } else {
                tapStart = -1
                tapFrames = 0
                impulse = false
                quietFrames = 0
            }
            offset = end
        }
        return if (tapStart >= 0 && impulse) tapStart else count
    }

    private const val MIN_MANUAL_STOP_SAMPLES = WavEncoder.SAMPLE_RATE * 1_500 / 1_000
    private const val WAKE_SPEECH_GAIN = 1.6f
    private const val MIN_WAKE_LISTEN_SAMPLES = WavEncoder.SAMPLE_RATE * 2
    private const val MIN_REPLY_LISTEN_SAMPLES = WavEncoder.SAMPLE_RATE * 5
    private const val MANUAL_STOP_REVIEW_SAMPLES = WavEncoder.SAMPLE_RATE * 360 / 1_000
    private const val VOICED_ONSET_LOOKBACK_SAMPLES = WavEncoder.SAMPLE_RATE
    private const val VOICED_ONSET_PADDING_SAMPLES = WavEncoder.SAMPLE_RATE * 80 / 1_000
}
