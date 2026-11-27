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
import com.puneet.friday.ui.Destination
import com.puneet.friday.ui.TabHostView
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.CompletionPolicy
import com.puneet.friday.ui.CompactProgress
import com.puneet.friday.ui.AnswerDialog
import com.puneet.friday.ui.AnswerConsent
import com.puneet.friday.auth.AccountClient
import com.puneet.friday.auth.AccountStore
import com.puneet.friday.auth.SignInActivity
import com.puneet.friday.auth.SignInRequired
import com.puneet.friday.voice.CommandResult
import com.puneet.friday.voice.ConfirmationSpeaker
import com.puneet.friday.voice.ConfirmationSpeech
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
    private var answerDetailsPinned = false
    private var answerSpeechStarted = false
    private var answerOptionsOpen = false
    private var answerCloseDelay: Long? = null
    private var answerDismissal: Runnable? = null
    private var reportDialog: Dialog? = null
    private var resumeListeningAfterSetup = false
    private var unlockRequired = false
    private var answerSetupOutcome: VoiceUiOutcome? = null
    private var accountRequired = false
    private var showAccountDeletedToast = false
    private var signInOpen = false
    private var resultDismissal: Runnable? = null
    private var resultReadableUntil = 0L
    private val compactProgress = CompactProgress(
        post = { callback, delay -> surface.postDelayed(callback, delay) },
        remove = { surface.removeCallbacks(it) },
    )
    private var resultInteracted = false
    private var currentAction: PhoneAction? = null
    private var pendingSequence: PendingSequence? = null
    private var pendingConfirmationSpeech: ConfirmationSpeech? = null

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
        if (::orb.isInitialized) orb.onHostResume()
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
                else if (compactSurface) finish()
            } else showSignInRequired()
            return
        }
        if (requestCode != SETUP_REQUEST) return
        setupPromptOpen = false
        val shouldResume = resumeListeningAfterSetup || pendingAutoListen
        resumeListeningAfterSetup = false
        pendingAutoListen = resultCode == RESULT_OK && shouldResume
        maybeStartFromEntryPoint()
        if (compactSurface && !pendingAutoListen && state != VoiceState.LISTENING) finish()
    }

    override fun onPause() {
        val wasAnswer = lastAnswer != null
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
        if (::orb.isInitialized) orb.onHostPause()
        super.onPause()
    }

    private fun createConfirmationSpeaker() = ConfirmationSpeaker(this, ::onSpeechState, ::onSpeechAudio,
        onAvailable = { available ->
            speechAvailable = available
            answerDialog?.setSpeechAvailable(available)
            if (::surface.isInitialized) surface.setAnswerSpeaking(available && speaking)
            if (!available) {
                surface.replay.visibility = View.GONE
                if (lastAnswer != null) scheduleAnswerDismissal(answerReadingDelay())
                if (lastConfirmation != null) scheduleResultDismissal(speaking = false, waitingForSpeech = false)
            }
        }, onFinished = ::onSpeechFinished, onInterrupted = ::onSpeechInterrupted)

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (lastConfirmation != null) {
            resultInteracted = true
            cancelResultDismissal()
        }
        if (compactSurface && lastAnswer != null && lastAnswer?.continuation == null && !answerDetailsPinned) {
            resultReadableUntil = SystemClock.uptimeMillis() + readableTimeout(answerReadingDelay().toInt())
            if (answerDismissal != null) answerCloseDelay?.let(::scheduleAnswerDismissal)
        }
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
        compactProgress.cancel()
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
            onPermissions = {
                when {
                    accountRequired -> launchSignIn()
                    unlockRequired -> requestPhoneUnlock()
                    answerSetupOutcome == VoiceUiOutcome.ANSWERS_DISABLED -> enableShortAnswers()
                    else -> launchSetup(resumeAfterSetup = false)
                }
            },
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
        if (!resumed || !hasWindowFocus() || !pendingAutoListen) return
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
        if (state != VoiceState.IDLE) cancelVoiceWork(resetUi = true)
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
        compactProgress.cancel()
        clearAnswer()
        answerSetupOutcome = null
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
        surface.setVoiceState(AssistantOrbView.State.LISTENING)
        backend.warmAsync()
        val commandRecorder = VoiceRecorder(applicationContext, afterWake = fromWake,
            answerReply = answerContext != null, handsFree = compactSurface)
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
                        if (compactSurface && answerContext == null) finish()
                        else showError(if (answerContext != null) "I didn't hear a reply. Ask the full question again." else "I couldn't hear a clear command")
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
            surface.setAudioLevel(level)
        }
    }

    private fun setDetail(value: String) {
        surface.clearToast()
        detail.text = value
        micLabel.visibility = View.VISIBLE
        detail.visibility = View.VISIBLE
    }

    private fun showVoiceStatus(value: String) {
        surface.setStatus(value)
        micLabel.visibility = if (compactSurface) View.VISIBLE else View.GONE
        detail.visibility = View.GONE
    }

    private fun setControl(homeLabel: String, panelLabel: String, action: String) {
        if (!compactSurface) micButton.setLabel(homeLabel)
        surface.controlLabel.text = panelLabel
        micButton.contentDescription = action
        if (!compactSurface) orb.contentDescription = "Orb. $action"
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
        showProcessing()
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
            runIfCurrent(request) {
                handleCommand(request, recording, result)
            }
        }
    }

    private fun showProcessing(advance: Boolean = true) {
        state = VoiceState.PROCESSING
        micButton.setActive(false)
        micButton.isEnabled = true
        micButton.showStop(true)
        setControl("Cancel", "Tap to cancel", "Cancel command")
        surface.cancel.visibility = View.GONE
        surface.clearToast()
        showVoiceStatus("Working on it")
        detail.setTextColor(getColor(R.color.text_secondary))
        surface.setVoiceState(AssistantOrbView.State.PROCESSING)
        if (compactSurface && advance) {
            if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=compact_progress_started")
            compactProgress.start {
                showVoiceStatus("Looking into it")
                surface.expandProgress()
                if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=compact_progress_expanded")
            }
        }
    }

    private fun handleCommand(request: Int, recording: RecordedCommand, result: CommandResult) {
        lastUploadMs = result.timings.uploadMs
        lastServerMs = result.timings.serverMs
        when (result.response.uiOutcome()) {
            VoiceUiOutcome.ANSWER -> presentAnswer((result.response as VoiceCommandResponse.Answer).answer)
            VoiceUiOutcome.ANSWERS_DISABLED, VoiceUiOutcome.ANSWERS_UNAVAILABLE -> showAnswerSetup(result.response.uiOutcome())
            VoiceUiOutcome.ACTION -> {
                val response = result.response as VoiceCommandResponse.Action
                if (DeviceLock.requiresUnlock(this, response.action)) {
                    if (response.action is PhoneAction.OpenMusic || response.action is PhoneAction.PlayMedia) {
                        showError("Open Friday while unlocked to browse music")
                    } else showUnlockRequired()
                    return
                }
                if (LockedActionPolicy.requiresUnlock(response.action, locked = true)) DebugTools.hideFromLockScreen(this)
                state = VoiceState.ACTION
                if (!compactSurface) showVoiceStatus("Preparing action")
                val sequence = response.action as? PhoneAction.Sequence
                pendingConfirmationSpeech = response.confirmationSpeech
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
        currentAction = action
        prepareOnWorker(request) { actionExecutor.prepare(action) }
    }

    private fun prepareOnWorker(request: Int, prepare: () -> AndroidActionExecutor.Preparation) {
        worker.execute {
            if (!isCurrent(request)) return@execute
            val prepared = prepare()
            runIfCurrent(request) { handlePrepared(request, prepared) }
        }
    }

    private fun handlePrepared(request: Int, prepared: AndroidActionExecutor.Preparation) {
        if (prepared is AndroidActionExecutor.Preparation.Choice ||
            prepared is AndroidActionExecutor.Preparation.Ready && prepared.confirmationTarget != null) {
            compactProgress.cancel()
        }
        actionRunner.handle(prepared) { choice ->
            if (isCurrent(request)) prepareOnWorker(request, choice::prepare)
        }
    }

    private fun onActionResult(result: ActionResult, actionMs: Long) {
        if (!resumed || isFinishing || isDestroyed || state != VoiceState.ACTION) return
        val action = currentAction
        currentAction = null
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
            ActionResult.NeedsUnlock -> when {
                action is PhoneAction.OpenMusic || action is PhoneAction.PlayMedia ->
                    showError("Set up or browse music in Friday while unlocked")
                completed.isEmpty() -> showUnlockRequired()
                else -> showError("$completed. Unlock your phone for the remaining commands.")
            }
            is ActionResult.Success -> {
                // Clock, calls and app launches can pause this Activity on the next main-loop turn.
                if (result.openedActivity) compactProgress.cancel()
                presentResult { showResult(completed.ifEmpty { result.confirmation }) }
            }
            is ActionResult.MediaRequest -> presentResult {
                if (action is PhoneAction.OpenMusic || action is PhoneAction.PlayMedia) pendingConfirmationSpeech = null
                showCompletion(completed.ifEmpty { result.confirmation }, "Media request sent", null)
            }
            is ActionResult.Handoff -> {
                compactProgress.cancel()
                val message = completed.ifEmpty { result.instruction }
                if (result.music) {
                    pendingConfirmationSpeech = null
                    showCompletion(message, "Music request", null)
                } else showHandoff(message)
            }
            is ActionResult.Failed -> showError(if (completed.isEmpty()) result.reason else "$completed. ${result.reason}")
            is ActionResult.NeedsPermission -> showPermission(result.which, completed)
        }
    }

    private fun presentResult(show: () -> Unit) {
        val request = generation
        compactProgress.present { if (isCurrent(request)) show() }
    }

    private fun showResult(message: String) =
        showCompletion(message, "Done", CompletionPolicy.spokenConfirmation(message))

    private fun showHandoff(message: String) =
        showCompletion(message, "Settings opened", CompletionPolicy.spokenHandoff(message))

    private fun showCompletion(message: String, status: String, spoken: String?) {
        val cloudSpeech = pendingConfirmationSpeech.takeIf { AnswerConsent.isAllowed(this) }
        pendingConfirmationSpeech = null
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
        if (compactSurface) resultReadableUntil = SystemClock.uptimeMillis() + readableTimeout(1_800)
        surface.replay.visibility = if (speechAvailable) View.VISIBLE else View.INVISIBLE
        micButton.contentDescription = "Start listening"
        showVoiceStatus(status)
        if (compactSurface) {
            setDetail(message)
            detail.setTextColor(getColor(R.color.text_primary))
        } else {
            surface.showToast(message)
        }
        surface.setVoiceState(AssistantOrbView.State.SUCCESS)
        resultInteracted = false
        if (cloudSpeech != null) confirmationSpeaker.speakCloud(cloudSpeech.text) { cancelled ->
            backend.fetchConfirmationSpeech(cloudSpeech, cancelled)
        } else if (spoken != null) confirmationSpeaker.speak(spoken)
        releaseLauncherWakeBlock()
        scheduleResultDismissal(speaking = false, waitingForSpeech = cloudSpeech != null || spoken != null && speechAvailable)
    }

    private fun showError(message: String) {
        compactProgress.cancel()
        pendingConfirmationSpeech = null
        cancelResultDismissal()
        answerSetupOutcome = null
        unlockRequired = false
        lastConfirmation = null
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
        surface.setVoiceState(AssistantOrbView.State.ERROR)
        releaseLauncherWakeBlock()
        if (compactSurface) scheduleErrorDismissal()
        else if (DebugTools.recoverAfterError(this)) setControl("Speak", WAKE_RECOVERY_CONTROL_LABEL, "Retry command")
    }

    private fun showUnsupported() {
        showError(VoiceFailureCopy.forOutcome(VoiceUiOutcome.UNSUPPORTED))
    }

    private fun showAnswerSetup(outcome: VoiceUiOutcome) {
        if (DeviceLock.isLocked(this)) { showUnlockRequired(); return }
        val message = VoiceFailureCopy.forOutcome(outcome)
        showError(message)
        DebugTools.cancelErrorRecovery(this)
        cancelResultDismissal()
        answerSetupOutcome = outcome
        if (outcome == VoiceUiOutcome.ANSWERS_DISABLED && !AnswerConsent.isAllowed(this)) {
            permissionButton.text = "Enable Short answers"
            permissionButton.visibility = View.VISIBLE
        }
        showAnswerSetupStatus(outcome)
        confirmationSpeaker.speak(message)
        if (compactSurface && permissionButton.visibility == View.VISIBLE) {
            showCompactRequiredAction("Short answers off", message, "Enable Short answers", ::enableShortAnswers)
        } else if (compactSurface) scheduleErrorDismissal()
    }

    private fun showAnswerSetupStatus(outcome: VoiceUiOutcome) {
        val message = VoiceFailureCopy.forOutcome(outcome)
        if (compactSurface) showVoiceStatus(message) else {
            micLabel.text = if (outcome == VoiceUiOutcome.ANSWERS_DISABLED) "Short answers off" else "Short answers unavailable"
            setDetail(message)
        }
    }

    private fun enableShortAnswers() {
        if (!resumed) return
        if (DeviceLock.isLocked(this)) { showUnlockRequired(); return }
        if (permissionDialog?.isShowing == true) return
        confirmationSpeaker.stop()
        permissionDialog = AnswerConsent.show(this) {
            permissionDialog = null
            if (resumed) {
                resetIdle()
                val message = if (AnswerConsent.isAllowed(this)) "Short answers on. Ask your question again." else "Short answers off."
                showVoiceStatus(message)
                surface.showToast(message)
                if (compactSurface) {
                    surface.setVoiceState(AssistantOrbView.State.SUCCESS)
                    scheduleErrorDismissal()
                }
            }
        }
    }

    private fun presentAnswer(answer: InformationAnswer) {
        if (DeviceLock.isLocked(this)) { showUnlockRequired(); return }
        presentResult { showAnswer(answer) }
    }

    private fun showAnswer(answer: InformationAnswer) {
        if (DeviceLock.isLocked(this)) { showUnlockRequired(); return }
        cancelVoiceWork(resetUi = !compactSurface)
        DebugTools.hideFromLockScreen(this)
        state = VoiceState.IDLE
        lastAnswer = answer
        if (compactSurface) {
            resultReadableUntil = SystemClock.uptimeMillis() + readableTimeout(answerReadingDelay().toInt())
            surface.showCompactAnswer(answer,
                onToggle = { open -> toggleAnswerDetails(answer, open) },
                onSource = ::openAnswerSource,
                onReport = { chooseAnswerReport(answer) },
                onStop = ::closeAnswer,
            )
            if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=compact_answer_shown")
        } else showAnswerDialog(answer)
        // Leave time to read if the optional speech engine never starts.
        scheduleAnswerDismissal(answerReadingDelay() + 12_000)
        if (answer.speechToken == null) confirmationSpeaker.speak(answer.text)
        else confirmationSpeaker.speakCloud(answer.text) { cancelled -> backend.fetchSpeech(answer, cancelled) }
    }

    private fun showAnswerDialog(answer: InformationAnswer) {
        answerDialog = AnswerDialog(this, answer,
            speechAvailable = speechAvailable,
            onStop = ::closeAnswer,
            onOptions = { open ->
                answerOptionsOpen = open
                if (open) cancelAnswerDismissal() else answerCloseDelay?.let(::scheduleAnswerDismissal)
            },
            onSource = ::openAnswerSource,
            onReport = { chooseAnswerReport(answer) },
            onClose = ::closeAnswer,
        ).also { it.setSpeaking(speaking) }
    }

    private fun toggleAnswerDetails(answer: InformationAnswer, open: Boolean) {
        if (lastAnswer !== answer) return
        if (DeviceLock.isLocked(this)) {
            clearAnswer()
            confirmationSpeaker.stop()
            showUnlockRequired()
            return
        }
        answerDetailsPinned = open
        surface.expandAnswerDetails(open)
        if (open) cancelAnswerDismissal()
        else if (!speaking) {
            if (answer.continuation == null)
                resultReadableUntil = SystemClock.uptimeMillis() + readableTimeout(answerReadingDelay().toInt())
            answerCloseDelay?.let(::scheduleAnswerDismissal)
        }
    }

    private fun openAnswerSource(url: String) {
        if (DeviceLock.isLocked(this)) { clearAnswer(); confirmationSpeaker.stop(); showUnlockRequired(); return }
        if (AnswerPayload.isPublicSource(url)) try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: RuntimeException) {
            if (compactSurface) android.widget.Toast.makeText(this, "No browser available", android.widget.Toast.LENGTH_SHORT).show()
            else surface.showToast("No browser available")
        }
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
        answerDetailsPinned = false
        lastAnswer = null
        val popup = answerDialog
        answerDialog = null
        popup?.dismiss()
        reportDialog?.dismiss()
        reportDialog = null
        if (::surface.isInitialized) surface.clearCompactAnswer()
    }

    private fun cancelAnswerDismissal() {
        answerDismissal?.let(surface::removeCallbacks)
        answerDismissal = null
    }

    private fun answerReadingDelay(): Long =
        ((lastAnswer?.text?.split(Regex("\\s+"))?.size ?: 0) * 350L).coerceIn(5_000L, 30_000L)

    private fun readableTimeout(millis: Int): Long {
        if (Build.VERSION.SDK_INT < 29) return millis.toLong()
        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val flags = android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
        return (manager?.getRecommendedTimeoutMillis(millis, flags) ?: millis).toLong()
    }

    private fun scheduleAnswerDismissal(delay: Long) {
        cancelAnswerDismissal()
        answerCloseDelay = delay
        val answer = lastAnswer ?: return
        if (answerDetailsPinned || answerOptionsOpen || reportDialog?.isShowing == true) return
        answerDismissal = Runnable {
            answerDismissal = null
            if (resumed && lastAnswer === answer && !answerOptionsOpen && reportDialog?.isShowing != true) {
                if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "event=answer_dismissed")
                finishAnswerSpeech(answer)
            }
        }.also {
            val minimum = if (compactSurface && answer.continuation == null) resultReadableUntil - SystemClock.uptimeMillis() else 0L
            surface.postDelayed(it, maxOf(delay, minimum))
        }
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
        compactProgress.cancel()
        cancelResultDismissal()
        answerSetupOutcome = null
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
            onCancel = { if (compactSurface) finish() },
        ) { launchSetup(resumeAfterSetup = false) }
        permissionDialog = popup
        popup.setOnDismissListener {
            if (permissionDialog === popup) permissionDialog = null
        }
    }

    private fun showUnlockRequired(speak: Boolean = true) {
        compactProgress.cancel()
        pendingConfirmationSpeech = null
        answerSetupOutcome = null
        DebugTools.cancelErrorRecovery(this)
        state = VoiceState.IDLE
        lastConfirmation = null
        unlockRequired = true
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
        surface.setVoiceState(AssistantOrbView.State.IDLE)
        if (speak) confirmationSpeaker.speak(getString(R.string.unlock_then_ask_again))
        releaseLauncherWakeBlock()
        DebugTools.recoverAfterResult(this, 8_000)
        if (compactSurface) showCompactRequiredAction(getString(R.string.unlock_to_continue),
            getString(R.string.unlock_then_ask_again), getString(R.string.unlock_phone), ::requestPhoneUnlock)
    }

    /** Required setup stays in a native dialog; the hands-free ribbon has no buttons. */
    private fun showCompactRequiredAction(title: String, message: String, positive: String, onPositive: () -> Unit) {
        cancelResultDismissal()
        DebugTools.cancelErrorRecovery(this)
        permissionDialog?.dismiss()
        surface.visibility = View.INVISIBLE
        val popup = FridayDialog.show(this, title, message, positive = positive, negative = "Not now",
            onCancel = { finish() }, onPositive = onPositive)
        permissionDialog = popup
        popup.setOnDismissListener {
            if (permissionDialog === popup) permissionDialog = null
            surface.visibility = View.VISIBLE
        }
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
        currentAction = null
        compactProgress.cancel()
        answerSetupOutcome = null
        clearAnswer()
        pendingSequence = null
        pendingConfirmationSpeech = null
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
        pendingConfirmationSpeech = null
        answerSetupOutcome = null
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
        micButton.contentDescription = "Start listening"
        surface.clearToast()
        showVoiceStatus(IDLE_LABEL)
        detail.setTextColor(getColor(R.color.text_secondary))
        surface.setVoiceState(AssistantOrbView.State.IDLE)
    }

    private fun onSpeechState(active: Boolean) {
        speaking = active && resumed && state == VoiceState.IDLE
        speechAudioSeen = false
        if (BuildConfig.DEBUG) Log.i(VOICE_LOG_TAG, "ttsSpeaking=$speaking")
        if (!resumed || state != VoiceState.IDLE) return
        if (lastAnswer != null) {
            if (speaking) {
                answerSpeechStarted = true
                answerCloseDelay = null
                cancelAnswerDismissal()
            }
            answerDialog?.setSpeaking(speaking)
            surface.setAnswerSpeaking(speaking && speechAvailable)
            surface.setVoiceState(if (speaking) AssistantOrbView.State.SPEAKING else AssistantOrbView.State.SUCCESS)
            return
        }
        micButton.showStop(speaking)
        micButton.setActive(speaking)
        micButton.contentDescription = if (speaking) "Stop speaking" else "Start listening"
        setControl(if (speaking) "Stop" else "Speak", if (speaking) "Tap to stop voice" else "Tap to speak",
            if (speaking) "Stop speaking" else "Start listening")
        answerSetupOutcome?.let {
            showAnswerSetupStatus(it)
            surface.setVoiceState(if (speaking) AssistantOrbView.State.SPEAKING else AssistantOrbView.State.ERROR)
            return
        }
        if (unlockRequired) return
        surface.cancel.visibility = View.GONE
        surface.replay.visibility = if (speaking || lastConfirmation == null || !speechAvailable) View.INVISIBLE else View.VISIBLE
        micLabel.text = if (speaking) "Speaking" else "Done"
        surface.setVoiceState(if (speaking) AssistantOrbView.State.SPEAKING else AssistantOrbView.State.SUCCESS)
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

    private fun onSpeechInterrupted() {
        // Do not open reply capture over the audio session that interrupted a clarification.
        if (lastAnswer != null) closeAnswer() else onSpeechFinished()
    }

    private fun cancelResultDismissal() {
        resultDismissal?.let(surface::removeCallbacks)
        resultDismissal = null
    }

    private fun scheduleErrorDismissal() {
        cancelResultDismissal()
        val delay = CompletionPolicy.errorCloseDelay(compactSurface) ?: return
        val request = generation
        resultDismissal = Runnable {
            resultDismissal = null
            if (isCurrent(request) && state == VoiceState.IDLE && lastConfirmation == null && !unlockRequired) finish()
        }.also { surface.postDelayed(it, delay) }
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
        }.also { surface.postDelayed(it, maxOf(delay, resultReadableUntil - SystemClock.uptimeMillis())) }
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
        if (compactSurface && resumed && !signInOpen) {
            if (DeviceLock.isLocked(this)) showUnlockRequired()
            else showCompactRequiredAction("Sign in to Friday", "Use your Google account to send commands.",
                "Continue with Google", ::launchSignIn)
        }
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
            surface.setAudioLevel(level)
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
            "answer", "answer-live", "answer-long", "answer-clarification", "answer-followup", "compact-fast-answer" -> {
                val live = scenario == "answer-live" || scenario == "answer-long"
                val fast = scenario == "compact-fast-answer"
                val clarify = scenario == "answer-clarification" || scenario == "answer-followup"
                if (fast) showProcessing()
                presentAnswer(InformationAnswer(
                    if (live) "information" else if (clarify) "clarification" else "knowledge",
                    if (fast) "The result is 19.77."
                    else if (scenario == "answer-long") "A cricket pitch is the strip of ground between the two wickets. " +
                        "It measures 22 yards from one set of stumps to the other. The bowler delivers the ball from one end, " +
                        "and the batter faces it at the other. Both batters can score a run by making it safely to the opposite end. " +
                        "At the end of an over, the next bowler delivers from the other end of the pitch."
                    else if (live) "Preview: This card shows a short verified answer with source links. Live scores need a deployed backend and a current web search."
                    else if (clarify) "Preview: Do you mean Mahatma Gandhi or Indira Gandhi?"
                    else "Preview: Sachin Tendulkar is a former Indian cricketer known for his batting records. He played international cricket from 1989 to 2013.",
                    if (live) listOf(AnswerSource("Sample source", "https://www.icc-cricket.com/")) else emptyList(),
                    "123e4567-e89b-42d3-a456-426614174000", 0L, "a".repeat(43),
                    if (scenario == "answer-followup") DEBUG_ANSWER_CONTEXT else null,
                ))
            }
            "success", "speaking" -> {
                showResult("Preview: Timer request sent for 10 minutes")
                if (scenario == "success") confirmationSpeaker.stop()
            }
            "error" -> showError("No internet. Connect and try again.")
            "no-command" -> showError("I couldn't hear a clear command")
            "missing-app" -> {
                showError("I couldn't find that app")
            }
            "unsupported" -> showUnsupported()
            "answers-disabled", "answers-unavailable" -> {
                showAnswerSetup(if (scenario == "answers-disabled") VoiceUiOutcome.ANSWERS_DISABLED else VoiceUiOutcome.ANSWERS_UNAVAILABLE)
            }
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
                handlePrepared(generation, prepared)
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
                    handlePrepared(generation, prepared.copy(run = { ActionResult.Success("Preview: No call placed") }))
                }
                else {
                    showResult("Preview: Lock-screen action allowed")
                    confirmationSpeaker.stop()
                    DebugTools.recoverAfterResult(this, 6_000)
                }
            }
            "processing" -> showProcessing(advance = false)
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
        surface.setVoiceState(AssistantOrbView.State.LISTENING)
        showVoiceStatus("Preview · voice response")
        surface.controlLabel.text = "Microphone off"
        val startedAt = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= DEBUG_ORB_VOICE_DURATION_MS || !resumed || isFinishing || isDestroyed) {
                    surface.setVoiceState(AssistantOrbView.State.IDLE)
                    showVoiceStatus("Preview complete")
                    surface.controlLabel.text = "Tap to speak"
                    orbDemoRunnable = null
                    return
                }
                val phase = elapsed / 1_000.0
                val pulse = ((sin(phase * 8.4) + 1.0) * 0.5).toFloat()
                val syllable = ((sin(phase * 17.3 + 0.8) + 1.0) * 0.5).toFloat()
                val rms = when (elapsed) {
                    in 0..999 -> 0f
                    in 1_000..2_999 -> 0.017f + pulse * 0.009f
                    in 3_000..5_499 -> 0.045f + pulse * 0.035f
                    else -> 0f
                }
                orb.setAudioSignal(
                    rms = rms,
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
        const val DEBUG_ORB_VOICE_DURATION_MS = 8_000L
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
