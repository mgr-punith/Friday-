package com.puneet.friday.actions

import kotlin.math.roundToInt

internal class VolumeRange(private val minimum: Int, private val maximum: Int) {
    private val span = maximum - minimum

    fun levelFor(percent: Int): Int = minimum + stepsFor(percent)

    fun adjustedLevel(current: Int, direction: VolumeDirection, percent: Int): Int {
        val steps = stepsFor(percent)
        val level = current.coerceIn(minimum, maximum)
        val target = if (direction == VolumeDirection.UP) level + steps else level - steps
        return target.coerceIn(minimum, maximum)
    }

    fun percentAt(level: Int): Int = ((level.coerceIn(minimum, maximum) - minimum) * 100.0 / span).roundToInt()

    private fun stepsFor(percent: Int): Int = (span * percent.toDouble() / 100.0).roundToInt()
}
