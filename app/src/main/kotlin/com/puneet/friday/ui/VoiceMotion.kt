package com.puneet.friday.ui

import kotlin.math.abs
import kotlin.math.exp

/** Audio envelope shared by the sphere and ribbon renderers. */
internal class VoiceMotion {
    var energy = 0f
        private set
    var low = 0f
        private set
    var mid = 0f
        private set
    var high = 0f
        private set
    private var targetEnergy = 0f
    private var targetLow = 0f
    private var targetMid = 0f
    private var targetHigh = 0f

    fun setTarget(energy: Float, low: Float, mid: Float, high: Float) {
        targetEnergy = unit(energy)
        targetLow = unit(low)
        targetMid = unit(mid)
        targetHigh = unit(high)
    }

    fun advance(seconds: Float) {
        val delta = if (seconds.isFinite()) seconds.coerceIn(0f, 0.1f) else 0f
        energy = approach(energy, targetEnergy, delta)
        low = approach(low, targetLow, delta)
        mid = approach(mid, targetMid, delta)
        high = approach(high, targetHigh, delta)
    }

    fun reset() {
        setTarget(0f, 0f, 0f, 0f)
        energy = 0f
        low = 0f
        mid = 0f
        high = 0f
    }

    private fun approach(value: Float, target: Float, delta: Float): Float {
        val next = value + (target - value) * (1f - exp(-delta / if (target > value) 0.055f else 0.080f))
        return if (abs(target - next) < 0.005f) target else next
    }

    private fun unit(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
