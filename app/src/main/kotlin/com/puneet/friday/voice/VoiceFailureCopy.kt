package com.puneet.friday.voice

object VoiceFailureCopy {
    fun forOutcome(outcome: VoiceUiOutcome): String = when (outcome) {
        VoiceUiOutcome.BUSY -> "Friday is busy. Try again."
        VoiceUiOutcome.TIMEOUT -> "The server took too long. Try again."
        VoiceUiOutcome.BAD_AUDIO -> "I didn't hear that clearly"
        VoiceUiOutcome.UNAUTHORIZED -> "Friday couldn't start. Try again."
        VoiceUiOutcome.ACTION, VoiceUiOutcome.UNSUPPORTED -> "Friday couldn't finish that. Try again."
    }

    fun forConnectionFailure(reason: String?, offline: Boolean): String = when {
        offline -> "No internet"
        reason == "registration_failed" || reason == "registration_invalid" ->
            "Friday couldn't start. Try again."
        else -> "Couldn't reach Friday. Try again."
    }
}
