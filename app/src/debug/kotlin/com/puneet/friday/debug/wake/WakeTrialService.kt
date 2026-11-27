package com.puneet.friday.debug.wake

import com.puneet.friday.voice.MicrophoneUnavailable
import com.puneet.friday.voice.PcmFrameReader

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.puneet.friday.R
import com.puneet.friday.entry.FridayEntryPoints
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch

/** User-started debug listener. A detection opens the existing visible command flow. */
class WakeTrialService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val resumeLock = Any()
    private var resumeScheduled = false
    private val resume = Runnable {
        synchronized(resumeLock) { resumeScheduled = false }
        startCaptureIfReady()
    }
    private var forceFallbackForTest = false
    private var outcomeForTest: String? = null
    private var usageControlled = false
    private var microphoneRetry = WakeMicrophoneRetry()
    private var lastControl = "none"
    private var wakeCue: ToneGenerator? = null
    @Volatile private var scoreProbe: WakeScoreProbe? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val stop = WakeBackgroundPolicy.stopOnTaskRemoval(usageControlled, WakeUsageStore.mode(this))
        Log.i(TAG, "event=task_removed keepListening=${!stop}")
        if (stop) stopTest()
        super.onTaskRemoved(rootIntent)
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(captureLock) { owner = this }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!com.puneet.friday.auth.AccountStore.isSignedIn(this)) {
            stopTest("Sign in to Friday before starting Hey Friday.")
            return START_NOT_STICKY
        }
        if (intent?.action == PAUSE_USAGE) {
            val accepted = usageControlled && session.snapshot().active &&
                WakeUsageStore.consume(this, intent.getLongExtra(USAGE_REVISION, -1))
            lastControl = if (accepted) "pause_accepted" else "pause_rejected"
            Log.i(TAG, "event=usage_control result=$lastControl")
            if (accepted) stopTest() else if (!session.snapshot().active) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            stopTest()
            return START_NOT_STICKY
        }
        if (intent?.action != START) {
            if (!session.snapshot().active) stopSelf()
            return START_NOT_STICKY
        }
        val controlRequest = intent.hasExtra(USAGE_REVISION)
        if (controlRequest && !WakeUsageStore.consume(this, intent.getLongExtra(USAGE_REVISION, -1))) {
            lastControl = "resume_rejected"
            Log.i(TAG, "event=usage_control result=$lastControl")
            if (!session.snapshot().active) stopSelf()
            return START_NOT_STICKY
        }
        if (controlRequest) {
            lastControl = "resume_accepted"
            Log.i(TAG, "event=usage_control result=$lastControl")
        }
        val threshold = if (controlRequest) WakeUsageStore.threshold(this)
            else intent.getFloatExtra(THRESHOLD, WakeTrialSession.DEFAULT_THRESHOLD)
        if (controlRequest && !WakeAssistantBridge.isReady(this)) {
            if (session.snapshot().active) {
                if (usageControlled) stopTest("Reconnect Friday to use Hey Friday.")
            } else {
                WakeUsageNotification.showPaused(this, openSetup = true)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (session.snapshot().active) {
            if (controlRequest && WakeUsagePolicy.canAdopt(session.snapshot(), threshold)) {
                usageControlled = true
                WakeUsageNotification.hidePaused(this)
                updateNotification()
            }
            return START_NOT_STICKY
        }
        usageControlled = controlRequest
        if (threshold !in WakeTrialSession.THRESHOLDS || !permissionsAllowed()) {
            session.finish(session.snapshot().generation, 0, "Allow microphone and notifications to start the test.")
            if (usageControlled) WakeUsageNotification.showPaused(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val generation = synchronized(captureLock) {
            val commandMode = intent.getBooleanExtra(COMMAND_MODE, false)
            if (capture != null || (gate.commandActive && !commandMode)) null
            else session.begin(threshold, commandMode)
        }
        if (generation == null) {
            if (!session.snapshot().active) {
                if (usageControlled) WakeUsageNotification.showPaused(this)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        try {
            microphoneRetry = WakeMicrophoneRetry()
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification)
            if (usageControlled) WakeUsageNotification.hidePaused(this)
            if (session.snapshot().commandMode) {
                wakeCue = try {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
                } catch (_: RuntimeException) {
                    null
                }
                com.puneet.friday.voice.ConfirmationSpeaker.warmUp(this)
            }
            Log.i(TAG, "event=start run=$generation cutoff=$threshold commands=${session.snapshot().commandMode}")
            startCaptureIfReady()
        } catch (_: RuntimeException) {
            stopTest("Couldn't start the microphone. Reopen this screen and try again.")
        }
        return START_NOT_STICKY
    }

    private fun permissionsAllowed(): Boolean {
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        WakeUsageNotification.ensureChannel(manager)
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun startCaptureIfReady() {
        val next = synchronized(captureLock) {
            val state = session.snapshot()
            if (owner !== this) return
            if (state.phase != WakeTrialSession.Phase.STARTING && state.phase != WakeTrialSession.Phase.PAUSED) return
            if (capture != null) return
            if (!gate.canCapture(state)) {
                if (state.commandMode) session.pause(state.generation) else stopTest("Stopped for Friday's assistant")
                return
            }
            if (!permissionsAllowed()) {
                stopTest("Allow microphone and notifications to start the test.")
                return
            }
            if (!session.startingMicrophone(state.generation)) return
            Capture(state.generation, state.threshold).also { capture = it }
        }
        updateNotification()
        Thread(next, "FridayWakeTrial").start()
    }

    private fun pauseForCommand(preservePendingWake: Boolean = false) {
        val state = session.snapshot()
        if (!state.active) return
        if (!state.commandMode) { stopTest("Stopped for Friday's assistant"); return }
        if (!preservePendingWake || state.phase != WakeTrialSession.Phase.HANDOFF) session.pause(state.generation)
        synchronized(captureLock) { capture }?.cancel()
        cancelResume()
        updateNotification()
    }

    private fun resumeAfterCommand() {
        val state = session.snapshot()
        if (!state.commandMode || state.phase != WakeTrialSession.Phase.PAUSED || synchronized(captureLock) { gate.occupied }) return
        scheduleCapture(REARM_DELAY_MS)
    }

    private fun scheduleCapture(delayMs: Long) {
        synchronized(resumeLock) {
            if (!resumeScheduled) {
                resumeScheduled = true
                main.postDelayed(resume, delayMs)
            }
        }
    }

    private fun cancelResume() = synchronized(resumeLock) {
        main.removeCallbacks(resume)
        resumeScheduled = false
    }

    private fun stopTest(message: String = "Microphone off") {
        cancelResume()
        val state = session.snapshot()
        session.stopping(state.generation, message)
        val previous = synchronized(captureLock) { capture }
        if (previous == null) {
            finishRun(message)
        } else {
            previous.cancel()
            updateNotification()
        }
    }

    private fun finishRun(message: String) {
        val state = session.snapshot()
        session.finish(state.generation, elapsed(state), message)
        val final = session.snapshot()
        Thread({ WakeTrialReports.save(filesDir, final) }, "FridayWakeReport").start()
        Log.i(TAG, "event=stop run=${final.generation} cutoff=${final.threshold} commands=${final.commandMode} elapsedMs=${final.elapsedMs} audioMs=${final.audioMs} computeMs=${final.computeMs} detections=${final.detections} opened=${final.openedCommands}")
        // The paused control uses another ID so Android's asynchronous
        // foreground-service cancellation cannot erase it.
        val retain = WakeUsagePolicy.retainsNotification(usageControlled, WakeUsageStore.mode(this))
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (retain) WakeUsageNotification.showPaused(this)
        stopSelf()
    }

    private fun afterCapture(generation: Long, failure: IllegalStateException?, audioMs: Long = 0) {
        if (synchronized(captureLock) { owner !== this } || session.snapshot().generation != generation) return
        val state = session.snapshot()
        if (!state.active) return
        when {
            state.phase == WakeTrialSession.Phase.STOPPING -> finishRun(state.message)
            state.phase == WakeTrialSession.Phase.HANDOFF -> {
                val token = session.claimHandoff(generation) ?: return
                session.awaitingTap(generation)
                updateNotification()
                val forceFallback = forceFallbackForTest
                val testOutcome = outcomeForTest
                forceFallbackForTest = false
                outcomeForTest = null
                requestAssistant(generation, token, forceFallback, testOutcome)
                main.postDelayed({
                    if (session.expireWake(generation, token)) {
                        Log.i(TAG, "event=handoff_expired run=$generation")
                        if (usageControlled && !WakeAssistantBridge.isReady(this)) {
                            finishRun("Reconnect Friday to use Hey Friday.")
                        } else startCaptureIfReady()
                    }
                }, HANDOFF_TIMEOUT_MS)
            }
            state.phase == WakeTrialSession.Phase.PAUSED -> { updateNotification(); resumeAfterCommand() }
            failure is MicrophoneUnavailable -> {
                val delay = microphoneRetry.nextDelayMs(audioMs)
                if (delay == null) {
                    finishRun(checkNotNull(failure.message))
                } else if (session.startingMicrophone(generation, retry = true)) {
                    Log.i(TAG, "event=microphone_retry run=$generation delayMs=$delay")
                    updateNotification()
                    scheduleCapture(delay)
                }
            }
            failure != null -> finishRun(failure.message ?: "Detector stopped. Try again.")
            else -> finishRun("Microphone off")
        }
    }

    private fun requestAssistant(generation: Long, token: String, forceFallback: Boolean, testOutcome: String?) {
        val retry = WakeAssistantRetry()
        var attempts = 0
        fun attempt() {
            if (session.snapshot().generation != generation || !session.isPendingWake(token)) return
            attempts++
            val requested = !forceFallback && WakeAssistantBridge.request(this, token, testOutcome)
            if (requested) {
                Log.i(TAG, "event=handoff run=$generation requested=true attempts=$attempts")
                return
            }
            val delay = if (forceFallback) null else retry.nextDelayMs()
            if (delay != null) {
                main.postDelayed({ attempt() }, delay)
            } else {
                Log.i(TAG, "event=handoff run=$generation requested=false attempts=$attempts")
            }
        }
        attempt()
    }

    private fun updateNotification() {
        if (session.snapshot().active) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val state = session.snapshot()
        if (usageControlled) return WakeUsageNotification.build(this, state)
        val token = state.pendingWake
        val openIntent = if (token != null) WakeAssistantBridge.tag(FridayEntryPoints.listenIntent(this), token)
            else Intent(this, WakeTrialActivity::class.java)
        val open = PendingIntent.getActivity(this, 61, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 62, Intent(this, WakeTrialService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = when (state.phase) {
            WakeTrialSession.Phase.HANDOFF -> "Wake heard · Tap to speak"
            WakeTrialSession.Phase.PAUSED -> "Paused while Friday is open · Tap to view"
            WakeTrialSession.Phase.STOPPING -> "Stopping microphone"
            else -> "microWakeWord · ${state.detections} detections · Tap to view"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_friday_status)
            .setContentTitle(if (state.phase == WakeTrialSession.Phase.PAUSED || state.phase == WakeTrialSession.Phase.HANDOFF)
                "Friday wake test is paused" else "Friday wake test is using the microphone")
            .setContentText(content).setContentIntent(open).setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_friday_status), "Stop test", stop).build())
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        val now = SystemClock.elapsedRealtime()
        when (args?.firstOrNull()) {
            "wake-probe-start" -> scoreProbe = WakeScoreProbe(now)
            "wake-probe-clear" -> scoreProbe = null
        }
        val state = session.snapshot()
        val result = WakeTrialReports.json(state).put("elapsedMs", elapsed(state))
        synchronized(captureLock) {
            result.put("wakeCapture", capture != null).put("commandCapture", gate.commandActive)
                .put("speechPlayback", gate.speechActive)
                .put("visibleAssistants", gate.visibleCount)
                .put("liveWakeAudioMs", capture?.liveAudioMs ?: 0L)
        }
        scoreProbe?.snapshot(now)?.let { result.put("wakeScoreProbe", it) }
        result.put("usageControlled", usageControlled).put("usageMode", WakeUsageStore.mode(this).value).put("lastControl", lastControl)
        writer.println(result.toString())
    }

    override fun onDestroy() {
        wakeCue?.release()
        wakeCue = null
        // The capture worker's queued completion still needs to release its slot.
        cancelResume()
        val state = session.snapshot()
        val previous = synchronized(captureLock) { if (owner === this) owner = null; capture }
        if (state.active) {
            session.stopping(state.generation, "Microphone off")
            val retain = WakeUsagePolicy.retainsNotification(usageControlled, WakeUsageStore.mode(this))
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (previous == null) {
                session.finish(state.generation, elapsed(state), "Microphone off")
                val final = session.snapshot()
                Thread({ WakeTrialReports.save(filesDir, final) }, "FridayWakeReport").start()
                if (retain) WakeUsageNotification.showPaused(this)
            } else previous.cancel()
        }
        super.onDestroy()
    }

    private inner class Capture(val generation: Long, private val threshold: Float) : Runnable {
        @Volatile var liveAudioMs = 0L
            private set
        val done = CountDownLatch(1)
        @Volatile private var cancelled = false

        fun cancel() {
            cancelled = true
        }

        override fun run() {
            var audio: AudioRecord? = null
            var detector: WakeDetector? = null
            var trace: WakeAudioTrace.Capture? = null
            var samples = 0L
            var computeNanos = 0L
            var failure: IllegalStateException? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
                if (cancelled) return
                check(FridayKeywordModel.available(assets)) { FridayKeywordModel.MISSING }
                val model = FridayKeywordModel.load(assets)
                val keyword = FridayKeywordDetector(model, threshold) { margin, streak ->
                    scoreProbe?.record(SystemClock.elapsedRealtime(), margin, streak)
                }
                detector = keyword
                val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) throw MicrophoneUnavailable()
                if (cancelled) return
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 2_560))
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw MicrophoneUnavailable()
                if (cancelled) return
                try {
                    audio.startRecording()
                } catch (_: IllegalStateException) {
                    throw MicrophoneUnavailable()
                }
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicrophoneUnavailable()
                trace = WakeAudioTrace.begin(this@WakeTrialService)
                PcmFrameReader.readFrames(
                    read = { buffer, offset, length -> audio.read(buffer, offset, length, AudioRecord.READ_NON_BLOCKING) },
                    nowMs = SystemClock::uptimeMillis,
                    cancelled = { cancelled },
                    waitForAudio = Thread::sleep,
                ) { frame ->
                    if (cancelled) return@readFrames false
                    if (samples == 0L) {
                        val listening = synchronized(captureLock) {
                            !cancelled && !gate.occupied && session.listening(generation, SystemClock.elapsedRealtime())
                        }
                        if (!listening) return@readFrames false
                        Log.i(TAG, "event=listening run=$generation cutoff=${model.cutoffFor(threshold)} detector=friday_keyword")
                        main.post { if (session.snapshot().generation == generation) updateNotification() }
                    }
                    samples += frame.size
                    liveAudioMs = samples * 1_000 / 16_000
                    val before = System.nanoTime()
                    val detected = keyword.process(frame, frame.size)
                    computeNanos += System.nanoTime() - before
                    val accepted = !cancelled && detected && session.detected(generation, elapsed(session.snapshot()))
                    trace?.append(frame, if (accepted) keyword.wakeSampleCount else frame.size, accepted)
                    if (accepted) {
                        Log.i(TAG, "event=wake run=$generation atMs=${session.snapshot().lastDetectionMs} cutoff=${model.cutoffFor(threshold)}")
                        if (session.snapshot().commandMode) return@readFrames false
                        main.post { if (session.snapshot().generation == generation) updateNotification() }
                    }
                    true
                }
            } catch (_: SecurityException) {
                failure = IllegalStateException("Microphone permission needed. Allow it in App permissions.")
            } catch (error: IllegalStateException) {
                if (!cancelled) failure = error
            } catch (_: Exception) {
                failure = IllegalStateException("Couldn't load Hey Friday or start the microphone.")
            } finally {
                trace?.close()
                try { audio?.stop() } catch (_: RuntimeException) { Unit }
                try {
                    audio?.release()
                } catch (_: RuntimeException) {
                    failure = IllegalStateException("Couldn't release the microphone. Try again.")
                }
                try { detector?.close() } catch (_: RuntimeException) {
                    failure = IllegalStateException("Detector stopped. Try again.")
                }
                session.addCompute(generation, samples * 1_000 / 16_000, computeNanos / 1_000_000)
                done.countDown()
                main.post {
                    // Retain the slot until its completion is handled. A late completion
                    // must never stop a newer capture in the same wake run.
                    synchronized(captureLock) {
                        if (capture !== this) return@post
                        capture = null
                    }
                    if (synchronized(captureLock) { owner !== this@WakeTrialService } && session.snapshot().generation == generation) {
                        val state = session.snapshot()
                        session.finish(generation, elapsed(state), state.message)
                        val final = session.snapshot()
                        Thread({ WakeTrialReports.save(filesDir, final) }, "FridayWakeReport").start()
                        if (usageControlled) WakeUsageNotification.showPaused(this@WakeTrialService)
                    } else afterCapture(generation, failure, liveAudioMs)
                }
            }
        }
    }

    internal companion object {
        const val START = "com.puneet.friday.debug.START_WAKE_TEST"
        const val STOP = "com.puneet.friday.debug.STOP_WAKE_TEST"
        const val PAUSE_USAGE = "com.puneet.friday.debug.PAUSE_WAKE_USAGE"
        const val USAGE_REVISION = "wake_usage_revision"
        const val THRESHOLD = "threshold"
        const val COMMAND_MODE = "commands"
        const val CHANNEL_ID = "friday_wake_trial"
        const val NOTIFICATION_ID = 61
        private const val TAG = "FridayWakeTrial"
        private const val HANDOFF_TIMEOUT_MS = 8_000L
        private const val REARM_DELAY_MS = 150L
        val session = WakeTrialSession()
        private val captureLock = Any()
        private var owner: WakeTrialService? = null
        private var capture: WakeTrialService.Capture? = null
        private val gate = WakeCaptureGate()

        private fun elapsed(state: WakeTrialSession.Snapshot): Long =
            if (state.active && state.startedMs > 0) SystemClock.elapsedRealtime() - state.startedMs else state.elapsedMs

        fun playWakeCue() {
            val service = synchronized(captureLock) { owner } ?: return
            val played = try {
                service.wakeCue?.startTone(ToneGenerator.TONE_PROP_BEEP, 40) == true
            } catch (_: RuntimeException) {
                false
            }
            Log.i(TAG, "event=wake_cue started=$played")
        }

        fun stopForAssistant(message: String = "Microphone off") {
            synchronized(captureLock) { owner }?.let { service -> service.main.post { service.stopTest(message) } }
        }

        fun assistantVisible(activity: Activity, preservePendingWake: Boolean = false) {
            val service = synchronized(captureLock) { gate.visible(activity); owner }
            service?.pauseForCommand(preservePendingWake)
        }

        fun assistantHidden(activity: Activity) {
            val service = synchronized(captureLock) { gate.hidden(activity); owner }
            service?.resumeAfterCommand()
        }

        fun beginSpeechPlayback(speaker: Any) {
            val service = synchronized(captureLock) { gate.speaking(speaker); owner }
            service?.pauseForCommand()
        }

        fun endSpeechPlayback(speaker: Any) {
            val service = synchronized(captureLock) { gate.silent(speaker); owner }
            service?.resumeAfterCommand()
        }

        fun beginCommandCapture() {
            val previous = synchronized(captureLock) {
                gate.beginCommand()
                val state = session.snapshot()
                if (state.commandMode) session.pause(state.generation)
                else if (state.active) session.stopping(state.generation, "Stopped for phone command")
                capture
            }
            try {
                previous?.cancel()
                WakeTrialHandoff.awaitStopped(previous?.done)
            } catch (error: RuntimeException) { endCommandCapture(); throw error }
        }

        fun endCommandCapture() {
            val service = synchronized(captureLock) { gate.endCommand(); owner }
            service?.main?.post { service.resumeAfterCommand() }
        }

        /** Exercises handoff without claiming a spoken phrase matched the model. Debug source set only. */
        fun simulateWakeForTest(forceFallback: Boolean = false, outcome: String? = null) {
            val state = session.snapshot()
            if (!state.commandMode || !session.detected(state.generation, elapsed(state), synthetic = true)) return
            val service = synchronized(captureLock) { capture?.cancel(); owner }
            service?.forceFallbackForTest = forceFallback
            service?.outcomeForTest = outcome
            Log.i(TAG, "event=synthetic_wake run=${state.generation}")
            if (synchronized(captureLock) { capture == null }) service?.afterCapture(state.generation, null)
        }
    }
}
