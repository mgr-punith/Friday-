package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.assistant.actions.ActionResult
import com.example.assistant.actions.AndroidActionExecutor
import com.example.assistant.actions.RequiredAccess
import com.example.assistant.ui.AppUi
import com.example.assistant.ui.AssistantOrbView
import com.example.assistant.ui.FridayDialog
import com.example.assistant.ui.MicButtonView
import com.example.assistant.voice.CommandResult
import com.example.assistant.voice.CaptureResult
import com.example.assistant.voice.FridayBackendClient
import com.example.assistant.voice.PcmProcessor
import com.example.assistant.voice.RecordedCommand
import com.example.assistant.voice.VoiceCommandResponse
import com.example.assistant.voice.VoiceUiOutcome
import com.example.assistant.voice.VoiceRecorder
import com.example.assistant.voice.uiOutcome
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity() {
    private enum class VoiceState { IDLE, LISTENING, PROCESSING, ACTION }

    private lateinit var orb: AssistantOrbView
    private lateinit var micButton: MicButtonView
    private lateinit var micLabel: TextView
    private lateinit var detail: TextView
    private lateinit var permissionButton: Button
    private lateinit var backend: FridayBackendClient
    private lateinit var actionExecutor: AndroidActionExecutor
    private lateinit var actionRunner: VoiceActionRunner
    private var activeRecorder: VoiceRecorder? = null
    private var unsupportedDialog: Dialog? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var state = VoiceState.IDLE
    private var resumed = false
    private var generation = 0
    private var listenStartedMs = 0L
    private var lastRecordMs = 0L
    private var lastUploadMs = 0L
    private var lastServerMs: Double? = null
    private var orbDemoRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = FridayBackendClient(this)
        actionExecutor = AndroidActionExecutor(this)
        actionRunner = VoiceActionRunner(this, ::onActionResult)
        setContentView(createContentView())
        if (BuildConfig.DEBUG && intent.getBooleanExtra(DEBUG_ORB_VOICE_EXTRA, false)) startOrbVoiceDemo()
        backend.registerInBackground()
        if (!getSharedPreferences("friday", MODE_PRIVATE).getBoolean("setup_seen", false)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        cancelVoiceWork(resetUi = true)
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        activeRecorder?.stop()
        activeRecorder = null
        unsupportedDialog?.dismiss()
        unsupportedDialog = null
        orbDemoRunnable?.let(orb::removeCallbacks)
        orbDemoRunnable = null
        actionRunner.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val screen = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.screen_background))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val frame = FrameLayout(this)
        val contentWidth = min(dp(440), resources.displayMetrics.widthPixels)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        frame.addView(
            content,
            FrameLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            },
        )
        screen.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(AppUi.text(this, "Friday", 24f, bold = true), linearParams(bottom = 4))
        content.addView(AppUi.text(this, "Phone commands", 14f, secondary = true), linearParams(bottom = 20))

        orb = AssistantOrbView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val orbStage = FrameLayout(this).apply {
            addView(
                orb,
                FrameLayout.LayoutParams(dp(176), dp(176)).apply { gravity = Gravity.CENTER },
            )
        }
        content.addView(
            orbStage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply {
                bottomMargin = dp(8)
            },
        )

        detail = AppUi.text(this, IDLE_DETAIL, 14f, secondary = true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        micButton = MicButtonView(this).apply {
            contentDescription = "Start listening"
            setOnClickListener { onMicPressed() }
        }
        micLabel = AppUi.text(this, IDLE_LABEL, 15f, bold = true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        permissionButton = AppUi.button(this, "Manage permissions", AppUi.ButtonTone.QUIET) {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }.apply { visibility = View.GONE }

        content.addView(
            micButton,
            LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            },
        )
        content.addView(micLabel, linearParams(bottom = 4))
        content.addView(detail, linearParams(bottom = 4))
        content.addView(permissionButton, linearParams(bottom = 20))

        content.addView(AppUi.sectionLabel(this, "Phone actions"), linearParams(bottom = 8))
        content.addView(
            AppUi.navigationButton(
                this,
                "Try a phone action",
                "Calls, alarms, apps and sound mode",
            ) { startActivity(Intent(this, ActionDebugActivity::class.java)) },
            linearParams(bottom = 8),
        )
        content.addView(
            AppUi.navigationButton(
                this,
                "Permissions",
                "Microphone, contacts, calls and silent mode",
            ) { startActivity(Intent(this, PermissionsActivity::class.java)) },
            linearParams(bottom = 20),
        )

        screen.isFocusableInTouchMode = true
        screen.requestFocus()
        return screen
    }

    private fun onMicPressed() {
        when (state) {
            VoiceState.IDLE -> startListening()
            VoiceState.LISTENING -> {
                micLabel.text = "Finishing command"
                detail.text = "Processing starts after recording stops."
                micButton.isEnabled = false
                activeRecorder?.stop()
            }
            VoiceState.PROCESSING, VoiceState.ACTION -> Unit
        }
    }

    private fun startListening() {
        if (!resumed) return
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
        micButton.contentDescription = "Stop listening"
        micLabel.text = "Starting microphone"
        detail.text = "Speak when Listening appears."
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.LISTENING)
        backend.warmAsync()
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
        detail.text = "Speak one phone command."
    }

    private fun sendRecording(request: Int, recording: RecordedCommand) {
        if (!isCurrent(request)) return
        lastRecordMs = SystemClock.elapsedRealtime() - listenStartedMs
        state = VoiceState.PROCESSING
        micButton.setActive(false)
        micButton.isEnabled = false
        micButton.contentDescription = "Processing command"
        micLabel.text = "Working"
        detail.text = "Friday is checking the command."
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.PROCESSING)
        worker.execute {
            val result = try {
                backend.sendCommand(recording.wavBytes)
            } catch (_: SocketTimeoutException) {
                runIfCurrent(request) { showError("Try again") }
                return@execute
            } catch (error: IOException) {
                val message = if (
                    error.message == "offline" || error is UnknownHostException || error is ConnectException
                ) "No internet" else "Try again"
                runIfCurrent(request) { showError(message) }
                return@execute
            } catch (_: RuntimeException) {
                runIfCurrent(request) { showError("Try again") }
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
                state = VoiceState.ACTION
                micLabel.text = "Checking this phone"
                detail.text = "Friday is preparing the action."
                worker.execute {
                    val prepared = actionExecutor.prepare(response.action)
                    runIfCurrent(request) { actionRunner.handle(prepared) }
                }
            }
            VoiceUiOutcome.UNSUPPORTED -> showUnsupported()
            VoiceUiOutcome.BUSY -> showError("Friday is busy. Try again.")
            VoiceUiOutcome.TIMEOUT -> showError("Try again")
            VoiceUiOutcome.BAD_AUDIO -> showError("I didn't hear that clearly")
            VoiceUiOutcome.UNAUTHORIZED -> showError("Try again")
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
            is ActionResult.Success -> showResult(result.confirmation)
            is ActionResult.Failed -> showError(result.reason)
            is ActionResult.NeedsPermission -> showPermission(result.which)
        }
    }

    private fun showResult(message: String) {
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.contentDescription = "Start listening"
        micLabel.text = "Done"
        detail.text = message
        detail.setTextColor(getColor(R.color.text_primary))
        orb.setState(AssistantOrbView.State.SUCCESS)
    }

    private fun showError(message: String) {
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.contentDescription = "Start listening"
        micLabel.text = "Try again"
        detail.text = message
        detail.setTextColor(getColor(R.color.error))
        orb.setState(AssistantOrbView.State.ERROR)
    }

    private fun showUnsupported() {
        showError("That feature isn't built yet")
        unsupportedDialog?.dismiss()
        unsupportedDialog = FridayDialog.show(
            this,
            "Feature not built yet",
            "Friday can call contacts or numbers, set alarms and timers, open apps, go to the Home screen, and change sound mode.",
            positive = "Got it",
            onCancel = { unsupportedDialog = null },
        ) { unsupportedDialog = null }
    }

    private fun showPermission(access: RequiredAccess) {
        state = VoiceState.IDLE
        micButton.isEnabled = true
        micButton.setActive(false)
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
        orb.setState(AssistantOrbView.State.ERROR)
    }

    private fun cancelVoiceWork(resetUi: Boolean) {
        generation++
        activeRecorder?.stop()
        activeRecorder = null
        unsupportedDialog?.dismiss()
        unsupportedDialog = null
        actionRunner.cancel()
        if (resetUi && ::micButton.isInitialized) resetIdle()
    }

    private fun resetIdle() {
        state = VoiceState.IDLE
        permissionButton.visibility = View.GONE
        micButton.isEnabled = true
        micButton.setActive(false)
        micButton.contentDescription = "Start listening"
        micLabel.text = IDLE_LABEL
        detail.text = IDLE_DETAIL
        detail.setTextColor(getColor(R.color.text_secondary))
        orb.setState(AssistantOrbView.State.IDLE)
    }

    private fun runIfCurrent(request: Int, action: () -> Unit) {
        runOnUiThread { if (isCurrent(request)) action() }
    }

    private fun clearRecorder(recorder: VoiceRecorder) {
        if (activeRecorder === recorder) activeRecorder = null
    }

    private fun isCurrent(request: Int): Boolean =
        resumed && !isFinishing && !isDestroyed && request == generation

    /** ADB-only renderer check. R8 removes this path from release builds. */
    private fun startOrbVoiceDemo() {
        orb.setState(AssistantOrbView.State.LISTENING)
        val startedAt = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= DEBUG_ORB_VOICE_DURATION_MS || isFinishing || isDestroyed) {
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

    private fun linearParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val IDLE_LABEL = "Talk to Friday"
        const val IDLE_DETAIL = "Tap the microphone and speak."
        const val VOICE_LOG_TAG = "FridayVoice"
        const val DEBUG_ORB_VOICE_EXTRA = "debug_orb_voice"
        const val DEBUG_ORB_VOICE_DURATION_MS = 4_000L
        const val DEBUG_ORB_FRAME_MS = 32L
    }
}
