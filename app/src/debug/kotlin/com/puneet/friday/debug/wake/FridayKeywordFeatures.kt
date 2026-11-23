package com.puneet.friday.debug.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Keeps the model's last 144 log-mel frames. One new frame arrives every 10 ms. */
internal class FridayKeywordFeatures(private val melBank: FloatArray) {
    private val pcm = FloatArray(FRAME)
    private var pcmIndex = 0
    private var samplesUntilFrame = HOP
    private var framesUntilScore = 1
    private var hopEnergy = 0f
    private val recentEnergy = FloatArray(65)
    private var energyIndex = 0
    private val hann = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
    private val reversed = IntArray(FFT) { Integer.reverse(it) ushr 23 }
    private val cosines = DoubleArray(FFT / 2) { cos(2 * PI * it / FFT) }
    private val sines = DoubleArray(FFT / 2) { -sin(2 * PI * it / FFT) }
    private val real = DoubleArray(FFT)
    private val imaginary = DoubleArray(FFT)
    private val power = FloatArray(BINS)
    private val mel = FloatArray(FRAMES * BANDS) { LOG_FLOOR }
    private val bandStart = IntArray(BANDS) { band ->
        (0 until BINS).first { melBank[band * BINS + it] > 0f }
    }
    private val bandEnd = IntArray(BANDS) { band ->
        (BINS - 1 downTo 0).first { melBank[band * BINS + it] > 0f } + 1
    }
    private var melIndex = 0
    val input = FloatArray(FRAMES * BANDS)
    var hasRecentSound = false
        private set

    fun append(sample: Short): Boolean {
        val normalized = sample / 32767f
        pcm[pcmIndex] = normalized
        hopEnergy += normalized * normalized
        if (++pcmIndex == FRAME) pcmIndex = 0
        if (--samplesUntilFrame > 0) return false
        samplesUntilFrame = HOP
        recentEnergy[energyIndex] = hopEnergy
        if (++energyIndex == recentEnergy.size) energyIndex = 0
        hopEnergy = 0f
        addMelFrame()
        if (--framesUntilScore > 0) return false
        framesUntilScore = 5
        val peak = mel.max()
        hasRecentSound = peak > LOG_FLOOR && hasAudibleInput()
        if (hasRecentSound) {
            for (band in 0 until BANDS) {
                var source = melIndex
                val target = band * FRAMES
                for (frame in 0 until FRAMES) {
                    input[target + frame] = maxOf(-1f, (mel[source * BANDS + band] - peak) / 7f)
                    if (++source == FRAMES) source = 0
                }
            }
        }
        return true
    }

    private fun hasAudibleInput(): Boolean {
        // Quiet speech can fall below the usual floor. Require both audible
        // frames and changes in level before scoring that quieter input.
        if (recentEnergy.count { it >= NORMAL_ENERGY } >= MIN_AUDIBLE_HOPS) return true
        if (recentEnergy.count { it >= QUIET_ENERGY } < MIN_AUDIBLE_HOPS) return false
        val quieterLevel = recentEnergy.max() / 6f
        return recentEnergy.count { it <= quieterLevel } >= 13
    }

    private fun addMelFrame() {
        val offset = melIndex * BANDS
        if (++melIndex == FRAMES) melIndex = 0
        if (pcm.all { it == 0f }) {
            mel.fill(LOG_FLOOR, offset, offset + BANDS)
            return
        }
        real.fill(0.0)
        imaginary.fill(0.0)
        var sample = pcmIndex
        for (index in 0 until FRAME) {
            real[reversed[index]] = pcm[sample] * hann[index]
            if (++sample == FRAME) sample = 0
        }
        fft()
        for (bin in 0 until BINS) {
            power[bin] = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]).toFloat()
        }
        for (band in 0 until BANDS) {
            var energy = 0f
            for (bin in bandStart[band] until bandEnd[band]) energy += power[bin] * melBank[band * BINS + bin]
            mel[offset + band] = log10(maxOf(1e-8f, energy))
        }
    }

    private fun fft() {
        var half = 1
        while (half < FFT) {
            val width = half * 2
            val step = FFT / width
            for (start in 0 until FFT step width) for (offset in 0 until half) {
                val low = start + offset
                val high = low + half
                val angle = offset * step
                val realPart = real[high] * cosines[angle] - imaginary[high] * sines[angle]
                val imaginaryPart = real[high] * sines[angle] + imaginary[high] * cosines[angle]
                real[high] = real[low] - realPart
                imaginary[high] = imaginary[low] - imaginaryPart
                real[low] += realPart
                imaginary[low] += imaginaryPart
            }
            half = width
        }
    }

    companion object {
        private const val FRAME = 400
        private const val HOP = 160
        private const val FRAMES = 144
        private const val FFT = 512
        private const val LOG_FLOOR = -8f
        private const val MIN_AUDIBLE_HOPS = 6
        private const val NORMAL_ENERGY = 0.0004f // −56 dBFS RMS over 160 samples.
        private const val QUIET_ENERGY = 0.0001f // −62 dBFS RMS over 160 samples.
        const val BINS = FFT / 2 + 1
        const val BANDS = 24
    }
}
