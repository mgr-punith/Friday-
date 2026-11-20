package com.example.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.example.assistant.DebugTools
import kotlin.math.max

class VoiceRecorder(private val context: Context) {
    @Volatile private var stopRequested = false
    @Volatile private var activeRecorder: AudioRecord? = null

    fun stop() {
        stopRequested = true
        try {
            activeRecorder?.stop()
        } catch (_: IllegalStateException) {
            Unit
        }
    }

    @Throws(SecurityException::class, IllegalStateException::class)
    fun record(
        onReady: () -> Unit = {},
        onLevel: (PcmProcessor.Level) -> Unit,
    ): CaptureResult {
        DebugTools.beginCommandCapture()
        try {
            return recordCommand(onReady, onLevel)
        } finally {
            DebugTools.endCommandCapture()
        }
    }

    private fun recordCommand(onReady: () -> Unit, onLevel: (PcmProcessor.Level) -> Unit): CaptureResult {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("microphone_permission_missing")
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minimum = AudioRecord.getMinBufferSize(
            WavEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "audio_record_unavailable" }
        val bufferSize = max(minimum, FRAME_SAMPLES * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WavEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "audio_record_unavailable"
        }
        val processor = PcmProcessor()
        val samples = ShortArray(WavEncoder.SAMPLE_RATE * MAX_SECONDS)
        val frame = ShortArray(FRAME_SAMPLES)
        val stopDetector = SpeechEndDetector()
        var readySent = false
        var count = 0
        try {
            activeRecorder = recorder
            recorder.startRecording()
            if (stopRequested) return CaptureResult.NoClearCommand
            while (!stopRequested && count < samples.size) {
                val read = try {
                    recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                } catch (_: IllegalStateException) {
                    break
                }
                if (read <= 0) break
                val level = processor.analyze(frame, read)
                onLevel(level)
                frame.copyInto(samples, count, 0, read)
                count += read
                val shouldStop = stopDetector.add(level)
                if (!readySent && stopDetector.isCalibrated) {
                    readySent = true
                    onReady()
                }
                if (shouldStop) break
            }
        } finally {
            activeRecorder = null
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: IllegalStateException) {
                Unit
            }
            recorder.release()
        }
        val manualStop = stopRequested && !stopDetector.endedBySilence
        if (manualStop && !AudioCapturePolicy.acceptsManualStop(count)) return CaptureResult.NoClearCommand
        val reviewedCount = AudioCapturePolicy.reviewedSampleCount(count, manualStop)
        val reviewedDetector = if (manualStop) {
            reviewCapture(samples, reviewedCount, processor)
        } else {
            stopDetector
        }
        if (!reviewedDetector.hasClearCommand) return CaptureResult.NoClearCommand
        if (count >= samples.size && !reviewedDetector.endedBySilence) return CaptureResult.NoClearCommand
        val start = reviewedDetector.firstSpeechFrame * FRAME_SAMPLES - PRE_ROLL_SAMPLES
        val end = (reviewedDetector.lastSpeechFrame + 1) * FRAME_SAMPLES + POST_ROLL_SAMPLES
        val cropped = processor.crop(samples, reviewedCount, start, end)
        if (!processor.hasSpeech(cropped)) return CaptureResult.NoClearCommand
        val normalized = processor.normalizeForRecognition(cropped)
        return CaptureResult.Command(
            RecordedCommand(WavEncoder.encode(normalized), normalized.size * 1_000L / WavEncoder.SAMPLE_RATE),
        )
    }

    private fun reviewCapture(samples: ShortArray, count: Int, processor: PcmProcessor): SpeechEndDetector {
        val detector = SpeechEndDetector()
        val frame = ShortArray(FRAME_SAMPLES)
        var offset = 0
        while (offset < count) {
            val frameCount = minOf(FRAME_SAMPLES, count - offset)
            samples.copyInto(frame, 0, offset, offset + frameCount)
            if (frameCount < frame.size) frame.fill(0, frameCount)
            detector.add(processor.analyze(frame, frameCount))
            offset += frameCount
        }
        return detector
    }

    private companion object {
        const val MAX_SECONDS = 10
        const val FRAME_SAMPLES = 320
        const val PRE_ROLL_SAMPLES = WavEncoder.SAMPLE_RATE * 300 / 1_000
        const val POST_ROLL_SAMPLES = WavEncoder.SAMPLE_RATE * 320 / 1_000
    }
}

sealed interface CaptureResult {
    data class Command(val recording: RecordedCommand) : CaptureResult
    data object NoClearCommand : CaptureResult
}

class SpeechEndDetector(
    private val minimumSpeechFrames: Int = 4,
    private val silenceFramesToStop: Int = 42,
    initialNoiseFloor: Float = 0.003f,
    private val calibrationFrames: Int = 12,
    private val minimumCommandFrames: Int = 20,
    private val minimumDynamicFrames: Int = 6,
    private val minimumStrongFrames: Int = 10,
    private val minimumVoicedFrames: Int = minOf(6, minimumCommandFrames),
    private val minimumVoicedPercent: Int = 35,
) {
    private var candidateSpeechFrames = 0
    private var candidateStrongFrames = 0
    private var candidateVoicedFrames = 0
    private var candidateStartFrame = 0
    private var silenceFrames = 0
    private var frameIndex = 0
    private var speechFrames = 0
    private var strongFrames = 0
    private var dynamicFrames = 0
    private var voicedFrames = 0
    private var previousSpeechLevel = 0f
    private var noiseFloor = initialNoiseFloor.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
    var hasSpeech: Boolean = false
        private set
    var firstSpeechFrame: Int = 0
        private set
    var lastSpeechFrame: Int = 0
        private set
    var endedBySilence: Boolean = false
        private set
    val hasClearCommand: Boolean
        get() = hasSpeech && speechFrames >= minimumCommandFrames &&
            strongFrames >= minimumStrongFrames && dynamicFrames >= minimumDynamicFrames &&
            voicedFrames >= minimumVoicedFrames &&
            voicedFrames * 100 >= speechFrames * minimumVoicedPercent
    val isCalibrated: Boolean
        get() = frameIndex >= calibrationFrames

    fun add(level: PcmProcessor.Level): Boolean = addFrame(level.rms, level.voicing)

    /** Tests that exercise energy-only behavior treat accepted frames as voiced. */
    fun add(rms: Float): Boolean = addFrame(rms, 1f)

    private fun addFrame(rms: Float, voicing: Float): Boolean {
        val level = if (rms.isFinite()) rms.coerceAtLeast(0f) else 0f
        val voiced = voicing.isFinite() && voicing >= MIN_VOICING_CORRELATION
        val currentFrame = frameIndex++
        if (currentFrame < calibrationFrames) {
            updateNoiseFloor(level, CALIBRATION_ADAPT_RATE)
            return false
        }
        if (!hasSpeech) {
            val startThreshold = max(ABSOLUTE_START_RMS, noiseFloor * START_NOISE_MULTIPLIER + START_OFFSET_RMS)
            val strongThreshold = max(ABSOLUTE_STRONG_RMS, startThreshold)
            if (level >= startThreshold || level >= LOUD_SPEECH_RMS) {
                if (candidateSpeechFrames == 0) {
                    candidateStartFrame = currentFrame
                    candidateStrongFrames = 0
                    candidateVoicedFrames = 0
                }
                candidateSpeechFrames++
                if (level >= strongThreshold) candidateStrongFrames++
                if (voiced) candidateVoicedFrames++
                if (candidateSpeechFrames >= minimumSpeechFrames) {
                    hasSpeech = true
                    firstSpeechFrame = candidateStartFrame
                    lastSpeechFrame = currentFrame
                    speechFrames = candidateSpeechFrames
                    strongFrames = candidateStrongFrames
                    dynamicFrames = 0
                    voicedFrames = candidateVoicedFrames
                    previousSpeechLevel = level
                    silenceFrames = 0
                }
            } else {
                candidateSpeechFrames = 0
                candidateStrongFrames = 0
                candidateVoicedFrames = 0
                updateNoiseFloor(level, NOISE_ADAPT_RATE)
            }
        } else {
            val strongThreshold = max(
                ABSOLUTE_STRONG_RMS,
                noiseFloor * START_NOISE_MULTIPLIER + START_OFFSET_RMS,
            )
            val continueThreshold = max(
                ABSOLUTE_CONTINUE_RMS,
                noiseFloor * CONTINUE_NOISE_MULTIPLIER + CONTINUE_OFFSET_RMS,
            )
            if (level >= continueThreshold) {
                lastSpeechFrame = currentFrame
                speechFrames++
                if (level >= strongThreshold) strongFrames++
                if (voiced) voicedFrames++
                if (previousSpeechLevel > 0f && kotlin.math.abs(level - previousSpeechLevel) >= DYNAMIC_RMS_DELTA) {
                    dynamicFrames++
                }
                previousSpeechLevel = level
                silenceFrames = 0
            } else {
                silenceFrames++
            }
        }
        endedBySilence = hasSpeech && silenceFrames >= silenceFramesToStop
        return endedBySilence
    }

    private fun updateNoiseFloor(level: Float, rate: Float) {
        val bounded = level.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        noiseFloor += (bounded - noiseFloor) * rate
    }

    private companion object {
        const val MIN_NOISE_FLOOR = 0.001f
        const val MAX_NOISE_FLOOR = 0.025f
        const val CALIBRATION_ADAPT_RATE = 0.25f
        const val NOISE_ADAPT_RATE = 0.08f
        const val ABSOLUTE_START_RMS = 0.009f
        const val ABSOLUTE_STRONG_RMS = 0.014f
        const val ABSOLUTE_CONTINUE_RMS = 0.009f
        const val LOUD_SPEECH_RMS = 0.03f
        const val START_NOISE_MULTIPLIER = 1.9f
        const val CONTINUE_NOISE_MULTIPLIER = 1.6f
        const val START_OFFSET_RMS = 0.003f
        const val CONTINUE_OFFSET_RMS = 0.003f
        const val DYNAMIC_RMS_DELTA = 0.0015f
        const val MIN_VOICING_CORRELATION = 0.38f
    }
}
