package com.example.assistant.ui

/** Allocation-free helpers shared by the orb renderer and JVM checks. */
internal object OrbMotionMath {
    fun noiseThreshold(noiseFloor: Float): Float {
        val safeFloor = finiteLevel(noiseFloor).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        return maxOf(MIN_VOICE_RMS, safeFloor * NOISE_MULTIPLIER + NOISE_OFFSET_RMS)
    }

    fun voiceEnergy(rms: Float, noiseFloor: Float): Float {
        val threshold = noiseThreshold(noiseFloor)
        val normalized = ((finiteLevel(rms) - threshold) / VOICE_RANGE_RMS).coerceIn(0f, 1f)
        return normalized * normalized * (3f - 2f * normalized)
    }

    fun adaptNoiseFloor(current: Float, rms: Float, rate: Float): Float {
        val safeCurrent = finiteLevel(current).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        val sample = finiteLevel(rms).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        return safeCurrent + (sample - safeCurrent) * rate.coerceIn(0f, 1f)
    }

    private fun finiteLevel(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(0f) else 0f

    const val DEFAULT_NOISE_FLOOR = 0.004f
    private const val MIN_NOISE_FLOOR = 0.001f
    private const val MAX_NOISE_FLOOR = 0.03f
    private const val MIN_VOICE_RMS = 0.011f
    private const val NOISE_MULTIPLIER = 1.65f
    private const val NOISE_OFFSET_RMS = 0.0025f
    private const val VOICE_RANGE_RMS = 0.065f
}
