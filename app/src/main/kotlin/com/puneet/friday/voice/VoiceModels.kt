package com.puneet.friday.voice

import com.puneet.friday.actions.PhoneAction

data class RecordedCommand(
    val wavBytes: ByteArray,
    val durationMs: Long,
)

sealed interface VoiceCommandResponse {
    data class Action(val action: PhoneAction, val serverDurationMs: Double?) : VoiceCommandResponse
    data class Answer(val answer: InformationAnswer) : VoiceCommandResponse
    data class AnswerSetup(val reason: AnswerSetupReason) : VoiceCommandResponse
    data object Unsupported : VoiceCommandResponse
    data class Error(val reason: Reason) : VoiceCommandResponse

    enum class Reason {
        BUSY,
        TIMEOUT,
        BAD_AUDIO,
        UNAUTHORIZED,
    }

    enum class AnswerSetupReason { DISABLED, UNAVAILABLE }
}

data class CommandTimings(
    val uploadMs: Long,
    val serverMs: Double?,
)

data class CommandResult(
    val response: VoiceCommandResponse,
    val timings: CommandTimings,
)

enum class VoiceUiOutcome {
    ACTION,
    ANSWER,
    ANSWERS_DISABLED,
    ANSWERS_UNAVAILABLE,
    UNSUPPORTED,
    BUSY,
    TIMEOUT,
    BAD_AUDIO,
    UNAUTHORIZED,
}

fun VoiceCommandResponse.uiOutcome(): VoiceUiOutcome = when (this) {
    is VoiceCommandResponse.Action -> VoiceUiOutcome.ACTION
    is VoiceCommandResponse.Answer -> VoiceUiOutcome.ANSWER
    is VoiceCommandResponse.AnswerSetup -> when (reason) {
        VoiceCommandResponse.AnswerSetupReason.DISABLED -> VoiceUiOutcome.ANSWERS_DISABLED
        VoiceCommandResponse.AnswerSetupReason.UNAVAILABLE -> VoiceUiOutcome.ANSWERS_UNAVAILABLE
    }
    VoiceCommandResponse.Unsupported -> VoiceUiOutcome.UNSUPPORTED
    is VoiceCommandResponse.Error -> when (reason) {
        VoiceCommandResponse.Reason.BUSY -> VoiceUiOutcome.BUSY
        VoiceCommandResponse.Reason.TIMEOUT -> VoiceUiOutcome.TIMEOUT
        VoiceCommandResponse.Reason.BAD_AUDIO -> VoiceUiOutcome.BAD_AUDIO
        VoiceCommandResponse.Reason.UNAUTHORIZED -> VoiceUiOutcome.UNAUTHORIZED
    }
}

object VoicePayload {
    const val MAX_WAV_BYTES = 1_000_000

    fun isValid(wavBytes: ByteArray): Boolean =
        wavBytes.size <= MAX_WAV_BYTES && WavEncoder.isFridayWav(wavBytes)
}

sealed interface VoiceFailure {
    data object NoInternet : VoiceFailure
    data object TryAgain : VoiceFailure
    data object NoSpeech : VoiceFailure
    data object Busy : VoiceFailure
}
