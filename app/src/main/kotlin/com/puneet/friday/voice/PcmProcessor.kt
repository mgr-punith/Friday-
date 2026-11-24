package com.puneet.friday.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class PcmProcessor(
    private val sampleRate: Int = WavEncoder.SAMPLE_RATE,
) {
    data class Level(
        val amplitude: Float,
        val low: Float,
        val mid: Float,
        val high: Float,
        val rms: Float = 0f,
        val voicing: Float = 0f,
    )

    fun analyze(samples: ShortArray, count: Int = samples.size, analyzeVoicing: Boolean = true): Level {
        val safeCount = count.coerceIn(0, samples.size)
        if (safeCount == 0) return Level(0f, 0f, 0f, 0f)
        var sumSquares = 0.0
        var lowDelta = 0.0
        var highDelta = 0.0
        var previous = samples[0].toInt()
        for (index in 0 until safeCount) {
            val value = samples[index].toInt()
            sumSquares += value.toDouble() * value
            if (index > 0) {
                val delta = abs(value - previous).toDouble()
                highDelta += delta
                lowDelta += abs(value + previous).toDouble() * 0.5
            }
            previous = value
        }
        val rms = sqrt(sumSquares / safeCount) / Short.MAX_VALUE
        val amplitude = normalizeLevel(rms * 5.2)
        val high = normalizeLevel((highDelta / max(1, safeCount - 1)) / Short.MAX_VALUE * 3.5)
        val low = normalizeLevel((lowDelta / max(1, safeCount - 1)) / Short.MAX_VALUE * 2.3)
        val mid = normalizeLevel(amplitude * 1.15 - low * 0.28 - high * 0.18)
        return Level(amplitude, low, mid, high, rms.toFloat(), if (analyzeVoicing) voicedCorrelation(samples, safeCount) else 0f)
    }

    fun crop(samples: ShortArray, count: Int, start: Int, endExclusive: Int): ShortArray {
        val safeCount = count.coerceIn(0, samples.size)
        val safeStart = start.coerceIn(0, safeCount)
        val safeEnd = endExclusive.coerceIn(safeStart, safeCount)
        return samples.copyOfRange(safeStart, safeEnd)
    }

    fun normalizeForRecognition(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        var sum = 0L
        for (sample in samples) sum += sample.toLong()
        val mean = sum.toDouble() / samples.size
        var centeredPeak = 0.0
        for (sample in samples) centeredPeak = max(centeredPeak, abs(sample - mean))
        if (centeredPeak < 1.0) return samples.copyOf()
        val gain = (TARGET_PEAK / centeredPeak).coerceIn(MIN_GAIN, MAX_GAIN)
        return ShortArray(samples.size) { index ->
            ((samples[index] - mean) * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun trim(samples: ShortArray, count: Int = samples.size): ShortArray {
        val safeCount = count.coerceIn(0, samples.size)
        if (safeCount == 0) return ShortArray(0)
        val threshold = max(240, peak(samples, safeCount) / 24)
        var first = 0
        while (first < safeCount && abs(samples[first].toInt()) < threshold) first++
        if (first == safeCount) return ShortArray(0)
        var last = safeCount - 1
        while (last > first && abs(samples[last].toInt()) < threshold) last--
        val padding = sampleRate / 20
        first = (first - padding).coerceAtLeast(0)
        last = (last + padding).coerceAtMost(safeCount - 1)
        return samples.copyOfRange(first, last + 1)
    }

    fun hasSpeech(samples: ShortArray, inputGain: Float = 1f): Boolean {
        if (samples.size < sampleRate / 5) return false
        // The frame detector already checks voicing; this final check needs energy only.
        val level = analyze(samples, analyzeVoicing = false)
        if (level.amplitude * inputGain < MIN_OVERALL_AMPLITUDE || peak(samples, samples.size) * inputGain < MIN_PEAK) {
            return false
        }
        val windowSize = max(1, sampleRate / WINDOWS_PER_SECOND)
        var activeWindows = 0
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(windowSize, samples.size - offset)
            var sumSquares = 0.0
            for (index in offset until offset + count) {
                val value = samples[index].toDouble()
                sumSquares += value * value
            }
            val rms = sqrt(sumSquares / count) / Short.MAX_VALUE
            if (rms * inputGain >= ACTIVE_WINDOW_RMS) activeWindows++
            offset += count
        }
        return activeWindows >= MIN_ACTIVE_WINDOWS
    }

    private fun peak(samples: ShortArray, count: Int): Int {
        var peak = 0
        for (index in 0 until count) peak = max(peak, abs(samples[index].toInt()))
        return peak
    }

    /** Maximum normalized autocorrelation in the adult speech pitch range. */
    private fun voicedCorrelation(samples: ShortArray, count: Int): Float {
        if (count < 80) return 0f
        var mean = 0.0
        for (index in 0 until count) mean += samples[index]
        mean /= count
        var energy = 0.0
        for (index in 0 until count) {
            val centered = samples[index] - mean
            energy += centered * centered
        }
        if (energy < MIN_VOICING_ENERGY) return 0f
        val minimumLag = max(2, sampleRate / MAX_VOICE_PITCH_HZ)
        val maximumLag = minOf(count / 2, sampleRate / MIN_VOICE_PITCH_HZ)
        if (minimumLag > maximumLag) return 0f
        var best = 0.0
        var lag = minimumLag
        while (lag <= maximumLag) {
            var product = 0.0
            var firstEnergy = 0.0
            var secondEnergy = 0.0
            for (index in lag until count) {
                val first = samples[index] - mean
                val second = samples[index - lag] - mean
                product += first * second
                firstEnergy += first * first
                secondEnergy += second * second
            }
            val denominator = sqrt(firstEnergy * secondEnergy)
            if (denominator > 0.0) best = max(best, product / denominator)
            lag += VOICING_LAG_STEP
        }
        return best.coerceIn(0.0, 1.0).toFloat()
    }

    private fun normalizeLevel(value: Double): Float = value.coerceIn(0.0, 1.0).toFloat()

    private companion object {
        const val MIN_OVERALL_AMPLITUDE = 0.025f
        const val MIN_PEAK = 700
        const val WINDOWS_PER_SECOND = 50
        const val ACTIVE_WINDOW_RMS = 0.009
        const val MIN_ACTIVE_WINDOWS = 5
        const val TARGET_PEAK = 22_000.0
        const val MIN_GAIN = 0.8
        const val MAX_GAIN = 4.0
        const val MIN_VOICE_PITCH_HZ = 70
        const val MAX_VOICE_PITCH_HZ = 350
        const val VOICING_LAG_STEP = 3
        const val MIN_VOICING_ENERGY = 1_000_000.0
    }
}
