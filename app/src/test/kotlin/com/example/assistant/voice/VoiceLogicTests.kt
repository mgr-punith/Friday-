package com.example.assistant.voice

import com.example.assistant.actions.PhoneAction
import com.example.assistant.actions.RingerMode
import com.example.assistant.ui.OrbMotionMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.sin

/** Dependency-free JVM checks for Friday's audio and backend contract. */
object VoiceLogicTests {
    @JvmStatic
    fun main(args: Array<String>) {
        var checks = 0
        fun expect(condition: Boolean, description: String) {
            check(condition) { description }
            checks++
        }
        fun parsedAction(name: String, fields: Map<String, Any?>): VoiceCommandResponse.Action {
            val response = VoiceResponseParser.parseFields("action", name, fields)
            expect(response is VoiceCommandResponse.Action, "$name parses as an action")
            return response as VoiceCommandResponse.Action
        }
        fun rejects(name: String, fields: Map<String, Any?>?) {
            expect(
                VoiceResponseParser.parseFields("action", name, fields) ==
                    VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO),
                "$name rejects malformed arguments",
            )
        }

        val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1400, -2200)
        val wav = WavEncoder.encode(samples)
        expect(wav.size == 44 + samples.size * 2, "WAV length includes one canonical header")
        expect(String(wav, 0, 4, Charsets.US_ASCII) == "RIFF", "WAV starts with RIFF")
        expect(String(wav, 8, 4, Charsets.US_ASCII) == "WAVE", "WAV identifies the WAVE format")
        expect(String(wav, 12, 4, Charsets.US_ASCII) == "fmt ", "WAV has a PCM format chunk")
        expect(String(wav, 36, 4, Charsets.US_ASCII) == "data", "WAV has a data chunk")
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        expect(header.getInt(24) == 16_000, "WAV sample rate is 16 kHz")
        expect(header.getShort(22).toInt() == 1, "WAV is mono")
        expect(header.getShort(34).toInt() == 16, "WAV uses 16-bit samples")
        expect(header.getInt(40) == samples.size * 2, "WAV data size matches the samples")
        expect(WavEncoder.isFridayWav(wav), "Friday accepts its generated WAV")
        val wrongRate = wav.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 8_000) }
        expect(!WavEncoder.isFridayWav(wrongRate), "Friday rejects an 8 kHz WAV")
        val wrongChannels = wav.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(22, 2) }
        expect(!WavEncoder.isFridayWav(wrongChannels), "Friday rejects stereo WAV")
        val wrongLength = wav.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 2) }
        expect(!WavEncoder.isFridayWav(wrongLength), "Friday rejects a mismatched data length")
        expect(!WavEncoder.isFridayWav(ByteArray(43)), "Friday rejects a short WAV")
        expect(VoicePayload.isValid(wav), "A canonical small WAV is uploadable")
        expect(!VoicePayload.isValid(ByteArray(VoicePayload.MAX_WAV_BYTES + 1)), "The app rejects oversized uploads")
        expect(!AudioCapturePolicy.acceptsManualStop(16_000), "A one-second manual stop stays local")
        expect(AudioCapturePolicy.acceptsManualStop(24_000), "A 1.5-second manual stop can contain a command")
        expect(
            AudioCapturePolicy.reviewedSampleCount(24_000, manualStop = true) == 18_240,
            "Manual stop review removes the final tap window",
        )
        expect(
            AudioCapturePolicy.reviewedSampleCount(24_000, manualStop = false) == 24_000,
            "Natural silence keeps the complete capture",
        )

        val pcm = PcmProcessor(sampleRate = 100)
        val padded = ShortArray(100).also {
            for (index in 40..59) it[index] = if (index % 2 == 0) 5_000 else -5_000
        }
        val trimmed = pcm.trim(padded)
        expect(trimmed.size == 30, "Silence trim keeps 50 ms of padding on both sides")
        expect(trimmed.first() == 0.toShort() && trimmed.last() == 0.toShort(), "Trim padding retains boundary silence")
        expect(pcm.trim(ShortArray(100)).isEmpty(), "Pure silence trims to no samples")
        expect(pcm.trim(shortArrayOf(1, 2), 10).size <= 2, "Trim bounds an oversized sample count")
        expect(!pcm.hasSpeech(ShortArray(19)), "Audio shorter than 200 ms is not speech")
        expect(!pcm.hasSpeech(ShortArray(100)), "Silence is not speech")
        expect(pcm.hasSpeech(padded), "Clear alternating speech energy is detected")
        val isolatedNoise = ShortArray(100).also { it[50] = 20_000 }
        expect(!pcm.hasSpeech(isolatedNoise), "One loud transient is not speech")
        val emptyLevel = pcm.analyze(ShortArray(0), 5)
        expect(emptyLevel == PcmProcessor.Level(0f, 0f, 0f, 0f), "Empty audio produces zero levels")
        val tone = ShortArray(320) { index -> (sin(2.0 * PI * index / 16.0) * 9_000).toInt().toShort() }
        val toneLevel = PcmProcessor().analyze(tone)
        expect(toneLevel.amplitude in 0.1f..1f, "Speech-level PCM produces normalized amplitude")
        expect(toneLevel.low in 0f..1f && toneLevel.mid in 0f..1f && toneLevel.high in 0f..1f, "Spectrum levels stay normalized")
        expect(toneLevel.rms in 0.05f..0.3f, "Analyzer exposes raw RMS for speech detection")
        val voicedTone = ShortArray(320) { index -> (sin(2.0 * PI * index / 100.0) * 9_000).toInt().toShort() }
        expect(PcmProcessor().analyze(voicedTone).voicing > 0.38f, "Speech-pitch audio has voiced correlation")
        val random = Random(42)
        val randomNoise = ShortArray(320) { random.nextInt(-9_000, 9_001).toShort() }
        expect(PcmProcessor().analyze(randomNoise).voicing < 0.38f, "Broadband noise lacks voiced correlation")
        val cropped = pcm.crop(shortArrayOf(1, 2, 3, 4, 5), 5, -2, 9)
        expect(cropped.contentEquals(shortArrayOf(1, 2, 3, 4, 5)), "Crop safely clamps recognition padding")
        val quiet = shortArrayOf(-1_000, 0, 1_000)
        val normalized = pcm.normalizeForRecognition(quiet)
        expect(normalized.maxOf { kotlin.math.abs(it.toInt()) } in 3_900..4_100, "Recognition audio applies bounded gain")
        val dcOffset = pcm.normalizeForRecognition(shortArrayOf(10_000, 11_000, 12_000))
        expect(dcOffset[1] == 0.toShort(), "Recognition audio removes DC offset")
        expect(OrbMotionMath.voiceEnergy(0.01f, 0.012f) == 0f, "Room noise keeps the orb frozen")
        expect(OrbMotionMath.voiceEnergy(0.05f, 0.004f) > 0.25f, "Speech creates visible orb motion")
        expect(
            OrbMotionMath.noiseThreshold(0.014f) > OrbMotionMath.noiseThreshold(0.004f),
            "Orb motion adapts to a louder room",
        )
        expect(
            OrbMotionMath.adaptNoiseFloor(0.004f, 0.012f, 0.25f) in 0.0059f..0.0061f,
            "Orb calibration learns ambient sound",
        )

        val detector = SpeechEndDetector(
            minimumSpeechFrames = 3,
            silenceFramesToStop = 3,
            calibrationFrames = 0,
            minimumCommandFrames = 3,
            minimumDynamicFrames = 0,
            minimumStrongFrames = 3,
        )
        expect(!detector.add(0.04f) && !detector.add(0.04f), "Two loud frames do not confirm speech")
        expect(!detector.add(0.04f), "Three loud frames start speech without stopping")
        expect(detector.hasSpeech && detector.firstSpeechFrame == 0, "Detector retains the speech start frame")
        expect(!detector.add(0f) && !detector.add(0f), "Silence shorter than the stop window keeps recording")
        expect(detector.add(0f), "The configured silence window stops after speech")
        expect(detector.endedBySilence && detector.hasClearCommand, "A short test command ends cleanly")
        expect(detector.lastSpeechFrame == 2, "Trailing silence does not move the speech end")
        val noise = SpeechEndDetector(
            minimumSpeechFrames = 3,
            silenceFramesToStop = 2,
            calibrationFrames = 0,
            minimumCommandFrames = 3,
            minimumDynamicFrames = 1,
            minimumStrongFrames = 3,
        )
        expect(!noise.add(0.04f) && !noise.add(0f) && !noise.add(0f), "A single noise spike never starts speech")
        expect(!noise.hasClearCommand, "A single transient is not a clear command")
        val tapThenRoomNoise = SpeechEndDetector(
            minimumSpeechFrames = 4,
            silenceFramesToStop = 5,
            initialNoiseFloor = 0.004f,
            calibrationFrames = 4,
            minimumCommandFrames = 12,
            minimumDynamicFrames = 3,
            minimumStrongFrames = 8,
        )
        repeat(4) { tapThenRoomNoise.add(0.004f) }
        repeat(4) { tapThenRoomNoise.add(0.04f) }
        repeat(5) { tapThenRoomNoise.add(0.008f) }
        expect(tapThenRoomNoise.endedBySilence, "Room noise after a screen tap reaches the silence stop")
        expect(!tapThenRoomNoise.hasClearCommand, "A screen tap followed by room noise is not a command")
        val adaptive = SpeechEndDetector(
            minimumSpeechFrames = 3,
            silenceFramesToStop = 3,
            initialNoiseFloor = 0.003f,
            calibrationFrames = 12,
            minimumCommandFrames = 3,
            minimumDynamicFrames = 1,
            minimumStrongFrames = 3,
        )
        repeat(12) { expect(!adaptive.add(0.012f), "Calibration frames do not become speech") }
        expect(adaptive.isCalibrated && !adaptive.hasSpeech, "Detector learns steady background noise before listening")
        repeat(8) { expect(!adaptive.add(0.012f), "Learned background noise stays below speech") }
        expect(!adaptive.hasSpeech, "Steady room noise never starts a command")
        expect(!adaptive.add(0.032f) && !adaptive.add(0.028f) && !adaptive.add(0.034f), "Speech above the learned noise floor starts capture")
        expect(adaptive.hasSpeech, "Adaptive detector accepts quiet speech over noise")
        expect(!adaptive.add(0.027f), "A changing speech frame continues the command")
        expect(adaptive.hasClearCommand, "Changing speech energy creates a clear command")
        expect(!adaptive.add(0f) && !adaptive.add(0f) && adaptive.add(0f), "Natural pause window ends adaptive speech")
        val steadyNoise = SpeechEndDetector(
            minimumSpeechFrames = 3,
            silenceFramesToStop = 3,
            calibrationFrames = 0,
            minimumCommandFrames = 10,
            minimumDynamicFrames = 3,
            minimumStrongFrames = 8,
        )
        repeat(50) { steadyNoise.add(0.04f) }
        expect(steadyNoise.hasSpeech && !steadyNoise.hasClearCommand, "Sustained steady noise never becomes a clear command")
        expect(!steadyNoise.endedBySilence, "Continuous noise does not create a natural speech end")
        val tapWithLowNoise = SpeechEndDetector(
            minimumSpeechFrames = 4,
            silenceFramesToStop = 5,
            initialNoiseFloor = 0.004f,
            calibrationFrames = 0,
            minimumCommandFrames = 20,
            minimumDynamicFrames = 3,
            minimumStrongFrames = 10,
        )
        repeat(4) { tapWithLowNoise.add(0.04f) }
        repeat(20) { index -> tapWithLowNoise.add(if (index % 2 == 0) 0.010f else 0.012f) }
        expect(!tapWithLowNoise.hasClearCommand, "A tap plus changing low noise lacks enough strong voice energy")

        expect(parsedAction("call_contact", mapOf("name" to " Mom ")).action == PhoneAction.CallContact("Mom"), "Contact names are trimmed")
        expect(parsedAction("call_number", mapOf("number" to "+91 98765 43210")).action is PhoneAction.CallNumber, "Phone number action parses")
        expect(
            parsedAction("set_alarm", mapOf("hour" to 6, "minute" to 30, "label" to "Work")).action ==
                PhoneAction.SetAlarm(6, 30, "Work"),
            "Alarm action preserves its label",
        )
        expect(parsedAction("set_alarm", mapOf("hour" to 0, "minute" to 0)).action == PhoneAction.SetAlarm(0, 0), "Alarm label is optional")
        expect(parsedAction("set_timer", mapOf("seconds" to 120)).action == PhoneAction.SetTimer(120), "Timer action parses")
        expect(parsedAction("open_app", mapOf("name" to "Clock")).action == PhoneAction.OpenApp("Clock"), "Open-app action parses")
        expect(parsedAction("close_app", emptyMap()).action == PhoneAction.CloseApp(), "Close-app name is optional")
        expect(parsedAction("close_app", mapOf("name" to "Clock")).action == PhoneAction.CloseApp("Clock"), "Close-app name parses")
        expect(parsedAction("set_ringer_mode", mapOf("mode" to "silent")).action == PhoneAction.SetRingerMode(RingerMode.SILENT), "Silent mode parses")
        expect(parsedAction("set_ringer_mode", mapOf("mode" to "vibrate")).action == PhoneAction.SetRingerMode(RingerMode.VIBRATE), "Vibrate mode parses")
        val normal = VoiceResponseParser.parseFields(
            "action", "set_ringer_mode", mapOf("mode" to "normal"), serverTimingHeader = "cache;dur=1.0, gemini;dur=42.5",
        ) as VoiceCommandResponse.Action
        expect(normal.action == PhoneAction.SetRingerMode(RingerMode.NORMAL), "Normal mode parses")
        expect(normal.serverDurationMs == 42.5, "Action stores Gemini duration")

        rejects("call_contact", mapOf("name" to ""))
        rejects("call_contact", mapOf("name" to "Mom", "extra" to true))
        rejects("call_number", mapOf("number" to "1".repeat(41)))
        rejects("set_alarm", mapOf("hour" to 24, "minute" to 0))
        rejects("set_alarm", mapOf("hour" to 6.5, "minute" to 30))
        rejects("set_alarm", mapOf("hour" to 6, "minute" to 30, "label" to null))
        rejects("set_timer", mapOf("seconds" to 0))
        rejects("set_timer", mapOf("seconds" to "60"))
        rejects("open_app", mapOf("name" to " "))
        rejects("close_app", mapOf("name" to ""))
        rejects("set_ringer_mode", mapOf("mode" to "loud"))
        rejects("unknown", emptyMap())
        rejects("set_timer", null)
        expect(VoiceResponseParser.parseFields("unsupported") == VoiceCommandResponse.Unsupported, "Unsupported response parses")
        expect(
            VoiceResponseParser.parseFields("error", reason = "timeout") ==
                VoiceCommandResponse.Error(VoiceCommandResponse.Reason.TIMEOUT),
            "Timeout response parses",
        )
        expect(
            VoiceResponseParser.parseFields("error", reason = "bad_audio") ==
                VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO),
            "Bad-audio response parses",
        )
        expect(
            VoiceResponseParser.parseFields("error", reason = "unauthorized") ==
                VoiceCommandResponse.Error(VoiceCommandResponse.Reason.UNAUTHORIZED),
            "Unauthorized response parses",
        )
        expect(
            VoiceResponseParser.parseFields("error", reason = "busy") ==
                VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BUSY),
            "Busy response parses",
        )
        expect(VoiceCommandResponse.Unsupported.uiOutcome() == VoiceUiOutcome.UNSUPPORTED, "Unsupported reaches the feature popup path")
        expect(normal.uiOutcome() == VoiceUiOutcome.ACTION, "Valid action reaches Android execution")
        expect(
            VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BUSY).uiOutcome() == VoiceUiOutcome.BUSY,
            "Busy response reaches retry feedback",
        )
        expect(
            VoiceResponseParser.parseFields("other") ==
                VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO),
            "Unknown response type is rejected",
        )

        expect(VoiceResponseParser.parseServerDuration("gemini;dur=12.5") == 12.5, "Server timing parses")
        expect(VoiceResponseParser.parseServerDuration("cache;dur=2, GEMINI ; dur=8") == 8.0, "Server timing finds Gemini among metrics")
        expect(VoiceResponseParser.parseServerDuration("gemini;dur=-1") == null, "Negative server timing is rejected")
        expect(VoiceResponseParser.parseServerDuration(null) == null, "Missing server timing stays absent")

        val now = 1_000_000L
        expect(TokenPolicy.expiresAt(now, 1) == now + 60, "Token lifetime has a safe minimum")
        expect(TokenPolicy.expiresAt(now, Long.MAX_VALUE) == now + 31L * 24L * 60L * 60L, "Token lifetime has a safe maximum")
        expect(TokenPolicy.isUsable(now + 86_401L, now), "Token is reusable outside the refresh window")
        expect(!TokenPolicy.isUsable(now + 86_400L, now), "Token refresh starts one day before expiry")
        expect(!TokenPolicy.isUsable(now - 1L, now), "Expired token is unusable")

        expect(BackendEndpoint.normalize(" https://friday.example/ ") == "https://friday.example", "Backend URL is trimmed")
        expectFailure("Cleartext backend URL is rejected") { BackendEndpoint.normalize("http://friday.example") }.also { checks++ }
        expectFailure("Backend URL credentials are rejected") { BackendEndpoint.normalize("https://user@friday.example") }.also { checks++ }
        expectFailure("Backend URL paths are rejected") { BackendEndpoint.normalize("https://friday.example/api") }.also { checks++ }

        println("$checks voice logic checks passed")
    }

    private fun expectFailure(description: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is IllegalArgumentException) { description }
    }
}
