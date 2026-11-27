package com.puneet.friday.debug.wake

import com.puneet.friday.voice.MicrophoneUnavailable
import com.puneet.friday.voice.PcmFrameReader

import java.util.concurrent.CountDownLatch

/** Debug trial lifecycle checks, with no microphone, native library or test framework. */
object WakeTrialLogicTests {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun expect(value: Boolean, description: String) {
            check(value) { description }
            checks++
        }
        val session = WakeTrialSession()
        expect(!session.snapshot().active, "A fresh process never starts background listening")
        val first = checkNotNull(session.begin(WakeTrialSession.STRICT_THRESHOLD))
        expect(session.snapshot().phase == WakeTrialSession.Phase.STARTING, "Initialization shows Starting")
        expect(session.begin(WakeTrialSession.DEFAULT_THRESHOLD) == null, "Repeated Start never replaces a running detector")
        expect(!session.detected(first, 10), "Warm-up detections never count")
        expect(session.listening(first, 100), "The current detector can enter Listening")
        expect(session.detected(first, 900), "A live detection counts")
        expect(session.snapshot().detections == 1 && session.snapshot().lastDetectionMs == 900L,
            "The result tracks a count and time, without a transcript")
        session.stopping(first, "Microphone off")
        expect(session.snapshot().active, "Stop retains microphone ownership until cleanup finishes")
        expect(!session.detected(first, 1_000), "No detection after Stop")
        expect(!session.listening(first, 200), "Delayed initialization cannot undo Stop")
        expect(session.begin(WakeTrialSession.DEFAULT_THRESHOLD) == null, "Start waits for previous cleanup")
        session.finish(first, 1_200, "Microphone off")
        expect(!session.snapshot().active && session.snapshot().elapsedMs == 1_200L, "Cleanup leaves stopped results readable")
        val second = checkNotNull(session.begin(WakeTrialSession.DEFAULT_THRESHOLD))
        expect(second != first && session.snapshot().detections == 0, "A new trial resets its count")
        expect(!session.detected(first, 1_300) && !session.listening(first, 300), "Old callbacks cannot affect a replacement")
        session.finish(first, 0, "stale error")
        expect(session.snapshot().phase == WakeTrialSession.Phase.STARTING, "Old cleanup cannot stop a replacement")
        session.stopping(first, "stale stop")
        expect(session.snapshot().phase == WakeTrialSession.Phase.STARTING, "Stale cancellation cannot stop a replacement")
        session.stopping(second, "Cancelled while starting")
        expect(!session.listening(second, 400), "Rapid Start then Stop cannot open a new listening session")
        session.finish(second, 0, "Microphone off")
        for (cutoff in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 0.75f)) {
            expect(runCatching { session.begin(cutoff) }.isFailure, "Only supported sensitivity settings are allowed")
        }
        for (cutoff in WakeTrialSession.THRESHOLDS) {
            val id = checkNotNull(session.begin(cutoff))
            session.finish(id, 0, "Microphone off")
            expect(session.snapshot().threshold == cutoff, "Each supported cutoff is reproducible")
        }
        expect(!WakeTrialModel.accepts(ByteArray(0)), "Reject a missing model")
        expect(!WakeTrialModel.accepts(ByteArray(WakeTrialModel.BYTES - 1)), "Reject a truncated model")
        expect(!WakeTrialModel.accepts(ByteArray(WakeTrialModel.BYTES)), "Reject a corrupt model with the expected size")
        expect(!WakeTrialModel.accepts(ByteArray(WakeTrialModel.BYTES + 1)), "Reject a larger model")
        val bands = FloatArray(FridayKeywordFeatures.BANDS * FridayKeywordFeatures.BINS)
        for (band in 0 until FridayKeywordFeatures.BANDS) {
            bands[(band + 1) * FridayKeywordFeatures.BINS - 1] = 1f
        }
        val features = FridayKeywordFeatures(bands)
        repeat(800) { features.append(0) }
        expect(!features.hasRecentSound, "Silence cannot reach the keyword scorer")
        repeat(160) { features.append(if (it % 2 == 0) 2_300 else -2_300) }
        expect(!features.hasRecentSound, "An isolated loud audio frame cannot reach the scorer")
        repeat(1_600) { features.append(if (it % 2 == 0) 2_300 else -2_300) }
        expect(features.hasRecentSound, "A sustained signal reaches the keyword scorer")
        repeat(12_800) { features.append(0) }
        expect(!features.hasRecentSound, "Old sound cannot keep scoring during silence")
        repeat(2_400) { features.append(if (it % 2 == 0) 30 else -30) }
        expect(features.hasRecentSound, "Quiet sound with a changing level reaches the scorer")
        repeat(12_800) { features.append(if (it % 2 == 0) 30 else -30) }
        expect(!features.hasRecentSound, "Steady quiet noise does not use the quiet speech gate")
        val wakeAudio = WakeAudioBuffer(capacity = 5)
        wakeAudio.append(shortArrayOf(1, 2, 3, 4), 3)
        expect(wakeAudio.copy().contentEquals(shortArrayOf(1, 2, 3)), "Wake clips omit samples past the detection")
        wakeAudio.append(shortArrayOf(4, 5, 6, 7), 4)
        expect(wakeAudio.copy().contentEquals(shortArrayOf(3, 4, 5, 6, 7)), "Wake clips keep only the newest samples in order")
        wakeAudio.clear()
        expect(wakeAudio.copy().isEmpty(), "Stopping capture clears the rolling wake audio")
        var now = 0L
        var nextSample = 0
        val captured = mutableListOf<Short>()
        val chunks = listOf(0, 91, 100, 129, 320).iterator()
        PcmFrameReader.readFrames(
            read = { frame, offset, length ->
                val count = chunks.next()
                check(count <= length)
                repeat(count) { frame[offset + it] = (nextSample++).toShort() }
                count
            },
            nowMs = { now }, cancelled = { false }, waitForAudio = { now += it },
        ) { frame ->
            captured.addAll(frame.toList())
            captured.size < 640
        }
        expect(captured == (0 until 640).map(Int::toShort),
            "Partial reads preserve sample order across detector frames")
        expect(now == 3 * PcmFrameReader.FRAME_MS, "An incomplete frame waits for audio without spinning")
        val stalled = runCatching {
            PcmFrameReader.readFrames({ _, _, _ -> 0 }, { now }, { false }, { now += it }) { error("No audio") }
        }
        expect(stalled.exceptionOrNull() is MicrophoneUnavailable && now < 2_500,
            "A stalled microphone exits within a bounded interval")
        expect(runCatching {
            PcmFrameReader.readFrames({ _, _, _ -> -6 }, { now }, { false }, { error("Unexpected wait") }) { true }
        }.exceptionOrNull() is MicrophoneUnavailable, "A dead Android recorder can be reopened")
        var cancelled = false
        PcmFrameReader.readFrames({ _, _, _ -> 0 }, { now }, { cancelled }, { cancelled = true }) { error("No audio") }
        expect(cancelled, "Stop can interrupt an empty recorder before the stall timeout")
        val micRetry = WakeMicrophoneRetry()
        expect(micRetry.nextDelayMs(0) == 250L && micRetry.nextDelayMs(20) == 1_000L &&
            micRetry.nextDelayMs(0) == null, "Repeated recorder failures exhaust the retry budget")
        expect(micRetry.nextDelayMs(5_000) == 250L, "Sustained audio restores the next outage's retry budget")
        val retrySession = WakeTrialSession()
        val retryRun = checkNotNull(retrySession.begin(WakeTrialSession.DEFAULT_THRESHOLD, commandMode = true))
        retrySession.listening(retryRun, 100)
        expect(retrySession.startingMicrophone(retryRun, retry = true) &&
            retrySession.snapshot().phase == WakeTrialSession.Phase.STARTING,
            "An interrupted recorder leaves Listening before its replacement receives any samples")
        retrySession.pause(retryRun)
        expect(!retrySession.startingMicrophone(retryRun, retry = true), "A delayed retry yields to a command")
        expect(retrySession.startingMicrophone(retryRun), "After the command, the regular rearm can open the mic")
        retrySession.stopping(retryRun, "Microphone off")
        expect(!retrySession.startingMicrophone(retryRun, retry = true), "Explicit Stop cancels microphone recovery")
        retrySession.finish(retryRun, 500, "Microphone off")
        val nextRun = checkNotNull(retrySession.begin(WakeTrialSession.DEFAULT_THRESHOLD))
        expect(!retrySession.startingMicrophone(retryRun, retry = true) && nextRun != retryRun,
            "Recovery from a previous wake run cannot take the microphone")
        expect(runCatching { WakeTrialHandoff.awaitStopped(null) }.isSuccess, "Capture starts when no trial owns the mic")
        expect(runCatching { WakeTrialHandoff.awaitStopped(CountDownLatch(0)) }.isSuccess, "Completed cleanup permits capture")
        expect(runCatching { WakeTrialHandoff.awaitStopped(CountDownLatch(1), 0) }.exceptionOrNull() is IllegalStateException,
            "A cleanup timeout reaches the recorder's handled failure path")
        Thread.currentThread().interrupt()
        expect(runCatching { WakeTrialHandoff.awaitStopped(CountDownLatch(1)) }.exceptionOrNull() is IllegalStateException,
            "Activity shutdown during handoff cannot escape as an unhandled checked exception")
        expect(Thread.interrupted(), "Cancellation preserves interruption and the test clears it afterward")
        val assistantRetry = WakeAssistantRetry()
        expect(assistantRetry.nextDelayMs() == 150L, "A late assistant service gets a short retry")
        expect(assistantRetry.nextDelayMs() == 300L, "Assistant retries back off")
        expect(assistantRetry.nextDelayMs() == 600L && assistantRetry.nextDelayMs() == 900L,
            "Assistant retries stay bounded")
        expect(assistantRetry.nextDelayMs() == null, "Exhausted assistant retries fall back to the notification")
        val commands = WakeTrialSession()
        expect(WakeTrialSession.DEFAULT_THRESHOLD == 1f, "Standard uses the bundled model's calibrated cutoff")
        expect(WakeTrialSession.HIGHER_SENSITIVITY_THRESHOLD < WakeTrialSession.DEFAULT_THRESHOLD,
            "Higher sensitivity uses a lower cutoff than Standard")
        val run = checkNotNull(commands.begin(WakeTrialSession.DEFAULT_THRESHOLD, commandMode = true, nowMs = 100))
        expect(commands.snapshot().commandMode, "Command mode requires an explicit trial option")
        expect(commands.listening(run, 1_000), "An opted-in command trial starts listening")
        expect(commands.detected(run, 2_000), "A live wake starts a handoff")
        val token = checkNotNull(commands.snapshot().pendingWake)
        expect(commands.claimHandoff(run) == token, "Only the active wake can request the system session")
        expect(commands.claimHandoff(run) == null, "Duplicate cleanup callbacks cannot launch two system sessions")
        expect(commands.snapshot().phase == WakeTrialSession.Phase.HANDOFF, "Pause wake inference before opening a command")
        expect(!commands.detected(run, 2_010), "Repeated wake callbacks cannot open duplicate panels")
        expect(!commands.listening(run, 1_100), "Wake capture cannot resume before handoff completes")
        expect(!commands.consumeWake("stale"), "Reject stale or forged handoff requests")
        commands.awaitingTap(run)
        expect(commands.isPendingWake(token), "The notification fallback retains its pending request")
        expect(commands.consumeWake(token), "The visible assistant consumes the current request")
        expect(!commands.consumeWake(token), "The same notification cannot start a second command")
        expect(commands.snapshot().openedCommands == 1 && commands.snapshot().pendingWake == null,
            "Count opened panels without recording command text")
        expect(!commands.detected(run, 2_500), "Wake callbacks cannot trigger during command capture or TTS")
        expect(commands.pause(run), "The visible assistant keeps the trial paused")
        expect(commands.listening(run, 5_000), "Dismissal can resume the same opted-in trial")
        expect(!commands.expireWake(run, token), "A used handoff timer cannot disturb resumed capture")
        expect(commands.snapshot().startedMs == 1_000L && commands.snapshot().detections == 1,
            "Resume preserves run duration and counts")
        commands.addCompute(run, 2_000, 50)
        commands.addCompute(run, 1_000, 25)
        expect(commands.snapshot().audioMs == 3_000L && commands.snapshot().computeMs == 75L,
            "Capture segments retain aggregate compute timings")
        expect(commands.detected(run, 6_000, synthetic = true), "The debug handoff test can inject a labeled wake")
        expect(commands.snapshot().syntheticDetections == 1, "Synthetic wakes stay distinguishable from model detections")
        val stoppedToken = checkNotNull(commands.snapshot().pendingWake)
        commands.stopping(run, "Microphone off")
        expect(!commands.consumeWake(stoppedToken) && !commands.pause(run), "Stop cancels a pending launch and forbids resume")
        commands.finish(run, 7_000, "Microphone off")
        expect(!commands.listening(run, 8_000), "Dismissal cannot restart a stopped service")
        val replacement = checkNotNull(commands.begin(WakeTrialSession.DEFAULT_THRESHOLD, commandMode = true, nowMs = 200))
        expect(!commands.consumeWake(stoppedToken), "An earlier run's notification cannot wake its replacement")
        commands.listening(replacement, 10_000)
        repeat(100) { index ->
            commands.detected(replacement, index.toLong())
            commands.consumeWake(checkNotNull(commands.snapshot().pendingWake))
            commands.listening(replacement, 11_000)
        }
        expect(commands.snapshot().detectionTimesMs.size == 64 && commands.snapshot().detections == 100,
            "Retain bounded event metadata while keeping the full count")
        expect(commands.snapshot().detectionTimesMs.first() == 36L, "The report retains the most recent event offsets")
        expect(commands.detected(replacement, 12_000), "A wake can enter the fallback handoff")
        val unclaimed = checkNotNull(commands.claimHandoff(replacement))
        commands.awaitingTap(replacement)
        expect(!commands.expireWake(run, unclaimed), "A timeout from another run cannot expire a live request")
        expect(!commands.expireWake(replacement, stoppedToken), "A timeout from an older wake cannot expire the current request")
        expect(commands.expireWake(replacement, unclaimed), "An unclaimed handoff can expire")
        expect(!commands.consumeWake(unclaimed), "An expired notification cannot open a command")
        expect(commands.listening(replacement, 21_000), "An expired handoff can rearm the same session")
        expect(commands.detected(replacement, 22_000), "The next wake can open Friday after an expired handoff")
        val gate = WakeCaptureGate()
        val starting = WakeTrialSession.Snapshot(phase = WakeTrialSession.Phase.STARTING)
        val paused = starting.copy(phase = WakeTrialSession.Phase.PAUSED, commandMode = true)
        expect(gate.canCapture(starting), "A user-started trial can acquire a free microphone")
        val panel = Any()
        val permissions = Any()
        gate.visible(panel)
        gate.visible(panel)
        expect(!gate.canCapture(starting), "A visible command panel prevents microphone acquisition")
        gate.visible(permissions)
        gate.hidden(panel)
        expect(!gate.canCapture(paused), "Dismissing one Activity cannot resume over another permission screen")
        gate.hidden(permissions)
        expect(gate.canCapture(paused), "Dismissal permits a paused command trial to resume")
        gate.beginCommand()
        gate.beginCommand()
        gate.endCommand()
        expect(!gate.canCapture(paused), "Overlapping command cleanup retains microphone ownership")
        gate.endCommand()
        gate.endCommand()
        expect(gate.canCapture(paused), "Repeated cleanup cannot make the ownership count negative")
        val firstSpeaker = Any()
        val nextSpeaker = Any()
        gate.speaking(firstSpeaker)
        gate.speaking(firstSpeaker)
        expect(gate.speechActive && !gate.canCapture(paused),
            "A confirmation keeps wake capture paused after its screen closes")
        gate.speaking(nextSpeaker)
        gate.silent(firstSpeaker)
        expect(gate.speechActive && !gate.canCapture(paused),
            "Finishing earlier speech cannot resume the microphone over its replacement")
        gate.silent(firstSpeaker)
        expect(!gate.canCapture(paused), "Duplicate completion cannot release another speaker")
        gate.silent(nextSpeaker)
        expect(!gate.speechActive && gate.canCapture(paused),
            "Completing the last confirmation permits local wake listening again")
        for (phase in listOf(WakeTrialSession.Phase.HANDOFF, WakeTrialSession.Phase.STOPPING,
            WakeTrialSession.Phase.STOPPED, WakeTrialSession.Phase.LISTENING)) {
            expect(!gate.canCapture(paused.copy(phase = phase)), "Only starting or paused trials can acquire the microphone")
        }
        val errorState = paused.copy(generation = 42)
        val recovery = WakeErrorRecovery(errorState.generation)
        val firstError = checkNotNull(recovery.schedule(errorState))
        expect(recovery.consume(firstError, errorState), "A wake-opened error can dismiss to rearm local listening")
        expect(!recovery.consume(firstError, errorState), "An error dismissal runs at most once")
        val oldError = checkNotNull(recovery.schedule(errorState))
        val newError = checkNotNull(recovery.schedule(errorState))
        expect(!recovery.consume(oldError, errorState), "A replaced error cannot dismiss the current panel")
        expect(recovery.consume(newError, errorState), "The latest error retains its dismissal")
        val cancelledError = checkNotNull(recovery.schedule(errorState))
        recovery.cancel()
        expect(!recovery.consume(cancelledError, errorState), "Cancellation invalidates the pending dismissal")
        for (phase in WakeTrialSession.Phase.entries.filter { it != WakeTrialSession.Phase.PAUSED }) {
            val changed = errorState.copy(phase = phase)
            val ticket = checkNotNull(recovery.schedule(errorState))
            expect(!recovery.consume(ticket, changed), "A transition to $phase invalidates the pending dismissal")
            expect(recovery.schedule(changed) == null, "Do not schedule recovery while $phase")
        }
        for (changed in listOf(errorState.copy(generation = 43), errorState.copy(commandMode = false))) {
            val ticket = checkNotNull(recovery.schedule(errorState))
            expect(!recovery.consume(ticket, changed), "A replacement or count-only run cannot inherit dismissal")
            expect(recovery.schedule(changed) == null, "Only the run that opened this command panel can recover")
        }
        val errorGate = WakeCaptureGate()
        val errorPanel = Any()
        errorGate.visible(errorPanel)
        errorGate.beginCommand()
        val ticket = checkNotNull(recovery.schedule(errorState))
        expect(!errorGate.canCapture(errorState), "An error never starts wake capture over the visible panel")
        expect(recovery.consume(ticket, errorState), "The timeout can dismiss the idle wake panel")
        errorGate.hidden(errorPanel)
        expect(!errorGate.canCapture(errorState), "Recovery waits for command microphone cleanup")
        errorGate.endCommand()
        expect(errorGate.canCapture(errorState), "After dismissal and cleanup local wake capture can resume")
        for (saved in listOf(null, "", "unknown", "HEY_FRIDAY", "tap")) {
            expect(WakeUsagePolicy.Mode.from(saved) == WakeUsagePolicy.Mode.TAP,
                "Missing or unknown preferences keep background listening off")
        }
        expect(WakeUsagePolicy.Mode.from("hey_friday") == WakeUsagePolicy.Mode.HEY_FRIDAY, "Restore only the explicit wake choice")
        expect(!WakeUsagePolicy.accepts(WakeUsagePolicy.Mode.TAP, 12, 12), "Switching to Tap rejects a saved Resume action")
        for (requested in listOf(-1L, 0L, 11L, 13L, Long.MAX_VALUE)) {
            expect(!WakeUsagePolicy.accepts(WakeUsagePolicy.Mode.HEY_FRIDAY, 12, requested), "Reject stale, missing or future notification actions")
        }
        expect(!WakeUsagePolicy.accepts(WakeUsagePolicy.Mode.HEY_FRIDAY, 0, 0), "An uninitialized revision cannot start a microphone")
        expect(WakeUsagePolicy.accepts(WakeUsagePolicy.Mode.HEY_FRIDAY, 12, 12), "An explicit current notification tap can act")
        expect(WakeUsagePolicy.threshold(false) == WakeTrialSession.DEFAULT_THRESHOLD, "Standard uses model calibration")
        expect(WakeUsagePolicy.threshold(true) == WakeTrialSession.HIGHER_SENSITIVITY_THRESHOLD, "Higher sensitivity is an explicit opt-in")
        expect(!WakeUsagePolicy.accepts(WakeUsagePolicy.Mode.HEY_FRIDAY, 13, 12), "Consuming an action prevents a duplicate tap from replaying it")
        for (phase in WakeTrialSession.Phase.entries) {
            val state = errorState.copy(phase = phase, threshold = WakeTrialSession.DEFAULT_THRESHOLD)
            expect(WakeUsagePolicy.canAdopt(state) == (phase != WakeTrialSession.Phase.STOPPED && phase != WakeTrialSession.Phase.STOPPING),
                "A choice can adopt a command trial only before cleanup")
            expect(!WakeUsagePolicy.canAdopt(state.copy(commandMode = false)), "A count-only trial cannot silently become command mode")
            val higher = state.copy(threshold = WakeTrialSession.HIGHER_SENSITIVITY_THRESHOLD)
            expect(!WakeUsagePolicy.canAdopt(higher), "Standard cannot adopt a higher-sensitivity trial")
            expect(WakeUsagePolicy.canAdopt(higher, WakeUsagePolicy.threshold(true)) ==
                (phase != WakeTrialSession.Phase.STOPPED && phase != WakeTrialSession.Phase.STOPPING),
                "A trial can be adopted at the explicitly saved higher sensitivity")
            expect(WakeUsagePolicy.status(state).isNotBlank(), "Each microphone lifecycle state has a notification label")
        }
        expect(WakeUsagePolicy.status(errorState.copy(phase = WakeTrialSession.Phase.PAUSED)) == "Friday is open",
            "Temporary assistant handoff does not claim the user stopped listening")
        expect(WakeUsagePolicy.status(errorState.copy(phase = WakeTrialSession.Phase.STOPPED)) == "Paused",
            "The stopped control shows Paused only after recorder cleanup")
        expect(WakeUsagePolicy.retainsNotification(true, WakeUsagePolicy.Mode.HEY_FRIDAY),
            "Pause retains a separate normal notification after removing the foreground notification")
        expect(!WakeUsagePolicy.retainsNotification(true, WakeUsagePolicy.Mode.TAP),
            "Tap mode removes the wake notification after cleanup")
        val heyFriday = WakeUsagePolicy.Mode.HEY_FRIDAY
        val tapToSpeak = WakeUsagePolicy.Mode.TAP
        expect(WakeUsagePolicy.assistantSetup(selected = true, connected = false, waitExpired = false) == WakeUsagePolicy.AssistantSetup.WAIT,
            "A cold assistant binding gets time to connect after selecting Friday or restarting the app")
        expect(WakeUsagePolicy.assistantSetup(selected = true, connected = false, waitExpired = true) == WakeUsagePolicy.AssistantSetup.RECONNECT,
            "Offer reconnection if the selected assistant stays disconnected")
        expect(WakeUsagePolicy.assistantSetup(selected = true, connected = true, waitExpired = true) == WakeUsagePolicy.AssistantSetup.READY,
            "A late binding immediately restores the normal start action")
        expect(WakeUsagePolicy.assistantSetup(selected = false, connected = true, waitExpired = true) == WakeUsagePolicy.AssistantSetup.SELECT,
            "A callback from the old binding cannot substitute for current assistant selection")
        val setupReturn = WakeUsagePolicy.Continuation()
        expect(!setupReturn.takeIfResumed(true), "Opening settings never starts wake listening on its own")
        setupReturn.pending = true
        expect(!setupReturn.takeIfResumed(false) && setupReturn.pending,
            "A permission or sign-in result stays pending while the screen is paused")
        val restored = WakeUsagePolicy.Continuation(pending = setupReturn.pending)
        expect(restored.takeIfResumed(true) && !restored.takeIfResumed(true),
            "Restoring and resuming continues the requested setup once")
        expect(setupReturn.takeIfResumed(true), "A result delivered after resume continues immediately")
        expect(WakeUsagePolicy.showResumeAfterUpdate(heyFriday, consented = true, modelAvailable = true, listening = false),
            "An update restores the resume control for an opted-in and paused listener")
        expect(!WakeUsagePolicy.showResumeAfterUpdate(tapToSpeak, consented = true, modelAvailable = true, listening = false),
            "An update respects Tap to speak even if Hey Friday was previously enabled")
        expect(!WakeUsagePolicy.showResumeAfterUpdate(heyFriday, consented = false, modelAvailable = true, listening = false),
            "An update does not offer background listening without consent")
        expect(!WakeUsagePolicy.showResumeAfterUpdate(heyFriday, consented = true, modelAvailable = false, listening = false),
            "An APK without a wake model does not offer the update resume control")
        expect(!WakeUsagePolicy.showResumeAfterUpdate(heyFriday, consented = true, modelAvailable = true, listening = true),
            "A late update broadcast cannot replace an active listener's notification")
        for (mode in WakeUsagePolicy.Mode.entries) {
            expect(!WakeUsagePolicy.retainsNotification(false, mode), "An advanced trial retains its original Stop behavior")
        }
        expect(!WakeBackgroundPolicy.stopOnTaskRemoval(true, WakeUsagePolicy.Mode.HEY_FRIDAY),
            "Removing a task keeps the user-enabled Hey Friday service alive")
        expect(WakeBackgroundPolicy.stopOnTaskRemoval(true, WakeUsagePolicy.Mode.TAP),
            "An earlier Hey Friday service cannot survive a later Tap choice")
        for (mode in WakeUsagePolicy.Mode.entries) {
            expect(WakeBackgroundPolicy.stopOnTaskRemoval(false, mode),
                "Removing an advanced trial's task stops its microphone")
        }
        for (maker in listOf("vivo", "VIVO", " Vivo ", "iQOO")) {
            expect(WakeBackgroundPolicy.isVivo(maker), "Vivo background guidance handles manufacturer formatting")
        }
        expect(!WakeBackgroundPolicy.isVivo("Samsung"), "Vivo guidance stays off other manufacturers")
        val battery = WakeBackgroundPolicy.Destination.APP_BATTERY
        val optimization = WakeBackgroundPolicy.Destination.BATTERY_OPTIMIZATION
        val appInfo = WakeBackgroundPolicy.Destination.APP_INFO
        val settings = WakeBackgroundPolicy.Destination.SETTINGS
        for ((unavailable, expected) in listOf(
            emptySet<WakeBackgroundPolicy.Destination>() to battery,
            setOf(battery) to optimization,
            setOf(battery, optimization) to appInfo,
            setOf(battery, optimization, appInfo) to settings,
            setOf(battery, optimization, appInfo, settings) to null,
        )) {
            val visited = mutableListOf<WakeBackgroundPolicy.Destination>()
            val destination = WakeBackgroundPolicy.firstAvailable(battery) { target ->
                visited += target
                if (target in unavailable) throw SecurityException("Settings screen unavailable")
            }
            expect(destination == expected, "Missing or blocked battery screens fall back without crashing")
            expect(visited == WakeBackgroundPolicy.Destination.entries.take(unavailable.size + 1),
                "Battery fallback does not retry or skip the next public settings screen")
        }
        val visited = mutableListOf<WakeBackgroundPolicy.Destination>()
        expect(WakeBackgroundPolicy.firstAvailable(settings) { visited += it } == settings,
            "Manufacturer guidance opens only the requested phone Settings")
        expect(visited == listOf(settings), "Opening phone Settings cannot request an exemption")
        checks += WakeModelInstallerTests.run()
        println("Wake trial logic: $checks checks passed")
    }
}
