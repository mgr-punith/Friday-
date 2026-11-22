package com.puneet.friday.voice

import java.util.concurrent.atomic.AtomicLong

/** Rejects callbacks from cancelled or replaced utterances, including engine startup. */
internal class SpeechSession {
    private var generation = 0L
    private var active = false
    val isActive: Boolean get() = active

    fun begin(): String {
        generation = nextGeneration.incrementAndGet()
        active = true
        return "friday_confirmation_$generation"
    }

    fun accepts(id: String?): Boolean = active && id == "friday_confirmation_$generation"

    fun finish(id: String?): Boolean {
        if (!accepts(id)) return false
        active = false
        return true
    }

    fun cancel() { active = false }

    private companion object { val nextGeneration = AtomicLong() }
}
