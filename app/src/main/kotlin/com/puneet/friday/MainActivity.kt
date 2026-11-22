package com.puneet.friday

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.puneet.friday.actions.ActionResult
import com.puneet.friday.actions.AndroidActionExecutor
import com.puneet.friday.actions.DeviceLock
import com.puneet.friday.actions.LockedActionPolicy
import com.puneet.friday.actions.PhoneAction
import com.puneet.friday.actions.RequiredAccess
import com.puneet.friday.entry.EntryPointPolicy
import com.puneet.friday.entry.FridayEntryPoints
import com.puneet.friday.entry.ReadyNotification
import com.puneet.friday.ui.AssistantOrbView
import com.puneet.friday.ui.MicButtonView
import com.puneet.friday.ui.VoiceSurface
import com.puneet.friday.ui.ActionPresentation
import com.puneet.friday.ui.Destination
import com.puneet.friday.ui.TabHostView
import com.puneet.friday.ui.CompletionPolicy
import com.puneet.friday.auth.AccountClient
import com.puneet.friday.auth.AccountStore
import com.puneet.friday.auth.SignInActivity
import com.puneet.friday.auth.SignInRequired
import com.puneet.friday.voice.CommandResult
import com.puneet.friday.voice.ConfirmationSpeaker
import com.puneet.friday.voice.CaptureResult
import com.puneet.friday.voice.FridayBackendClient
import com.puneet.friday.voice.PcmProcessor
import com.puneet.friday.voice.RecordedCommand
import com.puneet.friday.voice.VoiceCommandResponse
import com.puneet.friday.voice.VoiceFailureCopy
import com.puneet.friday.voice.VoiceUiOutcome
import com.puneet.friday.voice.VoiceRecorder
import com.puneet.friday.voice.uiOutcome
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sin

open class MainActivity : Activity() {
    protected open val compactSurface = false
    private enum class VoiceState { IDLE, GREETING, LISTENING, PROCESSING, ACTION }

    private lateinit var orb: AssistantOrbView
    private lateinit var micButton: MicButtonView
    private lateinit var micLabel: TextView
    private lateinit var detail: TextView
    private lateinit var permissionButton: Button
    private lateinit var surface: VoiceSurface
    private var tabHost: TabHostView? = null
    private var settingsPanel: SettingsPanel? = null
    private var settingsBackRegistered = false
    private val settingsBack = if (Build.VERSION.SDK_INT >= 33) SettingsBackHandler(this) {
        selectDestination(Destination.HOME)
    } else null
    private var lastConfirmation: String? = null
    private var speaking = false
    private var speechAvailable = true
    private var speechAudioSeen = false
    private lateinit var backend: FridayBackendClient
    private lateinit var actionExecutor: AndroidActionExecutor
    private lateinit var actionRunner: VoiceActionRunner
    private lateinit var confirmationSpeaker: ConfirmationSpeaker
    private var activeRecorder: VoiceRecorder? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var state = VoiceState.IDLE
    @Volatile private var resumed = false
    @Volatile private var generation = 0
    private var listenStartedMs = 0L
    private var lastRecordMs = 0L
    private var lastUploadMs = 0L
    private var lastServerMs: Double? = null
    private var orbDemoRunnable: Runnable? = null
    private var pendingAutoListen = false
    private var setupPromptOpen = false
    private var resumeListeningAfterSetup = false
    private var unlockRequired = false
    private var accountRequired = false
    private var signInOpen = false
    private var resultDismissal: Runnable? = null
    private var resultInteracted = false
    private var greetingContinuation: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = FridayBackendClient(this)
        actionExecutor = AndroidActionExecutor(this)
        actionRunner = VoiceActionRunner(this, ::onActionResult)
        confirmationSpeaker = ConfirmationSpeaker(this, ::onSpeechState, ::onSpeechAudio,
            onAvailable = { available ->
                speechAvailable = available
                if (!available) {
                    surface.replay.visibility = View.GONE
                    if (lastConfirmation != null) scheduleResultDismissal(speaking = false, waitingForSpeech = false)
                }
            }, onFinished = ::onSpeechFinished)
        setContentView(createContentView())
        if (savedInstanceState?.getBoolean("settings_selected") == true) selectDestination(Destination.SETTINGS)
        consumeEntryPointIntent(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(DEBUG_ORB_VOICE_EXTRA, false)) startOrbVoiceDemo()
        backend.registerInBackground()
        if (!AccountStore.isSignedIn(this)) {
            showSignInRequired()
            // ADB visual fixtures never execute actions or bypass the account gate in startListening.
            val visualFixture = BuildConfig.DEBUG && intent.hasExtra("debug_ui_state")
            if (!DeviceLock.isLocked(this) && !visualFixture) launchSignIn()
        } else if (!getSharedPreferences("friday", MODE_PRIVATE).getBoolean("setup_seen", false)) {
            launchSetup(resumeAfterSetup = pendingAutoListen)
            pendingAutoListen = false
        }
    }

    override fun onResume() {
        super.onResume()
        DebugTools.onAssistantVisible(this)
        resumed = true
        logEntryLifecycle("resume")
        restoreReadyNotificationIfNeeded()
        settingsPanel?.refresh()
        if (!AccountStore.isSignedIn(this)) showSignInRequired()
        else if (accountRequired) {
            accountRequired = false
            resetIdle()
            if (!getSharedPreferences("friday", MODE_PRIVATE).getBoolean("setup_seen", false) && !setupPromptOpen) launchSetup(false)
        }
        maybeStartFromEntryPoint()
        maybeShowDebugState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DebugTools.cancelErrorRecovery(this)
        setIntent(intent)
        consumeEntryPointIntent(intent)
        maybeStartFromEntryPoint()
        maybeShowDebugState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        logEntryLifecycle(if (hasFocus) "focus" else "blur")
        if (hasFocus) {
            maybeStartFromEntryPoint()
            maybeShowDebugState()
        }
    }

    private fun maybeShowDebugState() {
        if (!BuildConfig.DEBUG || !resumed || !hasWindowFocus()) return
        intent.getStringExtra("debug_ui_state")?.let { scenario ->
            intent.removeExtra("debug_ui_state")
            showDebugState(scenario)
        }
    }

    @Deprecated("Android Activity result compatibility for the dependency-free framework UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SIGN_IN_REQUEST) {
            signInOpen = false
            if (resultCode == RESULT_OK && AccountStore.isSignedIn(this)) {
                accountRequired = false
                resetIdle()
                backend.registerInBackground()
                if (!getSharedPreferences("friday", MODE_PRIVATE).getBoolean("setup_seen", false)) launchSetup(false)
            } else showSignInRequired()
            return
        }
        if (requestCode != SETUP_REQUEST) return
        setupPromptOpen = false
        val shouldResume = resumeListeningAfterSetup || pendingAutoListen
        resumeListeningAfterSetup = false
        pendingAutoListen = resultCode == RESULT_OK && shouldResume
        maybeStartFromEntryPoint()
    }

    override fun onPause() {
        logEntryLifecycle("pause")
        val closeCompletedPanel = CompletionPolicy.closeWhenLeaving(
            compactSurface, state == VoiceState.IDLE && lastConfirmation != null,
        )
        val preserveConfirmation = closeCompletedPanel && !isChangingConfigurations &&
            confirmationSpeaker.finishAfterScreenCloses()
        resumed = false
        cancelVoiceWork(resetUi = true, preserveConfirmation = preserveConfirmation)
        DebugTools.onAssistantHidden(this)
        // Clock/call/app intents can pause us before the speech close timer fires.
        // A completed compact panel must not return as an empty listening screen.
        if (closeCompletedPanel && !isChangingConfigurations) {
            if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=success_panel_dismissed_on_pause")
            finish()
        }
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resultInteracted = true
        cancelResultDismissal()
        if (DebugTools.onAssistantInteraction(this)) surface.controlLabel.text = RETRY_CONTROL_LABEL
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settings_selected", tabHost?.destination == Destination.SETTINGS)
        super.onSaveInstanceState(outState)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            tabHost?.destination == Destination.SETTINGS) {
            selectDestination(Destination.HOME)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        settingsPanel?.onPermissionResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        if (settingsBackRegistered) settingsBack?.unregister()
        generation++
        activeRecorder?.stop()
        activeRecorder = null
        orbDemoRunnable?.let(orb::removeCallbacks)
        orbDemoRunnable = null
        actionRunner.cancel()
        confirmationSpeaker.shutdown()
        worker.shutdownNow()
        DebugTools.onAssistantHidden(this)
        super.onDestroy()
    }

    private fun createContentView(): View {
        surface = VoiceSurface(
            this, compactSurface,
            onMic = ::onMicPressed,
            onCancel = { cancelVoiceWork(resetUi = true) },
            onPermissions = { if (accountRequired) launchSignIn() else if (unlockRequired) requestPhoneUnlock() else launchSetup(resumeAfterSetup = false) },
            onClose = { finish() },
            onReplay = { lastConfirmation?.let(confirmationSpeaker::speak) },
        )
        orb = surface.orb
        micButton = surface.mic
        micLabel = surface.title
        detail = surface.detail
        permissionButton = surface.permission
        if (compactSurface) return surface
        return TabHostView(this, surface, createSettings = {
            SettingsPanel(this, onSignOut = ::signOut) { launchSetup(resumeAfterSetup = false) }.also {
                settingsPanel = it
                it.refresh()
            }.view
        }, onSelect = ::selectDestination).also { tabHost = it }
    }

    private fun selectDestination(destination: Destination) {
        val host = tabHost ?: return
        if (host.destination == destination) return
        if (destination == Destination.SETTINGS) cancelVoiceWork(resetUi = state != VoiceState.IDLE)
        host.select(destination)
        settingsPanel?.refresh()
        if (Build.VERSION.SDK_INT >= 33) {
            if (destination == Destination.SETTINGS && !settingsBackRegistered) {
                settingsBack?.register()
                settingsBackRegistered = true
            } else if (destination == Destination.HOME && settingsBackRegistered) {
                settingsBack?.unregister()
                settingsBackRegistered = false
            }
        }
    }

    private fun consumeEntryPointIntent(source: Intent) {
        if (source.getBooleanExtra(FridayEntryPoints.EXTRA_START_LISTENING, false)) {
            // A screen-off launch can pause before its window gains focus. Keep
            // the single-use wake token until the command UI can accept capture.
            pendingAutoListen = true
            source.removeExtra(FridayEntryPoints.EXTRA_START_LISTENING)
        }
    }

    private fun maybeStartFromEntryPoint() {
        if (!resumed || !hasWindowFocus() || !pendingAutoListen || state != VoiceState.IDLE) return
        if (!AccountStore.isSignedIn(this)) {
            pendingAutoListen = false
            showSignInRequired()
            return
        }
        val wakeEntry = DebugTools.isWakeEntry(intent)
        if (!DebugTools.allowAutoListen(this, intent)) {
            pendingAutoListen = false
            return
        }
        if (BuildConfig.DEBUG && intent.hasExtra("debug_ui_state")) {
            pendingAutoListen = false
            maybeShowDebugState()
            return
        }
        selectDestination(Destination.HOME)
        val setupSeen = getSharedPreferences("friday", MODE_PRIVATE).getBoolean("setup_seen", false)
        val microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (EntryPointPolicy.shouldRequestSetup(setupSeen, setupPromptOpen)) {
            pendingAutoListen = false
            launchSetup(resumeAfterSetup = true)
        } else if (EntryPointPolicy.shouldAutoListen(true, setupSeen, microphoneGranted)) {
            pendingAutoListen = false
            if (wakeEntry) startWakeGreeting() else startListening()
        } else if (setupSeen) {
            pendingAutoListen = false
            showPermission(RequiredAccess.MICROPHONE)
        }
    }

    private fun logEntryLifecycle(event: String) {
        if (BuildConfig.DEBUG) {
            Log.i(VOICE_LOG_TAG, "entry=$event compact=$compactSurface resumed=$resumed focused=${hasWindowFocus()} pending=$pendingAutoListen")
        }
    }

    @Suppress("DEPRECATION")
    private fun launchSetup(resumeAfterSetup: Boolean) {
        if (DeviceLock.isLocked(this)) {
            showUnlockRequired()
            return
        }
        if (resumeAfterSetup) resumeListeningAfterSetup = true
        if (setupPromptOpen) return
        setupPromptOpen = true
        startActivityForResult(Intent(this, PermissionsActivity::class.java), SETUP_REQUEST)
    }

    private fun restoreReadyNotificationIfNeeded() {
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (EntryPointPolicy.shouldRestoreReadyNotification(ReadyNotification.isEnabled(this), notificationsAllowed)) {
            ReadyNotification.show(this)
        }
    }

    private fun onMicPressed() {
        if (state == VoiceState.GREETING) {
            confirmationSpeaker.stop()
            finishWakeGreeting()
            return
        }
        if (speaking) {
            confirmationSpeaker.stop()
            return
        }
        when (state) {
            VoiceState.IDLE -> startListening()
            VoiceState.LISTENING -> {
                micLabel.text = "Finishing recording"
                detail.text = "One moment."
                micButton.isEnabled = false
                surface.controlLabel.text = "Finishing"
                activeRecorder?.stop()
            }
            VoiceState.PROCESSING, VoiceState.ACTION -> cancelVoiceWork(resetUi = true)
            VoiceState.GREETING -> Unit
        }
    }

    private fun startWakeGreeting() {
        cancelVoiceWork(resetUi = true)
        state = VoiceState.GREETING
        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=wake_greeting_started")
        detail.text = WAKE_GREETING
        detail.setTextColor(getColor(R.color.text_primary))
        micLabel.text = "Friday"
        surface.controlLabel.text = "Tap to speak now"
        surface.cancel.visibility = View.VISIBLE
        micButton.contentDescription = "Skip greeting"
        backend.warmAsync()
        // Bound engine startup; a missing or stalled TTS engine must not block capture.
        greetingContinuation = Runnable { finishWakeGreeting() }.also { surface.postDelayed(it, 6_000) }
        confirmationSpeaker.speak(WAKE_GREETING)
    }

    private fun finishWakeGreeting() {
        if (!resumed || state != VoiceState.GREETING) return
        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=wake_greeting_finished")
        state = VoiceState.IDLE
        confirmationSpeaker.stop()
        greetingContinuation?.let(surface::removeCallbacks)
        val request = generation
        // Leave a short speaker-tail gap before opening AudioRecord.
        greetingContinuation = Runnable {
            greetingContinuation = null
            if (isCurrent(request) && state == VoiceState.IDLE) startListening(warmBackend = false)
        }.also { surface.postDelayed(it, 180) }
    }

    private fun startListening(warmBackend: Boolean = true) {
        if (!resumed) return
        if (!AccountStore.isSignedIn(this)) { showSignInRequired(); return }
        ConfirmationSpeaker.stopPlayback()
        cancelResultDismissal()
        greetingContinuation?.let(surface::removeCallbacks)
        greetingContinuation = null
        state = VoiceState.IDLE
        resultInteracted = true
        unlockRequired = false
        DebugTools.cancelErrorRecovery(this)
        confirmationSpeaker.stop()
        lastConfirmation = null
        surface.showRequest(null)
        surface.replay.visibility = View.GONE
        surface.cancel.visibility = View.VISIBLE
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showPermission(RequiredAccess.MICROPHONE)
            return
        }
        val request = ++generation
        state = VoiceState.LISTENING
        listenStartedMs = SystemClock.elapsedRealtime()
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(true)
        micButton.showStop(true)
        surface.controlLabel.text = "Tap to finish"
        micButton.contentDescription = "Stop listening"
        micLabel.text = "Starting microphone…"
        detail.text = "Wait for Listening."
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.LISTENING)
        if (warmBackend) backend.warmAsync()
        val commandRecorder = VoiceRecorder(applicationContext)
        activeRecorder = commandRecorder
        worker.execute {
            val capture = try {
                commandRecorder.record(
                    onReady = { runIfCurrent(request) { showListeningReady() } },
                    onLevel = { level -> updateAudioLevel(request, level) },
                )
            } catch (_: SecurityException) {
                runIfCurrent(request) {
                    clearRecorder(commandRecorder)
                    showPermission(RequiredAccess.MICROPHONE)
                }
                return@execute
            } catch (_: RuntimeException) {
                runIfCurrent(request) {
                    clearRecorder(commandRecorder)
                    showError("Microphone unavailable")
                }
                return@execute
            }
            runIfCurrent(request) {
                clearRecorder(commandRecorder)
                when (capture) {
                    is CaptureResult.Command -> sendRecording(request, capture.recording)
                    CaptureResult.NoClearCommand -> showError("I couldn't hear a clear command")
                }
            }
        }
    }

    private fun updateAudioLevel(request: Int, level: PcmProcessor.Level) {
        runOnUiThread {
            if (!isCurrent(request) || state != VoiceState.LISTENING) return@runOnUiThread
            orb.setAudioSignal(level.rms, level.low, level.mid, level.high)
        }
    }

    private fun showListeningReady() {
        if (state != VoiceState.LISTENING) return
        micLabel.text = "Listening"
        detail.text = "I'm listening."
    }

    private fun sendRecording(request: Int, recording: RecordedCommand) {
        if (!isCurrent(request)) return
        lastRecordMs = SystemClock.elapsedRealtime() - listenStartedMs
        state = VoiceState.PROCESSING
        micButton.setActive(false)
        micButton.isEnabled = true
        micButton.showStop(true)
        micButton.contentDescription = "Cancel command"
        surface.controlLabel.text = "Tap to cancel"
        surface.cancel.visibility = View.INVISIBLE
        micLabel.text = "Working on it"
        detail.text = "You can cancel while you wait."
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.PROCESSING)
        worker.execute {
            if (!isCurrent(request)) return@execute
            val result = try {
                backend.sendCommand(recording.wavBytes) { !isCurrent(request) }
            } catch (error: SocketTimeoutException) {
                logCommandFailure(error)
                runIfCurrent(request) { showError(VoiceFailureCopy.forOutcome(VoiceUiOutcome.TIMEOUT)) }
                return@execute
            } catch (error: IOException) {
                if (error is SignInRequired) {
                    runIfCurrent(request) { showSignInRequired() }
                    return@execute
                }
                logCommandFailure(error)
                val offline = error.message == "offline" || error is UnknownHostException || error is ConnectException
                val message = VoiceFailureCopy.forConnectionFailure(error.message, offline)
                runIfCurrent(request) { showError(message) }
                return@execute
            } catch (error: RuntimeException) {
                logCommandFailure(error)
                runIfCurrent(request) { showError("Friday couldn't finish that. Try again.") }
                return@execute
            }
            runIfCurrent(request) { handleCommand(request, recording, result) }
        }
    }

    private fun handleCommand(request: Int, recording: RecordedCommand, result: CommandResult) {
        lastUploadMs = result.timings.uploadMs
        lastServerMs = result.timings.serverMs
        when (result.response.uiOutcome()) {
            VoiceUiOutcome.ACTION -> {
                val response = result.response as VoiceCommandResponse.Action
                if (DeviceLock.requiresUnlock(this, response.action)) {
                    showUnlockRequired()
                    return
                }
                if (LockedActionPolicy.requiresUnlock(response.action, locked = true)) DebugTools.hideFromLockScreen(this)
                state = VoiceState.ACTION
                surface.showRequest(ActionPresentation.describe(response.action))
                micLabel.text = "Preparing action"
                detail.text = "Checking this phone."
                worker.execute {
                    val prepared = actionExecutor.prepare(response.action)
                    runIfCurrent(request) { actionRunner.handle(prepared) }
                }
            }
            VoiceUiOutcome.UNSUPPORTED -> showUnsupported()
            VoiceUiOutcome.BUSY,
            VoiceUiOutcome.TIMEOUT,
            VoiceUiOutcome.BAD_AUDIO -> showError(VoiceFailureCopy.forOutcome(result.response.uiOutcome()))
            VoiceUiOutcome.UNAUTHORIZED -> showSignInRequired()
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                VOICE_LOG_TAG,
                "recordMs=$lastRecordMs " +
                    "audioMs=${recording.durationMs} uploadMs=${result.timings.uploadMs} " +
                    "serverMs=${result.timings.serverMs ?: -1.0}",
            )
        }
    }

    private fun onActionResult(result: ActionResult, actionMs: Long) {
        if (!resumed || isFinishing || isDestroyed || state != VoiceState.ACTION) return
        if (BuildConfig.DEBUG) {
            Log.d(
                VOICE_LOG_TAG,
                "uploadMs=$lastUploadMs serverMs=${lastServerMs ?: -1.0} actionMs=$actionMs",
            )
        }
        when (result) {
            ActionResult.NeedsUnlock -> showUnlockRequired()
            is ActionResult.Success -> showResult(result.confirmation)
            is ActionResult.Failed -> showError(result.reason)
            is ActionResult.NeedsPermission -> showPermission(result.which)
        }
    }

    private fun showResult(message: String) {
        DebugTools.cancelErrorRecovery(this)
        unlockRequired = false
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        surface.cancel.visibility = View.GONE
        surface.controlLabel.text = "Tap to speak"
        lastConfirmation = message
        surface.replay.visibility = if (speechAvailable) View.VISIBLE else View.INVISIBLE
        micButton.contentDescription = "Start listening"
        micLabel.text = "Done"
        detail.text = message
        detail.setTextColor(getColor(R.color.text_primary))
        orb.setState(AssistantOrbView.State.SUCCESS)
        resultInteracted = false
        confirmationSpeaker.speak(CompletionPolicy.spokenConfirmation(message))
        scheduleResultDismissal(speaking = false, waitingForSpeech = speechAvailable)
    }

    private fun showError(message: String) {
        cancelResultDismissal()
        unlockRequired = false
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        surface.cancel.visibility = View.INVISIBLE
        surface.replay.visibility = View.GONE
        surface.controlLabel.text = RETRY_CONTROL_LABEL
        micButton.contentDescription = "Start listening"
        micLabel.text = if (message == "That feature isn't built yet") "Can't do that yet" else "Try again"
        detail.text = message
        detail.setTextColor(getColor(R.color.error))
        orb.setState(AssistantOrbView.State.ERROR)
        if (DebugTools.recoverAfterError(this)) surface.controlLabel.text = WAKE_RECOVERY_CONTROL_LABEL
    }

    private fun showUnsupported() {
        showError("That feature isn't built yet")
    }

    private fun showPermission(access: RequiredAccess) {
        cancelResultDismissal()
        if (DeviceLock.isLocked(this)) {
            showUnlockRequired()
            return
        }
        unlockRequired = false
        DebugTools.cancelErrorRecovery(this)
        state = VoiceState.IDLE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        surface.cancel.visibility = View.INVISIBLE
        surface.replay.visibility = View.GONE
        surface.controlLabel.text = "Tap to speak"
        micButton.contentDescription = "Start listening"
        micLabel.text = "Permission needed"
        detail.text = when (access) {
            RequiredAccess.MICROPHONE -> "Allow microphone access to use voice input."
            RequiredAccess.CONTACTS -> "Allow contacts access to find a person."
            RequiredAccess.PHONE -> "Allow phone access to place a call."
            RequiredAccess.NOTIFICATION_POLICY -> "Allow Silent mode access in Android Settings."
        }
        detail.setTextColor(getColor(R.color.text_primary))
        permissionButton.visibility = View.VISIBLE
        permissionButton.text = "Manage permissions"
        orb.setState(AssistantOrbView.State.ERROR)
    }

    private fun showUnlockRequired(speak: Boolean = true) {
        DebugTools.cancelErrorRecovery(this)
        state = VoiceState.IDLE
        lastConfirmation = null
        unlockRequired = true
        surface.showRequest(null)
        surface.cancel.visibility = View.INVISIBLE
        surface.replay.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        micButton.contentDescription = "Start listening"
        surface.controlLabel.setText(R.string.tap_to_speak)
        micLabel.setText(R.string.unlock_to_continue)
        detail.setText(R.string.unlock_then_ask_again)
        detail.setTextColor(getColor(R.color.text_primary))
        permissionButton.setText(R.string.unlock_phone)
        permissionButton.visibility = View.VISIBLE
        orb.setState(AssistantOrbView.State.IDLE)
        if (speak) confirmationSpeaker.speak(getString(R.string.unlock_then_ask_again))
        DebugTools.recoverAfterResult(this, 8_000)
    }

    private fun requestPhoneUnlock() {
        DebugTools.cancelErrorRecovery(this)
        confirmationSpeaker.stop()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null) {
            showUnlockRequired(speak = false)
            return
        }
        try {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (isFinishing || isDestroyed) return
                    if (DeviceLock.isLocked(this@MainActivity)) {
                        showUnlockRequired(speak = false)
                    } else {
                        // Discard the blocked command. A fresh request still gets normal call confirmation.
                        resetIdle()
                        DebugTools.hideFromLockScreen(this@MainActivity)
                        detail.setText(R.string.ask_command_again)
                        if (compactSurface) finish()
                    }
                }

                override fun onDismissCancelled() { if (!isFinishing && !isDestroyed) showUnlockRequired(speak = false) }
                override fun onDismissError() { if (!isFinishing && !isDestroyed) showUnlockRequired(speak = false) }
            })
        } catch (_: RuntimeException) {
            showUnlockRequired(speak = false)
        }
    }

    private fun cancelVoiceWork(resetUi: Boolean, preserveConfirmation: Boolean = false) {
        cancelResultDismissal()
        greetingContinuation?.let(surface::removeCallbacks)
        greetingContinuation = null
        resultInteracted = true
        DebugTools.cancelErrorRecovery(this)
        generation++
        if (!preserveConfirmation) confirmationSpeaker.stop()
        backend.cancelCommand()
        activeRecorder?.stop()
        activeRecorder = null
        actionRunner.cancel()
        if (resetUi && ::micButton.isInitialized) resetIdle()
    }

    private fun resetIdle() {
        unlockRequired = false
        lastConfirmation = null
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        surface.cancel.visibility = View.INVISIBLE
        surface.replay.visibility = View.GONE
        surface.controlLabel.text = "Tap to speak"
        surface.showRequest(null)
        micButton.contentDescription = "Start listening"
        micLabel.text = IDLE_LABEL
        detail.text = IDLE_DETAIL
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.IDLE)
    }

    private fun onSpeechState(active: Boolean) {
        speaking = active && resumed && (state == VoiceState.IDLE || state == VoiceState.GREETING)
        speechAudioSeen = false
        if (BuildConfig.DEBUG && state != VoiceState.GREETING) Log.i(VOICE_LOG_TAG, "ttsSpeaking=$speaking")
        if (resumed && state == VoiceState.GREETING) {
            if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "greetingSpeaking=$speaking")
            orb.setState(if (speaking) AssistantOrbView.State.SPEAKING else AssistantOrbView.State.IDLE)
            return
        }
        if (!resumed || state != VoiceState.IDLE) return
        micButton.showStop(speaking)
        micButton.setActive(speaking)
        micButton.contentDescription = if (speaking) "Stop speaking" else "Start listening"
        surface.controlLabel.text = if (speaking) "Tap to stop voice" else "Tap to speak"
        if (unlockRequired) return
        surface.cancel.visibility = if (lastConfirmation != null) View.GONE else View.INVISIBLE
        surface.replay.visibility = if (speaking || lastConfirmation == null || !speechAvailable) View.INVISIBLE else View.VISIBLE
        micLabel.text = if (speaking) "Speaking" else "Done"
        orb.setState(if (speaking) AssistantOrbView.State.SPEAKING else AssistantOrbView.State.SUCCESS)
        if (lastConfirmation != null) scheduleResultDismissal(speaking, waitingForSpeech = false)
    }

    private fun onSpeechFinished() {
        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=speech_finished")
        if (state == VoiceState.GREETING) finishWakeGreeting()
        else if (resumed && lastConfirmation != null) scheduleResultDismissal(speaking = false, waitingForSpeech = false)
    }

    private fun cancelResultDismissal() {
        resultDismissal?.let(surface::removeCallbacks)
        resultDismissal = null
    }

    private fun scheduleResultDismissal(speaking: Boolean, waitingForSpeech: Boolean) {
        cancelResultDismissal()
        val delay = CompletionPolicy.closeDelay(compactSurface, lastConfirmation != null, speaking, waitingForSpeech, resultInteracted) ?: return
        val request = generation
        resultDismissal = Runnable {
            resultDismissal = null
            if (isCurrent(request) && state == VoiceState.IDLE && lastConfirmation != null && !resultInteracted) {
                if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=success_panel_dismissed")
                finish()
            }
        }.also { surface.postDelayed(it, delay) }
    }

    @Suppress("DEPRECATION")
    private fun launchSignIn() {
        if (signInOpen) return
        if (DeviceLock.isLocked(this)) {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
            return
        }
        signInOpen = true
        startActivityForResult(Intent(this, SignInActivity::class.java), SIGN_IN_REQUEST)
    }

    private fun showSignInRequired() {
        cancelVoiceWork(resetUi = true)
        accountRequired = true
        pendingAutoListen = false
        micLabel.text = "Sign in to Friday"
        detail.text = "Use your Google account to send commands."
        permissionButton.text = "Continue with Google"
        permissionButton.visibility = View.VISIBLE
    }

    private fun signOut() {
        ConfirmationSpeaker.stopPlayback()
        cancelVoiceWork(resetUi = true)
        DebugTools.stopListening(this)
        ReadyNotification.setEnabled(this, false)
        val client = AccountClient(this)
        try { client.signOutLocally() } catch (_: Exception) { Unit }
        selectDestination(Destination.HOME)
        showSignInRequired()
        worker.execute { client.flushLogoutBestEffort() }
    }

    private fun onSpeechAudio(level: PcmProcessor.Level) {
        if (speaking && resumed) {
            orb.setAudioSignal(level.rms, level.low, level.mid, level.high)
            if (BuildConfig.DEBUG && !speechAudioSeen) {
                speechAudioSeen = true
                Log.i(VOICE_LOG_TAG, "ttsAudioAvailable=true")
            }
        }
    }

    /** Debug-only visual QA. These states never execute a phone action. */
    private fun showDebugState(scenario: String) {
        if (!BuildConfig.DEBUG) return
        selectDestination(Destination.HOME)
        cancelVoiceWork(resetUi = true)
        when (scenario) {
            "greeting" -> startWakeGreeting()
            "success", "speaking" -> {
                surface.showRequest("Set timer · 10 minutes")
                showResult("Preview: Timer request sent for 10 minutes")
                if (scenario == "success") confirmationSpeaker.stop()
            }
            "error" -> showError("No internet. Connect and try again.")
            "no-command" -> showError("I couldn't hear a clear command")
            "missing-app" -> {
                surface.showRequest("Open inbox")
                showError("I couldn't find that app")
            }
            "unsupported" -> showUnsupported()
            "permission" -> showPermission(RequiredAccess.MICROPHONE)
            "lock-call", "lock-app", "lock-timer", "lock-sound" -> {
                val action = when (scenario) {
                    "lock-call" -> PhoneAction.CallNumber("5550100")
                    "lock-app" -> PhoneAction.OpenApp("Private test app")
                    "lock-timer" -> PhoneAction.SetTimer(120)
                    else -> PhoneAction.SetRingerMode(com.puneet.friday.actions.RingerMode.VIBRATE)
                }
                // Labeled policy check: prepare real dispatch, without executing its Android effect or a cloud request.
                state = VoiceState.ACTION
                val prepared = actionExecutor.prepare(action)
                if (prepared is AndroidActionExecutor.Preparation.Finished) onActionResult(prepared.result, 0)
                else if (scenario == "lock-call" && prepared is AndroidActionExecutor.Preparation.Ready) {
                    actionRunner.handle(prepared.copy(run = { ActionResult.Success("Preview: No call placed") }))
                }
                else {
                    showResult("Preview: Lock-screen action allowed")
                    confirmationSpeaker.stop()
                    DebugTools.recoverAfterResult(this, 6_000)
                }
            }
            "processing" -> {
                state = VoiceState.PROCESSING
                micButton.showStop(true)
                micButton.contentDescription = "Cancel command"
                surface.controlLabel.text = "Tap to cancel"
                micLabel.text = "Working on it"
                detail.text = "You can cancel while you wait."
                orb.setState(AssistantOrbView.State.PROCESSING)
            }
        }
    }

    private fun runIfCurrent(request: Int, action: () -> Unit) {
        runOnUiThread { if (isCurrent(request)) action() }
    }

    private fun clearRecorder(recorder: VoiceRecorder) {
        if (activeRecorder === recorder) activeRecorder = null
    }

    private fun isCurrent(request: Int): Boolean =
        resumed && !isFinishing && !isDestroyed && request == generation

    private fun logCommandFailure(error: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.w(VOICE_LOG_TAG, "commandFailure=${error.javaClass.simpleName}")
        }
    }

    /** ADB-only renderer check. R8 removes this path from release builds. */
    private fun startOrbVoiceDemo() {
        orb.setState(AssistantOrbView.State.LISTENING)
        val startedAt = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= DEBUG_ORB_VOICE_DURATION_MS || !resumed || isFinishing || isDestroyed) {
                    orb.setState(AssistantOrbView.State.IDLE)
                    orbDemoRunnable = null
                    return
                }
                val phase = elapsed / 1_000.0
                val pulse = ((sin(phase * 8.4) + 1.0) * 0.5).toFloat()
                val syllable = ((sin(phase * 17.3 + 0.8) + 1.0) * 0.5).toFloat()
                orb.setAudioSignal(
                    rms = 0.035f + pulse * 0.07f,
                    low = 0.25f + pulse * 0.55f,
                    mid = 0.32f + syllable * 0.62f,
                    high = 0.18f + (1f - pulse) * 0.58f,
                )
                orb.postDelayed(this, DEBUG_ORB_FRAME_MS)
            }
        }
        orbDemoRunnable = runnable
        orb.post(runnable)
    }

    private companion object {
        const val SIGN_IN_REQUEST = 108
        const val WAKE_GREETING = "Ready?"
        const val IDLE_LABEL = "Talk to Friday"
        const val IDLE_DETAIL = "Tap the microphone to start."
        const val RETRY_CONTROL_LABEL = "Tap to retry"
        const val WAKE_RECOVERY_CONTROL_LABEL = "Returning to Hey Friday"
        const val VOICE_LOG_TAG = "FridayVoice"
        const val DEBUG_ORB_VOICE_EXTRA = "debug_orb_voice"
        const val DEBUG_ORB_VOICE_DURATION_MS = 4_000L
        const val DEBUG_ORB_FRAME_MS = 32L
        const val SETUP_REQUEST = 203
    }

    @android.annotation.SuppressLint("NewApi")
    private class SettingsBackHandler(private val activity: Activity, onBack: () -> Unit) {
        private val callback = android.window.OnBackInvokedCallback(onBack)
        fun register() = activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        fun unregister() = activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
    }
}
