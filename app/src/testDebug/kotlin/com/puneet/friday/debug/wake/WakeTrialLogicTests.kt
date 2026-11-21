package com.puneet.friday.debug.wake

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
        val first = checkNotNull(session.begin(0.66f))
        expect(session.snapshot().phase == WakeTrialSession.Phase.STARTING, "Initialization shows Starting")
        expect(session.begin(0.30f) == null, "Repeated Start never replaces a running detector")
        expect(!session.detected(first, 10), "Warm-up detections never count")
        expect(session.listening(first, 100), "The current detector can enter Listening")
        expect(session.detected(first, 900), "A live detection counts")
        expect(session.snapshot().detections == 1 && session.snapshot().lastDetectionMs == 900L,
            "The result tracks a count and time, without a transcript")
        session.stopping(first, "Microphone off")
        expect(session.snapshot().active, "Stop retains microphone ownership until cleanup finishes")
        expect(!session.detected(first, 1_000), "No detection after Stop")
        expect(!session.listening(first, 200), "Delayed initialization cannot undo Stop")
        expect(session.begin(0.30f) == null, "Start waits for previous cleanup")
        session.finish(first, 1_200, "Microphone off")
        expect(!session.snapshot().active && session.snapshot().elapsedMs == 1_200L, "Cleanup leaves stopped results readable")
        val second = checkNotNull(session.begin(0.30f))
        expect(second != first && session.snapshot().detections == 0, "A new trial resets its count")
        expect(!session.detected(first, 1_300) && !session.listening(first, 300), "Old callbacks cannot affect a replacement")
        session.finish(first, 0, "stale error")
        expect(session.snapshot().phase == WakeTrialSession.Phase.STARTING, "Old cleanup cannot stop a replacement")
        session.stopping(first, "stale stop")
        expect(session.snapshot().phase == WakeTrialSession.Phase.STARTING, "Stale cancellation cannot stop a replacement")
        session.stopping(second, "Cancelled while starting")
        expect(!session.listening(second, 400), "Rapid Start then Stop cannot open a new listening session")
        session.finish(second, 0, "Microphone off")
        for (cutoff in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 0.5f)) {
            expect(runCatching { session.begin(cutoff) }.isFailure, "Only measured sensitivity settings are allowed")
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
        expect(runCatching { WakeTrialHandoff.awaitStopped(null) }.isSuccess, "Capture starts when no trial owns the mic")
        expect(runCatching { WakeTrialHandoff.awaitStopped(CountDownLatch(0)) }.isSuccess, "Completed cleanup permits capture")
        expect(runCatching { WakeTrialHandoff.awaitStopped(CountDownLatch(1), 0) }.exceptionOrNull() is IllegalStateException,
            "A cleanup timeout reaches the recorder's handled failure path")
        Thread.currentThread().interrupt()
        expect(runCatching { WakeTrialHandoff.awaitStopped(CountDownLatch(1)) }.exceptionOrNull() is IllegalStateException,
            "Activity shutdown during handoff cannot escape as an unhandled checked exception")
        expect(Thread.interrupted(), "Cancellation preserves interruption and the test clears it afterward")
        val commands = WakeTrialSession()
        expect(WakeTrialSession.DEFAULT_THRESHOLD == 0.30f, "The owner chose 0.30 for command trials")
        val run = checkNotNull(commands.begin(0.30f, commandMode = true, nowMs = 100))
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
        val replacement = checkNotNull(commands.begin(0.30f, commandMode = true, nowMs = 200))
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
        expect(!WakeUsagePolicy.accepts(WakeUsagePolicy.Mode.HEY_FRIDAY, 13, 12), "Consuming an action prevents a duplicate tap from replaying it")
        for (phase in WakeTrialSession.Phase.entries) {
            val state = errorState.copy(phase = phase, threshold = WakeTrialSession.DEFAULT_THRESHOLD)
            expect(WakeUsagePolicy.canAdopt(state) == (phase != WakeTrialSession.Phase.STOPPED && phase != WakeTrialSession.Phase.STOPPING),
                "A choice can adopt a command trial only before cleanup")
            expect(!WakeUsagePolicy.canAdopt(state.copy(commandMode = false)), "A count-only trial cannot silently become command mode")
            expect(!WakeUsagePolicy.canAdopt(state.copy(threshold = 0.1f)), "The choice preserves the measured 0.30 command cutoff")
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
        for (mode in WakeUsagePolicy.Mode.entries) {
            expect(!WakeUsagePolicy.retainsNotification(false, mode), "An advanced trial retains its original Stop behavior")
        }
        checks += WakeModelInstallerTests.run()
        println("Wake trial logic: $checks checks passed")
    }
}
