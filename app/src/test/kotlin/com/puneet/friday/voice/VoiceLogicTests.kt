package com.puneet.friday.voice

import com.puneet.friday.actions.MediaOperation
import com.puneet.friday.actions.PhoneAction
import com.puneet.friday.actions.RingerMode
import com.puneet.friday.actions.SettingState
import com.puneet.friday.actions.SettingsTarget
import com.puneet.friday.entry.EntryPointPolicy
import com.puneet.friday.entry.AssistantSettingsTests
import com.puneet.friday.ui.OrbMotionMath
import com.puneet.friday.ui.ActionPresentation
import com.puneet.friday.ui.TabNavigation
import com.puneet.friday.ui.Destination
import com.puneet.friday.ui.CompletionPolicy
import com.puneet.friday.ui.PopupPlacementTests
import com.puneet.friday.auth.AuthPolicy
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

        val flashlight = parsedAction("flashlight", mapOf("on" to false))
        val volume = parsedAction("set_volume", mapOf("stream" to "media", "percent" to 50))
        val launch = parsedAction("open_app", mapOf("name" to "Clock"))
        expect(parsedAction("open_music", emptyMap()).action == PhoneAction.OpenMusic, "Find installed music apps locally")
        rejects("open_music", mapOf("package" to "com.spotify.music"))
        for (operation in MediaOperation.entries) {
            expect(parsedAction("control_media", mapOf("operation" to operation.wireName)).action ==
                PhoneAction.ControlMedia(operation), "Parse the media operation")
        }
        rejects("control_media", mapOf("operation" to "stop"))
        rejects("control_media", mapOf("operation" to "pause", "appName" to "YouTube"))
        expect(parsedAction("play_media", mapOf("query" to "Believer", "appName" to "Spotify")).action ==
            PhoneAction.PlayMedia("Believer", "Spotify"), "Parse a named song and player")
        rejects("play_media", mapOf("query" to "Believer", "intent" to "custom"))
        rejects("play_media", mapOf("query" to "\nBeliever"))
        rejects("play_media", mapOf("query" to "x".repeat(161)))
        rejects("play_media", mapOf("query" to "Believer", "appName" to null))
        val airplaneOn = parsedAction(
            "open_settings",
            mapOf("setting" to "airplane_mode", "state" to "on"),
        )
        expect(
            airplaneOn.action == PhoneAction.OpenSettings(SettingsTarget.AIRPLANE_MODE, SettingState.ON),
            "Airplane mode Settings handoff parses",
        )
        expect(
            parsedAction("open_settings", mapOf("setting" to "airplane_mode")).action ==
                PhoneAction.OpenSettings(SettingsTarget.AIRPLANE_MODE),
            "Settings state is optional",
        )
        rejects("open_settings", mapOf("setting" to "wifi"))
        rejects("open_settings", mapOf("setting" to "airplane_mode", "state" to "toggle"))
        rejects("open_settings", mapOf("setting" to "airplane_mode", "intent" to "android.settings.SETTINGS"))
        val steps = listOf(flashlight, volume, launch)
        val batch = VoiceResponseParser.parseSequence(steps, null) as VoiceCommandResponse.Action
        expect(batch.action == PhoneAction.Sequence(steps.map { it.action }), "A sequence keeps the requested order")
        expect(VoiceResponseParser.parseSequence(listOf(launch, flashlight), null) is VoiceCommandResponse.Error,
            "An app launch cannot run ahead of a remaining action")
        expect(VoiceResponseParser.parseSequence(listOf(flashlight, VoiceCommandResponse.Unsupported), null) is VoiceCommandResponse.Error,
            "An unsupported step blocks the whole sequence")
        rejects("flashlight", mapOf("on" to "false"))
        rejects("set_volume", mapOf("stream" to "media", "percent" to 101))

        val answerFields: Map<String, Any?> = mapOf(
            "type" to "answer", "kind" to "knowledge", "text" to "Sachin Tendulkar is a former Indian cricketer.",
            "sources" to emptyList<Any>(), "answerId" to "123e4567-e89b-42d3-a456-426614174000",
            "issuedAt" to 1_795_000_000L, "reportToken" to "a".repeat(43),
        )
        val answer = AnswerPayload.parse(answerFields)
        expect(answer != null && answer.sources.isEmpty(), "A short knowledge answer parses independently of phone actions")
        expect(VoiceCommandResponse.Answer(answer!!).uiOutcome() == VoiceUiOutcome.ANSWER, "An answer has its own UI outcome")
        expect(VoiceResponseParser.parseFields("action", "answer_question", answerFields) is VoiceCommandResponse.Error,
            "A question answer cannot execute as a phone action")
        expect(VoiceResponseParser.parseSequence(listOf(flashlight, VoiceCommandResponse.Answer(answer)), null) is VoiceCommandResponse.Error,
            "An answer cannot run inside an action sequence")
        for (invalid in listOf(
            answerFields + ("text" to "a".repeat(601)), answerFields + ("text" to "word ".repeat(81)),
            answerFields + ("text" to "line\nbreak"), answerFields + ("text" to " "),
            answerFields + ("kind" to "action"), answerFields + ("kind" to "information"),
            answerFields + ("issuedAt" to -1), answerFields + ("issuedAt" to 1.5),
            answerFields + ("reportToken" to "short"), answerFields + ("action" to "call_number"),
        )) expect(AnswerPayload.parse(invalid) == null, "Malformed answers cannot reach the popup")
        val sourced = answerFields + mapOf("kind" to "information", "sources" to listOf(
            mapOf("title" to "Match report", "url" to "https://example.com/report"),
        ))
        expect(AnswerPayload.parse(sourced)?.sources?.size == 1, "A searched answer has a source")
        expect(AnswerPayload.parse(sourced + ("kind" to "knowledge")) == null, "Memory answers cannot claim web sources")
        val clarification = answerFields + mapOf("kind" to "clarification", "text" to "Which Gandhi do you mean?",
            "continuation" to "a".repeat(80))
        expect(AnswerPayload.parse(clarification)?.continuation == "a".repeat(80), "A clarification carries an opaque continuation")
        for (invalid in listOf(
            clarification + ("continuation" to "x".repeat(4_097)), clarification + ("continuation" to "too-short"),
            clarification + ("continuation" to "not a valid token ".repeat(8)), clarification + ("continuation" to null),
            clarification + ("kind" to "knowledge"), clarification + ("kind" to "unavailable"),
        )) expect(AnswerPayload.parse(invalid) == null, "Only bounded clarification continuations can open reply capture")
        for (url in listOf("http://example.com", "intent://example.com", "file:///data/local", "https://localhost",
            "https://127.0.0.1", "https://[::1]/", "https://example.local", "https://example.internal",
            "https://user:pass@example.com", "https://example.com:444/", "https://example.com/\n", "https://example.com\\test")) {
            expect(!AnswerPayload.isPublicSource(url), "Unsafe source links are rejected")
        }
        for (url in listOf("https://localhost./", "https://service.local./", "https://0x7f.0.0.1/")) {
            expect(!AnswerPayload.isPublicSource(url), "Alternate local host forms are rejected")
        }
        expect(AnswerPayload.isPublicSource("https://example.com/report?a=1"), "Public HTTPS sources remain usable")

        val tabs = TabNavigation()
        expect(tabs.current == Destination.HOME, "Main navigation starts on Home")
        expect(!tabs.select(Destination.HOME), "Reselecting Home does not reload its view")
        expect(tabs.select(Destination.SETTINGS), "Settings changes the visible tab")
        expect(!tabs.select(Destination.SETTINGS), "Reselecting Settings preserves its scroll state")
        expect(tabs.select(Destination.HOME), "Returning Home changes the visible tab")
        expect(tabs.current == Destination.HOME, "Returning Home preserves the navigation destination")

        val speech = SpeechSession()
        expect(!speech.isActive, "A new speaker has no pending confirmation to keep after closing")
        val firstSpeech = speech.begin()
        expect(speech.isActive, "A queued confirmation remains active during engine startup")
        expect(speech.accepts(firstSpeech), "The current utterance can report playback")
        speech.cancel()
        expect(!speech.isActive, "Cancellation releases a pending confirmation before engine startup")
        expect(!speech.accepts(firstSpeech), "Cancelled TTS cannot restart playback after engine startup")
        val replacementSpeech = speech.begin()
        expect(!speech.finish(firstSpeech), "A stale completion cannot stop a replacement utterance")
        expect(speech.accepts(replacementSpeech), "Replacement speech stays active")
        expect(speech.finish(replacementSpeech), "The current completion ends playback")
        expect(!speech.isActive, "Completed playback cannot keep a closed screen's speaker alive")
        expect(!speech.finish(replacementSpeech), "Duplicate TTS completion is ignored")
        expect(!speech.accepts(null), "Missing TTS identity cannot update the screen")
        val nextSpeaker = SpeechSession()
        val originalSpeakerId = speech.begin()
        val nextSpeakerId = nextSpeaker.begin()
        expect(originalSpeakerId != nextSpeakerId,
            "A replacement Activity's first confirmation has a different shared-engine identity")
        expect(!nextSpeaker.finish(originalSpeakerId) && nextSpeaker.accepts(nextSpeakerId),
            "The old Activity's stop callback cannot finish the replacement's confirmation")
        expect(CompletionPolicy.closeDelay(true, true, false, false, false) == 0L,
            "A compact successful result closes when its confirmation finishes without a display delay")
        expect(CompletionPolicy.closeDelay(false, true, false, false, false) == null,
            "A successful result keeps the full Home screen open")
        expect(CompletionPolicy.closeDelay(true, false, false, false, false) == null,
            "Errors and permission requests do not use success dismissal")
        expect(CompletionPolicy.closeDelay(true, true, false, false, true) == null,
            "User interaction keeps the result available")
        expect(CompletionPolicy.closeDelay(true, true, true, false, false) == 12_000L,
            "Speech has a bounded completion watchdog")
        expect(CompletionPolicy.closeDelay(true, true, false, true, false) == 12_000L,
            "TTS startup does not close the result before the confirmation")
        expect(CompletionPolicy.closeWhenLeaving(true, true),
            "A completed compact timer panel closes when Clock pauses it before speech finishes")
        expect(!CompletionPolicy.closeWhenLeaving(false, true),
            "An external action leaves the full Home screen available")
        expect(!CompletionPolicy.closeWhenLeaving(true, false),
            "Pausing an unfinished command, error or permission panel cannot mark it complete")
        expect(!CompletionPolicy.closeWhenLeaving(false, false),
            "Leaving the idle full-screen app does not finish its task")
        val launcherResult = CompletionPolicy.onPause(false, true, false, false)
        expect(launcherResult.keepConfirmation && !launcherResult.closeScreen && !launcherResult.resetUi,
            "Clock handoff from launcher Home keeps confirmation and result without closing Home")
        val panelResult = CompletionPolicy.onPause(true, true, false, false)
        expect(panelResult.keepConfirmation && panelResult.closeScreen && !panelResult.resetUi,
            "Clock handoff from the compact assistant closes only its screen and keeps speech")
        for (compact in listOf(false, true)) {
            val unfinished = CompletionPolicy.onPause(compact, false, false, false)
            expect(!unfinished.keepConfirmation && !unfinished.closeScreen && unfinished.resetUi,
                "Leaving unfinished voice work cancels it from either entry point")
            val rotating = CompletionPolicy.onPause(compact, true, false, true)
            expect(!rotating.keepConfirmation && !rotating.closeScreen && rotating.resetUi,
                "Rotation cannot detach speech or close the replacement screen")
        }
        val finishing = CompletionPolicy.onPause(true, false, true, false)
        expect(!finishing.keepConfirmation && !finishing.resetUi,
            "Closing an unfinished compact screen cancels speech without flashing idle")
        expect(CompletionPolicy.spokenConfirmation("Silent mode on") == "Done. Silent mode on",
            "A completed sound change gets one short confirmation")
        expect(CompletionPolicy.spokenHandoff("Turn airplane mode on here") == "Turn airplane mode on here",
            "A Settings handoff does not claim Friday changed the setting")
        for (message in listOf("Calling Mom", "Opening Calculator", "Timer request sent for 2 minutes", "Alarm request sent for 6:30 AM")) {
            expect(CompletionPolicy.spokenConfirmation(message) == message,
                "Dispatched external actions preserve honest wording: $message")
        }
        expect(AuthPolicy.accessUsable(1_061, 1_000), "An account access token outside the refresh margin is usable")
        expect(!AuthPolicy.accessUsable(1_060, 1_000), "A token near expiry refreshes before the command")
        expect(!AuthPolicy.accessUsable(999, 1_000), "Expired access cannot reach the command endpoint")
        val loginId = "A".repeat(43)
        expect(AuthPolicy.acceptsCallback("com.puneet.friday", "signin", loginId, loginId),
            "The expected browser callback can finish the pending transaction")
        expect(!AuthPolicy.acceptsCallback("com.example.assistant", "signin", loginId, loginId),
            "The old package callback cannot complete a Friday login")
        expect(!AuthPolicy.acceptsCallback("com.puneet.friday", "signin", loginId, "B".repeat(43)),
            "Another transaction cannot replace the pending login")
        expect(!AuthPolicy.acceptsCallback("com.puneet.friday", "signin", loginId, null),
            "A browser link without a pending transaction is rejected")
        expect(!AuthPolicy.isOpaque("A".repeat(42)) && !AuthPolicy.isOpaque("/".repeat(43)),
            "Malformed transaction credentials are rejected")
        expect(AuthPolicy.isGoogleClientId("123456789-test.apps.googleusercontent.com"),
            "The public Google web client ID has the expected host")
        expect(!AuthPolicy.isGoogleClientId("123-test.apps.googleusercontent.com.evil.example"),
            "A lookalike identity host is rejected")
        expect(ActionPresentation.describe(PhoneAction.SetTimer(3_661)) == "Set timer · 1 hour 1 minute 1 second",
            "The request card preserves mixed timer units")
        expect(ActionPresentation.describe(PhoneAction.SetTimer(60)) == "Set timer · 1 minute",
            "The request card omits zero timer units")
        expect(ActionPresentation.describe(PhoneAction.SetAlarm(6, 5)) == "Set alarm · 06:05",
            "The request card preserves alarm minutes")
        expect(ActionPresentation.describe(PhoneAction.CloseApp("Calculator")) == "Go to home screen",
            "The request card does not claim to close another app")
        expect(ActionPresentation.describe(PhoneAction.SetRingerMode(RingerMode.SILENT)) == "Turn on silent mode",
            "The request card describes silent without DND")

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
        expect(!AudioCapturePolicy.canStopAfterSilence(afterWake = true, sampleCount = 31_999),
            "Wake capture keeps listening until two seconds")
        expect(AudioCapturePolicy.canStopAfterSilence(afterWake = true, sampleCount = 32_000),
            "Wake capture can stop on silence after two seconds")
        expect(AudioCapturePolicy.canStopAfterSilence(afterWake = false, sampleCount = 1),
            "Tap capture keeps its current silence stop")
        expect(!AudioCapturePolicy.canStopAfterSilence(afterWake = false, sampleCount = 79_999, answerReply = true),
            "Reply capture leaves five seconds to respond before a silence stop")
        expect(AudioCapturePolicy.canStopAfterSilence(afterWake = false, sampleCount = 80_000, answerReply = true),
            "Reply capture can finish on silence after five seconds")
        expect(!AudioCapturePolicy.canStopAfterSilence(afterWake = true, sampleCount = 32_000, answerReply = true),
            "A clarification after a wake still uses the reply listening window")
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
        val playbackLevel = PcmProcessor().analyze(tone, analyzeVoicing = false)
        expect(playbackLevel.copy(voicing = toneLevel.voicing) == toneLevel,
            "TTS playback keeps identical energy and spectrum while skipping speech classification")
        expect(toneLevel.amplitude in 0.1f..1f, "Speech-level PCM produces normalized amplitude")
        expect(toneLevel.low in 0f..1f && toneLevel.mid in 0f..1f && toneLevel.high in 0f..1f, "Spectrum levels stay normalized")
        expect(toneLevel.rms in 0.05f..0.3f, "Analyzer exposes raw RMS for speech detection")
        val voicedTone = ShortArray(320) { index -> (sin(2.0 * PI * index / 100.0) * 9_000).toInt().toShort() }
        expect(PcmProcessor().analyze(voicedTone).voicing > 0.38f, "Speech-pitch audio has voiced correlation")
        val lowerCorrelation = SpeechEndDetector(
            minimumSpeechFrames = 3,
            calibrationFrames = 0,
            minimumCommandFrames = 3,
            minimumDynamicFrames = 1,
            minimumStrongFrames = 3,
            minimumVoicedFrames = 3,
        )
        listOf(0.04f, 0.05f, 0.04f, 0.05f).forEach { rms ->
            lowerCorrelation.add(PcmProcessor.Level(0f, 0f, 0f, 0f, rms, 0.25f))
        }
        expect(lowerCorrelation.hasClearCommand, "Speech with 0.25 voicing correlation passes the relaxed 0.20 threshold")
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
        expect(OrbMotionMath.voiceEnergy(0.017f, 0.004f) > 0.08f, "Quiet speech creates visible orb motion")
        expect(OrbMotionMath.voiceEnergy(0.008f, 0.004f) == 0f, "Background hum leaves the orb still")
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
        val afterWake = AudioCapturePolicy.commandDetector(afterWake = true)
        val afterTap = AudioCapturePolicy.commandDetector(afterWake = false)
        repeat(3) {
            afterWake.add(0.004f)
            afterTap.add(0.004f)
        }
        expect(!afterWake.isCalibrated, "Wake capture still samples the noise floor before the ready tone")
        afterWake.add(0.004f)
        afterTap.add(0.004f)
        expect(afterWake.isCalibrated && !afterTap.isCalibrated, "Wake entry is ready after four microphone frames")
        repeat(8) { afterTap.add(0.004f) }
        expect(afterTap.isCalibrated, "Tap entry keeps its twelve-frame calibration")
        val commandProcessor = PcmProcessor()
        val quietCommand = ShortArray(320 * 28) { index ->
            val peak = if ((index / 320) % 2 == 0) 330 else 475
            (sin(2.0 * PI * index / 100.0) * peak).toInt().toShort()
        }
        val wakeQuiet = AudioCapturePolicy.commandDetector(afterWake = true)
        val tapQuiet = AudioCapturePolicy.commandDetector(afterWake = false)
        val wakeNoise = AudioCapturePolicy.commandDetector(afterWake = true)
        repeat(12) {
            wakeQuiet.add(0.002f)
            tapQuiet.add(0.002f)
            wakeNoise.add(0.002f)
        }
        for (offset in quietCommand.indices step 320) {
            val level = commandProcessor.analyze(quietCommand.copyOfRange(offset, offset + 320))
            wakeQuiet.add(level)
            tapQuiet.add(level)
            wakeNoise.add(level.copy(voicing = 0f))
        }
        expect(wakeQuiet.hasClearCommand && !tapQuiet.hasClearCommand,
            "Wake listening accepts quieter voiced commands")
        expect(!wakeNoise.hasClearCommand, "Wake listening still rejects unvoiced sound at the same level")
        expect(AudioCapturePolicy.hasCommandEnergy(commandProcessor, quietCommand, afterWake = true),
            "The final energy check accepts quiet speech after a wake")
        expect(!AudioCapturePolicy.hasCommandEnergy(commandProcessor, quietCommand, afterWake = false),
            "The tap-to-speak energy check keeps its existing threshold")
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
        val broadbandNoise = SpeechEndDetector(
            minimumSpeechFrames = 4,
            silenceFramesToStop = 4,
            calibrationFrames = 0,
            minimumCommandFrames = 20,
            minimumDynamicFrames = 6,
            minimumStrongFrames = 10,
            minimumVoicedFrames = 6,
        )
        val broadbandRandom = Random(711)
        repeat(60) {
            val noiseFrame = ShortArray(320) { broadbandRandom.nextInt(-12_000, 12_001).toShort() }
            broadbandNoise.add(PcmProcessor().analyze(noiseFrame))
        }
        repeat(4) { broadbandNoise.add(PcmProcessor.Level(0f, 0f, 0f, 0f)) }
        expect(!broadbandNoise.hasClearCommand, "Changing broadband noise lacks enough voiced speech frames")
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
        expect(
            ActionPresentation.describe(PhoneAction.OpenSettings(SettingsTarget.AIRPLANE_MODE, SettingState.OFF)) ==
                "Open airplane mode settings · turn off",
            "Settings handoff presentation includes the requested state",
        )
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
        val answersDisabled = VoiceResponseParser.parseFields("unsupported", reason = "answers_disabled")
        expect(answersDisabled == VoiceCommandResponse.AnswerSetup(VoiceCommandResponse.AnswerSetupReason.DISABLED),
            "A disabled answer points to client setup")
        expect(answersDisabled.uiOutcome() == VoiceUiOutcome.ANSWERS_DISABLED, "A disabled answer has its own UI outcome")
        val answersUnavailable = VoiceResponseParser.parseFields("unsupported", reason = "answers_unavailable")
        expect(answersUnavailable.uiOutcome() == VoiceUiOutcome.ANSWERS_UNAVAILABLE, "Unavailable answers do not ask for new consent")
        expect(VoiceResponseParser.parseFields("unsupported", reason = "unknown") is VoiceCommandResponse.Error,
            "An unknown setup reason fails validation")
        expect(VoiceResponseParser.parseSequence(listOf(flashlight, answersDisabled), null) is VoiceCommandResponse.Error,
            "An answer setup hint cannot execute in an action sequence")
        expect(VoiceFailureCopy.forOutcome(VoiceUiOutcome.ANSWERS_DISABLED) == "Turn on Short answers to ask questions.",
            "Disabled answers explain how to enable them")
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
            EntryPointPolicy.shouldAutoListen(requested = true, setupSeen = true, microphoneGranted = true),
            "An enabled entry point starts listening after setup",
        )
        expect(
            !EntryPointPolicy.shouldAutoListen(requested = true, setupSeen = false, microphoneGranted = true),
            "An entry point waits for setup",
        )
        expect(
            !EntryPointPolicy.shouldAutoListen(requested = true, setupSeen = true, microphoneGranted = false),
            "An entry point waits for microphone access",
        )
        expect(
            !EntryPointPolicy.shouldAutoListen(requested = false, setupSeen = true, microphoneGranted = true),
            "A normal launch stays idle",
        )
        expect(EntryPointPolicy.shouldRequestSetup(setupSeen = false, promptOpen = false), "First entry-point launch opens setup")
        expect(!EntryPointPolicy.shouldRequestSetup(setupSeen = false, promptOpen = true), "An open setup screen is not launched twice")
        expect(!EntryPointPolicy.shouldRequestSetup(setupSeen = true, promptOpen = false), "Completed setup is not reopened")
        expect(
            EntryPointPolicy.shouldRestoreReadyNotification(enabled = true, notificationsAllowed = true),
            "An enabled Ready notification is restored after the app reopens",
        )
        expect(
            !EntryPointPolicy.shouldRestoreReadyNotification(enabled = true, notificationsAllowed = false),
            "A blocked notification is not posted",
        )
        expect(
            EntryPointPolicy.readyNotificationState(enabled = false, notificationsAllowed = false) ==
                EntryPointPolicy.ReadyNotificationState.OFF,
            "A disabled Ready notification stays off",
        )
        expect(
            EntryPointPolicy.readyNotificationState(enabled = true, notificationsAllowed = true) ==
                EntryPointPolicy.ReadyNotificationState.ON,
            "An enabled and allowed Ready notification is on",
        )
        expect(
            EntryPointPolicy.readyNotificationState(enabled = true, notificationsAllowed = false) ==
                EntryPointPolicy.ReadyNotificationState.BLOCKED,
            "A revoked notification permission is shown as blocked",
        )
        expect(
            EntryPointPolicy.restoresReadyNotification(android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED),
            "Locked boot restores the Ready notification",
        )
        expect(
            EntryPointPolicy.restoresReadyNotification(android.content.Intent.ACTION_BOOT_COMPLETED),
            "Unlocked boot restores the Ready notification",
        )
        expect(
            EntryPointPolicy.restoresReadyNotification(android.content.Intent.ACTION_MY_PACKAGE_REPLACED),
            "App updates restore the Ready notification",
        )
        expect(!EntryPointPolicy.restoresReadyNotification(null), "Unrelated broadcasts do not restore the notification")
        expect(
            VoiceFailureCopy.forOutcome(VoiceUiOutcome.TIMEOUT) == "The server took too long. Try again.",
            "A provider timeout names the cause",
        )
        expect(
            VoiceFailureCopy.forConnectionFailure("registration_failed", offline = false) ==
                "Friday couldn't start. Try again.",
            "A registration failure does not look like a speech error",
        )
        expect(
            VoiceFailureCopy.forConnectionFailure("offline", offline = true) == "No internet",
            "An offline request keeps the direct network message",
        )
        expect(!EntryPointPolicy.supportsTileSubtitle(28), "API 28 skips the tile subtitle API")
        expect(EntryPointPolicy.supportsTileSubtitle(29), "API 29 enables tile subtitles")
        expect(!EntryPointPolicy.usesPendingIntentTileLaunch(33), "API 33 uses the legacy tile launch API")
        expect(EntryPointPolicy.usesPendingIntentTileLaunch(34), "API 34 uses PendingIntent tile launch")
        expect(EntryPointPolicy.RECOGNITION_ERROR_CLIENT == 5, "Assistant compatibility service reports a client error")
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
        expect(VoiceResponseParser.parseServerDuration("groq;dur=896.0, transcription;dur=577.0, classification;dur=319.0") == 896.0,
            "Groq timing reports the whole two-stage request")
        expect(VoiceResponseParser.parseServerDuration("transcription;dur=20, GROQ ; dur=30") == 30.0,
            "Groq timing works after a stage metric")
        expect(VoiceResponseParser.parseServerDuration("groq;dur=-1") == null, "Negative Groq timing is rejected")
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

        checks += AssistantSettingsTests.run()
        checks += PopupPlacementTests.run()
        println("$checks voice logic checks passed")
    }

    private fun expectFailure(description: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is IllegalArgumentException) { description }
    }
}
