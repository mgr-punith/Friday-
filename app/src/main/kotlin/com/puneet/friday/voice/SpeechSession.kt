package com.puneet.friday.voice

/** Rejects callbacks from cancelled or replaced utterances, including engine startup. */
internal class SpeechSession {
    private var generation = 0L
    private var active = false

    fun begin(): String {
        generation++
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
}
