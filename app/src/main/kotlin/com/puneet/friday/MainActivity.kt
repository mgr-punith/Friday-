package com.puneet.friday

import android.Manifest
import android.app.Activity
import android.app.Dialog
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
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.CompletionPolicy
import com.puneet.friday.ui.AnswerDialog
import com.puneet.friday.ui.AnswerConsent
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
import com.puneet.friday.voice.InformationAnswer
import com.puneet.friday.voice.AnswerSource
import com.puneet.friday.voice.AnswerPayload
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sin

open class MainActivity : Activity() {
    protected open val compactSurface = false
    private enum class VoiceState { IDLE, LISTENING, PROCESSING, ACTION }

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
    private var replaceSpeakerOnResume = false
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
    private var permissionDialog: Dialog? = null
    private var answerDialog: AnswerDialog? = null
    private var lastAnswer: InformationAnswer? = null
    private var answerSpeechStarted = false
    private var answerOptionsOpen = false
    private var answerCloseDelay: Long? = null
    private var answerDismissal: Runnable? = null
    private var reportDialog: Dialog? = null
    private var resumeListeningAfterSetup = false
    private var unlockRequired = false
    private var accountRequired = false
    private var showAccountDeletedToast = false
    private var signInOpen = false
    private var resultDismissal: Runnable? = null
    private var resultInteracted = false
    private var pendingSequence: PendingSequence? = null

    private class PendingSequence(val request: Int, val steps: List<PhoneAction>) {
        val completed = mutableListOf<String>()
        var actionMs = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = FridayBackendClient(this)
        actionExecutor = AndroidActionExecutor(this)
        confirmationSpeaker = createConfirmationSpeaker()
        actionRunner = VoiceActionRunner(this, ::onActionResult,
            speakChoices = { confirmationSpeaker.speakOffline(it) },
            stopSpeech = { confirmationSpeaker.stop() },
        )
        setContentView(createContentView())
        if (savedInstanceState?.getBoolean("settings_selected") == true) selectDestination(Destination.SETTINGS)
        consumeEntryPointIntent(intent)
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
        if (compactSurface) DebugTools.onAssistantVisible(this)
        resumed = true
        if (replaceSpeakerOnResume) {
            replaceSpeakerOnResume = false
            // The old confirmation owns its remaining playback, with no Activity callbacks.
            confirmationSpeaker = createConfirmationSpeaker()
            if (lastConfirmation != null) onSpeechState(false)
        }
        logEntryLifecycle("resume")
        restoreReadyNotificationIfNeeded()
        settingsPanel?.refresh()
        if (!AccountStore.isSignedIn(this)) showSignInRequired()
        else if (accountRequired) {
            accountRequired = false
            resetIdle()
            if (!getSharedPreferences("friday", MODE_PRIVATE).getBoolean("setup_seen", false) && !setupPromptOpen) launchSetup(false)
        }
        if (showAccountDeletedToast) {
            showAccountDeletedToast = false
            surface.showToast("Friday account deleted")
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
        if (intent.getBooleanExtra(DEBUG_ORB_VOICE_EXTRA, false)) {
            intent.removeExtra(DEBUG_ORB_VOICE_EXTRA)
            startOrbVoiceDemo()
        }
    }

    @Deprecated("Android Activity result compatibility for the dependency-free framework UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ACCOUNT_REQUEST) {
            when (resultCode) {
                AccountActivity.SIGN_OUT -> signOut()
                RESULT_OK -> if (!AccountStore.isSignedIn(this)) {
                    finishAccountExit()
                    showAccountDeletedToast = true
                }
            }
            return
        }
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
        val wasAnswer = answerDialog != null
        clearAnswer()
        permissionDialog?.dismiss()
        settingsPanel?.dismissPopups()
        logEntryLifecycle("pause")
        val completion = CompletionPolicy.onPause(compactSurface, state == VoiceState.IDLE && lastConfirmation != null,
            isFinishing, isChangingConfigurations)
        val preserveConfirmation = completion.keepConfirmation &&
            confirmationSpeaker.finishAfterScreenCloses()
        if (preserveConfirmation) replaceSpeakerOnResume = true
        resumed = false
        cancelVoiceWork(resetUi = completion.resetUi, preserveConfirmation = preserveConfirmation)
        DebugTools.onAssistantHidden(this)
        // Clock/call/app intents can pause us before the speech close timer fires.
        // A completed compact panel must not return as an empty listening screen.
        if (completion.closeScreen || compactSurface && wasAnswer && !isChangingConfigurations) {
            if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=success_panel_dismissed_on_pause")
            finish()
        }
        super.onPause()
    }

    private fun createConfirmationSpeaker() = ConfirmationSpeaker(this, ::onSpeechState, ::onSpeechAudio,
        onAvailable = { available ->
            speechAvailable = available
            answerDialog?.setSpeechAvailable(available)
            if (!available) {
                surface.replay.visibility = View.GONE
                if (lastAnswer != null) scheduleAnswerDismissal(answerReadingDelay())
                if (lastConfirmation != null) scheduleResultDismissal(speaking = false, waitingForSpeech = false)
            }
        }, onFinished = ::onSpeechFinished)

    override fun onUserInteraction() {
        super.onUserInteraction()
        resultInteracted = true
        cancelResultDismissal()
        if (DebugTools.onAssistantInteraction(this)) setControl("Speak", RETRY_CONTROL_LABEL, "Retry command")
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
        settingsPanel?.dismissPopups()
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
            onSettings = { selectDestination(Destination.SETTINGS) },
        )
        orb = surface.orb
        micButton = surface.mic
        micLabel = surface.title
        detail = surface.detail
        permissionButton = surface.permission
        if (compactSurface) return surface
        return TabHostView(this, surface, createSettings = {
            SettingsPanel(this, onAccount = ::openAccount, onBack = { selectDestination(Destination.HOME) }) {
                launchSetup(resumeAfterSetup = false)
            }.also {
                settingsPanel = it
                it.refresh()
            }.view
        }).also { tabHost = it }
    }

    private fun selectDestination(destination: Destination) {
        val host = tabHost ?: return
        if (host.destination == destination) return
        if (destination == Destination.SETTINGS) {
            surface.clearToast()
            cancelVoiceWork(resetUi = state != VoiceState.IDLE)
        }
        else settingsPanel?.dismissPopups()
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
            if (compactSurface) finish()
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
            startListening(fromWake = wakeEntry)
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
        if (speaking) {
            confirmationSpeaker.stop()
            return
        }
        when (state) {
            VoiceState.IDLE -> startListening()
            VoiceState.LISTENING -> {
                showVoiceStatus("Finishing recording")
                micButton.isEnabled = false
                setControl("Finishing", "Finishing", "Finishing recording")
                activeRecorder?.stop()
            }
            VoiceState.PROCESSING, VoiceState.ACTION -> cancelVoiceWork(resetUi = true)
        }
    }

    private fun startListening(fromWake: Boolean = false, answerContext: String? = null) {
        if (!resumed) return
        clearAnswer()
        if (answerContext != null && (DeviceLock.isLocked(this) || !AnswerConsent.isAllowed(this))) return
        if (!AccountStore.isSignedIn(this)) { showSignInRequired(); return }
        ConfirmationSpeaker.stopPlayback()
        cancelResultDismissal()
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
        if (!AnswerConsent.hasChoice(this)) {
            if (DeviceLock.isLocked(this)) { showUnlockRequired(); return }
            if (permissionDialog?.isShowing == true) return
            permissionDialog = AnswerConsent.show(this) {
                permissionDialog = null
                if (resumed) startListening(fromWake, answerContext)
            }
            return
        }
        if (!compactSurface) DebugTools.onAssistantVisible(this)
        val request = ++generation
        state = VoiceState.LISTENING
        listenStartedMs = SystemClock.elapsedRealtime()
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(true)
        micButton.showStop(true)
        setControl("Finish", "Tap to finish", "Finish recording")
        surface.clearToast()
        showVoiceStatus("Starting microphone…")
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.LISTENING)
        backend.warmAsync()
        val commandRecorder = VoiceRecorder(applicationContext, afterWake = fromWake, answerReply = answerContext != null)
        activeRecorder = commandRecorder
        worker.execute {
            val capture = try {
                commandRecorder.record(
                    onReady = { runIfCurrent(request) { showListeningReady(fromWake, answerContext != null) } },
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
                    is CaptureResult.Command -> {
                        if (BuildConfig.DEBUG) Log.i(
                            VOICE_LOG_TAG, "event=command_captured wake=$fromWake audioMs=${capture.recording.durationMs}",
                        )
                        sendRecording(request, capture.recording, answerContext)
                    }
                    CaptureResult.NoClearCommand -> {
                        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=command_no_speech wake=$fromWake")
                        showError(if (answerContext != null) "I didn't hear a reply. Ask the full question again." else "I couldn't hear a clear command")
                    }
                    CaptureResult.AudioInUse -> {
                        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=command_audio_in_use wake=$fromWake")
                        showError("Audio is in use. Try again.")
                    }
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

    private fun setDetail(value: String) {
        surface.clearToast()
        detail.text = value
        micLabel.visibility = View.VISIBLE
        detail.visibility = View.VISIBLE
    }

    private fun showVoiceStatus(value: String) {
        micLabel.text = value
        micLabel.visibility = if (compactSurface) View.VISIBLE else View.GONE
        detail.visibility = View.GONE
    }

    private fun setControl(homeLabel: String, panelLabel: String, action: String) {
        if (!compactSurface) micButton.setLabel(homeLabel)
        surface.controlLabel.text = panelLabel
        micButton.contentDescription = action
        orb.contentDescription = "Orb. $action"
        orb.isEnabled = micButton.isEnabled
    }

    private fun showListeningReady(fromWake: Boolean, answerReply: Boolean = false) {
        if (state != VoiceState.LISTENING) return
        val message = if (answerReply) "Listening for your reply" else "Listening"
        showVoiceStatus(message)
        surface.showToast(message)
        if (BuildConfig.DEBUG && answerReply) Log.i(VOICE_LOG_TAG, "event=answer_reply_listening")
        if (fromWake) {
            DebugTools.playWakeCue()
            if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=wake_listening")
        }
    }

    private fun sendRecording(request: Int, recording: RecordedCommand, answerContext: String? = null) {
        if (!isCurrent(request)) return
        if (BuildConfig.DEBUG && answerContext == DEBUG_ANSWER_CONTEXT) {
            // The labeled clarification fixture can exercise capture, but never uploads a recording.
            showDebugState("answer")
            return
        }
        lastRecordMs = SystemClock.elapsedRealtime() - listenStartedMs
        state = VoiceState.PROCESSING
        micButton.setActive(false)
        micButton.isEnabled = true
        micButton.showStop(true)
        micButton.contentDescription = "Cancel command"
        setControl("Cancel", "Tap to cancel", "Cancel command")
        surface.cancel.visibility = View.GONE
        surface.clearToast()
        showVoiceStatus("Working on it")
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.PROCESSING)
        worker.execute {
            if (!isCurrent(request)) return@execute
            val result = try {
                backend.sendCommand(recording.wavBytes, answerContext) { !isCurrent(request) }
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
            VoiceUiOutcome.ANSWER -> showAnswer((result.response as VoiceCommandResponse.Answer).answer)
            VoiceUiOutcome.ACTION -> {
                val response = result.response as VoiceCommandResponse.Action
                if (DeviceLock.requiresUnlock(this, response.action)) {
                    showUnlockRequired()
                    return
                }
                if (LockedActionPolicy.requiresUnlock(response.action, locked = true)) DebugTools.hideFromLockScreen(this)
                state = VoiceState.ACTION
                surface.showRequest(ActionPresentation.describe(response.action))
                showVoiceStatus("Preparing action")
                val sequence = response.action as? PhoneAction.Sequence
                pendingSequence = sequence?.let { PendingSequence(request, it.steps) }
                prepareVoiceAction(request, sequence?.steps?.first() ?: response.action)
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

    private fun prepareVoiceAction(request: Int, action: PhoneAction) {
        worker.execute {
            val prepared = actionExecutor.prepare(action)
            runIfCurrent(request) { actionRunner.handle(prepared) }
        }
    }

    private fun onActionResult(result: ActionResult, actionMs: Long) {
        if (!resumed || isFinishing || isDestroyed || state != VoiceState.ACTION) return
        val sequence = pendingSequence
        if (sequence != null) {
            sequence.actionMs += actionMs
            val stepConfirmation = when (result) {
                is ActionResult.Success -> result.confirmation
                is ActionResult.MediaRequest -> result.confirmation
                is ActionResult.Handoff -> result.instruction
                else -> null
            }
            if (stepConfirmation != null) {
                sequence.completed.add(stepConfirmation)
                val next = sequence.steps.getOrNull(sequence.completed.size)
                if (next != null) {
                    prepareVoiceAction(sequence.request, next)
                    return
                }
            }
            pendingSequence = null
        }
        val completed = sequence?.completed.orEmpty().joinToString(". ")
        if (BuildConfig.DEBUG) {
            Log.d(
                VOICE_LOG_TAG,
                "uploadMs=$lastUploadMs serverMs=${lastServerMs ?: -1.0} actionMs=${sequence?.actionMs ?: actionMs}",
            )
        }
        when (result) {
            ActionResult.NeedsUnlock -> if (completed.isEmpty()) showUnlockRequired()
                else showError("$completed. Unlock your phone for the remaining commands.")
            is ActionResult.Success -> showResult(completed.ifEmpty { result.confirmation })
            is ActionResult.MediaRequest -> showCompletion(completed.ifEmpty { result.confirmation }, "Media request sent", null)
            is ActionResult.Handoff -> {
                val message = completed.ifEmpty { result.instruction }
                if (result.music) showCompletion(message, "Music app opened", null) else showHandoff(message)
            }
            is ActionResult.Failed -> showError(if (completed.isEmpty()) result.reason else "$completed. ${result.reason}")
            is ActionResult.NeedsPermission -> showPermission(result.which, completed)
        }
    }

    private fun showResult(message: String) =
        showCompletion(message, "Done", CompletionPolicy.spokenConfirmation(message))

    private fun showHandoff(message: String) =
        showCompletion(message, "Settings opened", CompletionPolicy.spokenHandoff(message))

    private fun showCompletion(message: String, status: String, spoken: String?) {
        DebugTools.cancelErrorRecovery(this)
        unlockRequired = false
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        surface.cancel.visibility = View.GONE
        setControl("Speak", "Tap to speak", "Start listening")
        lastConfirmation = message
        surface.replay.visibility = if (speechAvailable) View.VISIBLE else View.INVISIBLE
        micButton.contentDescription = "Start listening"
        showVoiceStatus(status)
        if (compactSurface) {
            setDetail(message)
            detail.setTextColor(getColor(R.color.text_primary))
        } else {
            surface.showToast(message)
        }
        orb.setState(AssistantOrbView.State.SUCCESS)
        resultInteracted = false
        if (spoken != null) confirmationSpeaker.speak(spoken)
        releaseLauncherWakeBlock()
        scheduleResultDismissal(speaking = false, waitingForSpeech = spoken != null && speechAvailable)
    }

    private fun showError(message: String) {
        cancelResultDismissal()
        unlockRequired = false
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        surface.cancel.visibility = View.GONE
        surface.replay.visibility = View.GONE
        setControl("Speak", RETRY_CONTROL_LABEL, "Retry command")
        micButton.contentDescription = "Start listening"
        showVoiceStatus(message)
        surface.showToast(message)
        if (compactSurface) micButton.contentDescription = "Retry command"
        orb.setState(AssistantOrbView.State.ERROR)
        releaseLauncherWakeBlock()
        if (DebugTools.recoverAfterError(this)) setControl("Speak", WAKE_RECOVERY_CONTROL_LABEL, "Retry command")
    }

    private fun showUnsupported() {
        showError("Sorry, I can't do that yet")
    }

    private fun showAnswer(answer: InformationAnswer) {
        if (DeviceLock.isLocked(this)) { showUnlockRequired(); return }
        cancelVoiceWork(resetUi = true)
        DebugTools.hideFromLockScreen(this)
        lastAnswer = answer
        if (compactSurface) surface.visibility = View.INVISIBLE
        answerDialog = AnswerDialog(this, answer,
            speechAvailable = speechAvailable,
            onStop = ::closeAnswer,
            onOptions = { open ->
                answerOptionsOpen = open
                if (open) cancelAnswerDismissal() else answerCloseDelay?.let(::scheduleAnswerDismissal)
            },
            onSource = { url ->
                if (DeviceLock.isLocked(this)) { clearAnswer(); showUnlockRequired(); return@AnswerDialog }
                if (AnswerPayload.isPublicSource(url)) try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: RuntimeException) { surface.showToast("No browser available") }
            },
            onReport = { chooseAnswerReport(answer) },
            onClose = ::closeAnswer,
        )
        // Leave time to read if the optional speech engine never starts.
        scheduleAnswerDismissal(answerReadingDelay() + 12_000)
        confirmationSpeaker.speak(answer.text)
    }

    private fun closeAnswer() {
        if (lastAnswer == null) return
        clearAnswer()
        confirmationSpeaker.stop()
        if (compactSurface) finish() else resetIdle()
    }

    private fun finishAnswerSpeech(answer: InformationAnswer) {
        val context = answer.continuation
        if (context == null || DeviceLock.isLocked(this) || !AnswerConsent.isAllowed(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            closeAnswer()
            return
        }
        clearAnswer()
        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=answer_reply_requested")
        startListening(answerContext = context)
    }

    private fun clearAnswer() {
        cancelAnswerDismissal()
        answerCloseDelay = null
        answerSpeechStarted = false
        answerOptionsOpen = false
        lastAnswer = null
        val popup = answerDialog
        answerDialog = null
        popup?.dismiss()
        reportDialog?.dismiss()
        reportDialog = null
        if (::surface.isInitialized) surface.visibility = View.VISIBLE
    }

    private fun cancelAnswerDismissal() {
        answerDismissal?.let(surface::removeCallbacks)
        answerDismissal = null
    }

    private fun answerReadingDelay(): Long =
        ((lastAnswer?.text?.split(Regex("\\s+"))?.size ?: 0) * 350L).coerceIn(5_000L, 30_000L)

    private fun scheduleAnswerDismissal(delay: Long) {
        cancelAnswerDismissal()
        answerCloseDelay = delay
        val answer = lastAnswer ?: return
        if (answerOptionsOpen || reportDialog?.isShowing == true) return
        answerDismissal = Runnable {
            answerDismissal = null
            if (resumed && lastAnswer === answer && !answerOptionsOpen && reportDialog?.isShowing != true) {
                if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=answer_dismissed")
                finishAnswerSpeech(answer)
            }
        }.also { surface.postDelayed(it, delay) }
    }

    private fun chooseAnswerReport(answer: InformationAnswer) {
        if (DeviceLock.isLocked(this)) { clearAnswer(); showUnlockRequired(); return }
        cancelAnswerDismissal()
        answerCloseDelay = null
        confirmationSpeaker.stop()
        reportDialog?.dismiss()
        reportDialog = FridayDialog.choices(this, "Report this answer",
            listOf("Incorrect information", "Outdated information", "Offensive content", "Other problem"),
            onCancel = ::closeAnswer) { index ->
            reportDialog = FridayDialog.show(this, "Send this report?",
                "Send this answer, its source links and the selected reason to Friday for review. Friday keeps reports for seven days. Your recording and question are not included.",
                positive = "Send report", negative = "Cancel", onCancel = ::closeAnswer) {
                val request = generation
                worker.execute {
                    val sent = try { backend.reportAnswer(answer, listOf("incorrect", "outdated", "offensive", "other")[index]) }
                        catch (_: Exception) { false }
                    runIfCurrent(request) {
                        reportDialog = FridayDialog.show(this,
                            if (sent) "Report sent" else "Report not sent",
                            if (sent) "Friday received your report." else "Check your connection and try again.",
                            onCancel = ::closeAnswer, onPositive = ::closeAnswer)
                    }
                }
            }
        }
    }

    private fun showPermission(access: RequiredAccess, completed: String = "") {
        cancelResultDismissal()
        if (DeviceLock.isLocked(this)) {
            showUnlockRequired()
            return
        }
        DebugTools.cancelErrorRecovery(this)
        resetIdle()
        val explanation = when (access) {
            RequiredAccess.MICROPHONE -> "Allow microphone access to give voice commands."
            RequiredAccess.CONTACTS -> "Allow contacts access to find a person to call."
            RequiredAccess.PHONE -> "Allow phone access to place a call."
            RequiredAccess.NOTIFICATION_POLICY -> "Allow silent mode access in Android Settings."
        }
        permissionDialog?.dismiss()
        val popup = FridayDialog.show(
            this,
            "Permission needed",
            if (completed.isEmpty()) explanation else "$completed. $explanation",
            positive = "Manage permissions",
            negative = "Not now",
        ) { launchSetup(resumeAfterSetup = false) }
        permissionDialog = popup
        popup.setOnDismissListener {
            if (permissionDialog === popup) permissionDialog = null
        }
    }

    private fun showUnlockRequired(speak: Boolean = true) {
        DebugTools.cancelErrorRecovery(this)
        state = VoiceState.IDLE
        lastConfirmation = null
        unlockRequired = true
        surface.showRequest(null)
        surface.cancel.visibility = View.GONE
        surface.replay.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        micButton.contentDescription = "Start listening"
        setControl("Speak", getString(R.string.tap_to_speak), "Start listening")
        micLabel.setText(R.string.unlock_to_continue)
        setDetail(getString(R.string.unlock_then_ask_again))
        detail.setTextColor(getColor(R.color.text_primary))
        permissionButton.setText(R.string.unlock_phone)
        permissionButton.visibility = View.VISIBLE
        orb.setState(AssistantOrbView.State.IDLE)
        if (speak) confirmationSpeaker.speak(getString(R.string.unlock_then_ask_again))
        releaseLauncherWakeBlock()
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
                        setDetail(getString(R.string.ask_command_again))
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
        clearAnswer()
        pendingSequence = null
        orbDemoRunnable?.let(orb::removeCallbacks)
        orbDemoRunnable = null
        cancelResultDismissal()
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

    private fun releaseLauncherWakeBlock() {
        if (!compactSurface) DebugTools.onAssistantHidden(this)
    }

    private fun resetIdle() {
        releaseLauncherWakeBlock()
        unlockRequired = false
        lastConfirmation = null
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.showStop(false)
        surface.cancel.visibility = View.GONE
        surface.replay.visibility = View.GONE
        setControl("Speak", "Tap to speak", "Start listening")
        surface.showRequest(null)
        micButton.contentDescription = "Start listening"
        surface.clearToast()
        showVoiceStatus(IDLE_LABEL)
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.IDLE)
    }

    private fun onSpeechState(active: Boolean) {
        speaking = active && resumed && state == VoiceState.IDLE
        speechAudioSeen = false
        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "ttsSpeaking=$speaking")
        if (!resumed || state != VoiceState.IDLE) return
        answerDialog?.let {
            if (speaking) {
                answerSpeechStarted = true
                answerCloseDelay = null
                cancelAnswerDismissal()
            }
            it.setSpeaking(speaking)
            orb.setState(if (speaking) AssistantOrbView.State.SPEAKING else AssistantOrbView.State.IDLE)
            return
        }
        micButton.showStop(speaking)
        micButton.setActive(speaking)
        micButton.contentDescription = if (speaking) "Stop speaking" else "Start listening"
        setControl(if (speaking) "Stop" else "Speak", if (speaking) "Tap to stop voice" else "Tap to speak",
            if (speaking) "Stop speaking" else "Start listening")
        if (unlockRequired) return
        surface.cancel.visibility = View.GONE
        surface.replay.visibility = if (speaking || lastConfirmation == null || !speechAvailable) View.INVISIBLE else View.VISIBLE
        micLabel.text = if (speaking) "Speaking" else "Done"
        orb.setState(if (speaking) AssistantOrbView.State.SPEAKING else AssistantOrbView.State.SUCCESS)
        if (lastConfirmation != null) scheduleResultDismissal(speaking, waitingForSpeech = false)
    }

    private fun onSpeechFinished() {
        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=speech_finished")
        if (resumed && lastAnswer != null) {
            scheduleAnswerDismissal(if (answerSpeechStarted) {
                if (lastAnswer?.continuation != null) 350L else 0L
            } else answerReadingDelay())
            return
        }
        if (resumed && lastConfirmation != null) scheduleResultDismissal(speaking = false, waitingForSpeech = false)
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
        setDetail("Use your Google account to send commands.")
        permissionButton.text = "Continue with Google"
        permissionButton.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun openAccount() {
        startActivityForResult(Intent(this, AccountActivity::class.java), ACCOUNT_REQUEST)
    }

    private fun signOut() {
        val client = AccountClient(this)
        try { client.signOutLocally() } catch (_: Exception) { Unit }
        finishAccountExit()
        worker.execute { client.flushLogoutBestEffort() }
    }

    private fun finishAccountExit() {
        ConfirmationSpeaker.stopPlayback()
        DebugTools.stopListening(this)
        ReadyNotification.setEnabled(this, false)
        selectDestination(Destination.HOME)
        showSignInRequired()
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
            "answer", "answer-live", "answer-clarification", "answer-followup" -> {
                val live = scenario == "answer-live"
                val clarify = scenario == "answer-clarification" || scenario == "answer-followup"
                showAnswer(InformationAnswer(
                    if (live) "information" else if (clarify) "clarification" else "knowledge",
                    if (live) "Preview: This card shows a short verified answer with source links. Live scores need a deployed backend and a current web search."
                    else if (clarify) "Preview: Do you mean Mahatma Gandhi or Indira Gandhi?"
                    else "Preview: Sachin Tendulkar is a former Indian cricketer known for his batting records. He played international cricket from 1989 to 2013.",
                    if (live) listOf(AnswerSource("Sample source", "https://www.icc-cricket.com/")) else emptyList(),
                    "123e4567-e89b-42d3-a456-426614174000", 0L, "a".repeat(43),
                    if (scenario == "answer-followup") DEBUG_ANSWER_CONTEXT else null,
                ))
            }
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
            "contact-choices", "contact-exact" -> {
                state = VoiceState.ACTION
                val action = PhoneAction.CallContact("Sample contact")
                val skip = scenario == "contact-exact"
                val result = { ActionResult.Success("Preview: Contact selected") }
                val ready = AndroidActionExecutor.Preparation.Ready(
                    action, confirmationTarget = "Sample contact",
                    runDirectIfUnlocked = if (skip) result else null, run = result,
                )
                val prepared = if (skip) ready else AndroidActionExecutor.Preparation.Choice(
                    action, "Which one should I call?",
                    listOf("Sample contact one", "Sample contact two").map {
                        AndroidActionExecutor.ChoiceOption(it, ready)
                    },
                )
                actionRunner.handle(prepared)
            }
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
                setControl("Cancel", "Tap to cancel", "Cancel command")
                surface.clearToast()
                showVoiceStatus("Working on it")
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
        orbDemoRunnable?.let(orb::removeCallbacks)
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
        const val ACCOUNT_REQUEST = 109
        const val IDLE_LABEL = "Talk to Friday"
        const val RETRY_CONTROL_LABEL = "Tap to retry"
        const val WAKE_RECOVERY_CONTROL_LABEL = "Returning to Hey Friday"
        const val VOICE_LOG_TAG = "FridayVoice"
        const val DEBUG_ORB_VOICE_EXTRA = "debug_orb_voice"
        const val DEBUG_ANSWER_CONTEXT = "friday_debug_answer_followup_no_upload"
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
