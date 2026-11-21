package com.puneet.friday.debug.wake

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import com.puneet.friday.ui.AppUi
import java.io.File
import java.util.Locale

class WakeTrialActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var timing: TextView
    private lateinit var start: Button
    private lateinit var sensitivity: Button
    private lateinit var mode: Button
    private var threshold = WakeTrialSession.DEFAULT_THRESHOLD
    private var commandMode = true
    private var pendingPermissionStart = false
    private var localMessage: String? = null
    private var shownDetections = 0
    @Volatile private var replayCancelled = false
    private var replayRunning = false
    private var renderedSnapshot: WakeTrialSession.Snapshot? = null
    private var renderedMessage: String? = null
    private var renderedThreshold = Float.NaN
    private var renderedReplay = false
    private var renderedCommandMode = false
    private var renderedTimeBucket = -1L
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threshold = savedInstanceState?.getFloat("threshold", WakeTrialSession.DEFAULT_THRESHOLD) ?: WakeTrialSession.DEFAULT_THRESHOLD
        commandMode = savedInstanceState?.getBoolean("commands") ?: intent.getBooleanExtra("debug_wake_commands", true)
        val (scroll, content) = AppUi.scrollContent(this, "Wake detector test", "Debug only · microWakeWord", back = true)
        content.addView(AppUi.notice(this,
            "Start this test to listen for Hey Friday on this phone. After a wake, Friday can open its command panel. Wait for Listening, then say your command. Only command audio goes to Friday's backend and Gemini. The test saves counts and timings, without audio or transcripts."), AppUi.params(this, 24))
        status = AppUi.text(this, "Microphone off", 15f, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        count = AppUi.text(this, "Detections: 0", 19f, bold = true)
        timing = AppUi.text(this, "", 12f, secondary = true)
        content.addView(status, AppUi.params(this, 12))
        content.addView(count, AppUi.params(this, 8))
        content.addView(timing, AppUi.params(this, 24))
        start = AppUi.button(this, "Start local test", AppUi.ButtonTone.PRIMARY) { toggleTest() }
        content.addView(start, AppUi.params(this, 12))
        sensitivity = AppUi.navigationButton(this, "Sensitivity", "") {
            val index = WakeTrialSession.THRESHOLDS.indexOf(threshold)
            threshold = WakeTrialSession.THRESHOLDS[(index + 1) % WakeTrialSession.THRESHOLDS.size]
            render()
        }
        content.addView(sensitivity, AppUi.params(this, 8))
        mode = AppUi.navigationButton(this, "On wake", "") { commandMode = !commandMode; render() }
        content.addView(mode, AppUi.params(this, 8))
        content.addView(AppUi.text(this, "0.30 is the command-trial default. The model default is 0.66. Lower values catch more phrases but also trigger on more unrelated sounds.", 12f, secondary = true), AppUi.params(this, 24))
        content.addView(AppUi.navigationButton(this, "App permissions", "Manage microphone and notifications") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, AppUi.params(this, 16))
        content.addView(AppUi.text(this, "In Open Friday mode, dismiss the command panel to resume wake listening. If Friday can't open, tap the wake-test notification. Count detections mode runs no commands. Stop test turns listening off. After a phone restart, open this screen to start again.", 12f, secondary = true), AppUi.params(this, 0))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        render()
        if (intent.getBooleanExtra("debug_wake_trigger", false)) {
            main.postDelayed({
                finish()
                main.postDelayed({ WakeTrialService.simulateWakeForTest(
                    intent.getBooleanExtra("debug_wake_fallback", false), intent.getStringExtra("debug_wake_outcome"),
                ) }, 300)
            }, 100)
        }
        if (savedInstanceState == null && intent.getBooleanExtra(WakeTrialDiagnostics.EXTRA, false)) {
            replayRunning = true
            localMessage = "Checking saved test audio. Microphone off."
            Thread({
                val message = try {
                    WakeTrialDiagnostics.run(filesDir, intent.getStringExtra(WakeTrialDiagnostics.RUN_ID).orEmpty()) { replayCancelled }
                } catch (_: Exception) {
                    "Couldn't write replay results."
                }
                main.post {
                    replayRunning = false
                    if (!isDestroyed) {
                        localMessage = message
                        render()
                    }
                }
            }, "FridayWakeReplay").start()
        }
    }

    override fun onResume() {
        super.onResume()
        shownDetections = WakeTrialService.session.snapshot().detections
        main.post(refresh)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (pendingPermissionStart) {
            pendingPermissionStart = false
            startTest()
        }
    }

    override fun onPause() {
        replayCancelled = true
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("threshold", threshold)
        outState.putBoolean("commands", commandMode)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != PERMISSIONS) return
        pendingPermissionStart = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        if (!pendingPermissionStart) localMessage = "Allow microphone and notifications in App permissions, then retry."
        render()
    }

    private fun toggleTest() {
        localMessage = null
        if (WakeTrialService.session.snapshot().active) {
            WakeTrialService.stopForAssistant("Microphone off")
            render()
            return
        }
        if (!MicroWakeWordNative.available || !File(filesDir, WakeTrialModel.RELATIVE_PATH).isFile) {
            localMessage = "Install the wake-test debug build and approved test model first."
            render()
            return
        }
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSIONS)
        } else {
            startTest()
        }
    }

    private fun startTest() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.areNotificationsEnabled() != true || manager.getNotificationChannel(WakeTrialService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            localMessage = "Allow wake-test notifications in App permissions so you can stop the microphone."
        } else {
            try {
                startForegroundService(Intent(this, WakeTrialService::class.java).setAction(WakeTrialService.START)
                    .putExtra(WakeTrialService.THRESHOLD, threshold).putExtra(WakeTrialService.COMMAND_MODE, commandMode))
            } catch (_: RuntimeException) {
                localMessage = "Couldn't start the microphone. Reopen this screen and try again."
            }
        }
        render()
    }

    private fun render() {
        val snapshot = WakeTrialService.session.snapshot()
        val message = localMessage ?: snapshot.message
        if (snapshot.active) { threshold = snapshot.threshold; commandMode = snapshot.commandMode }
        val elapsed = if (snapshot.active && snapshot.startedMs > 0)
            SystemClock.elapsedRealtime() - snapshot.startedMs else snapshot.elapsedMs
        val timeBucket = elapsed / 5_000
        if (snapshot == renderedSnapshot && message == renderedMessage && threshold == renderedThreshold &&
            replayRunning == renderedReplay && commandMode == renderedCommandMode && timeBucket == renderedTimeBucket) return
        renderedSnapshot = snapshot
        renderedMessage = message
        renderedThreshold = threshold
        renderedReplay = replayRunning
        renderedCommandMode = commandMode
        renderedTimeBucket = timeBucket
        if (status.text.toString() != message) status.text = message
        val accessibleStatus = "$message. Detections: ${snapshot.detections}."
        if (status.contentDescription != accessibleStatus) status.contentDescription = accessibleStatus
        val detectionText = "Detections: ${snapshot.detections}"
        if (count.text.toString() != detectionText) count.text = detectionText
        val last = snapshot.lastDetectionMs?.let(::duration) ?: "None"
        timing.text = "${if (snapshot.commandMode) "Run time" else "Listening time"}: ${duration(elapsed)}\nLast detection: $last\nCommand panels opened: ${snapshot.openedCommands}"
        start.text = if (snapshot.active) "Stop test" else "Start local test"
        start.isEnabled = !replayRunning && snapshot.phase != WakeTrialSession.Phase.STOPPING
        start.alpha = if (start.isEnabled) 1f else 0.5f
        sensitivity.isEnabled = !replayRunning && !snapshot.active
        sensitivity.alpha = if (sensitivity.isEnabled) 1f else 0.5f
        mode.isEnabled = !replayRunning && !snapshot.active
        mode.alpha = if (mode.isEnabled) 1f else 0.5f
        AppUi.setNavigationText(mode, "On wake", if (commandMode) "Open Friday" else "Count detections")
        AppUi.setNavigationText(sensitivity, "Sensitivity", when (threshold) {
            0.66f -> "0.66 · Model default"
            0.30f -> "0.30 · Command trial"
            else -> "0.10 · More sensitive"
        })
        if (snapshot.detections > shownDetections) {
            count.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
        shownDetections = snapshot.detections
    }

    private fun duration(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1_000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    private companion object { const val PERMISSIONS = 761 }
}
