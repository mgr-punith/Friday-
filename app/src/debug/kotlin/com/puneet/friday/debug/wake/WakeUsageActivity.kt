package com.puneet.friday.debug.wake

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.puneet.friday.R
import com.puneet.friday.entry.AssistantSettings
import com.puneet.friday.entry.ReadyNotification
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Entry choice for the existing opt-in trial; selecting a row never starts audio. */
class WakeUsageActivity : Activity() {
    private var selected = WakeUsagePolicy.Mode.TAP
    private val options = mutableMapOf<WakeUsagePolicy.Mode, Pair<RadioButton, LinearLayout>>()
    private lateinit var apply: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancelDownload: Button
    private lateinit var assistantFallback: Button
    private var help: PopupWindow? = null
    private var pendingPermission = false
    private var pendingAssistantSettings = false
    private var assistantSelected = false
    private var showAssistantFallback = false
    private var message: String? = null
    private enum class ModelState { CHECKING, REQUIRED, DOWNLOADING, INSTALLING, READY, RUNTIME_MISSING, UNSUPPORTED }
    private data class RenderState(val mode: WakeUsagePolicy.Mode, val phase: WakeTrialSession.Phase,
        val message: String?, val model: ModelState, val progress: Int,
        val assistantSelected: Boolean, val assistantFallback: Boolean)
    private var model = ModelState.CHECKING
    private var downloadPercent = 0
    private var rendered: RenderState? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var work: Future<*>? = null
    private var installer: WakeModelInstaller? = null
    private var workGeneration = 0
    private var downloadTimeout: Runnable? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = WakeUsagePolicy.Mode.from(savedInstanceState?.getString("selection") ?: WakeUsageStore.mode(this).value)
        pendingPermission = savedInstanceState?.getBoolean("permission") ?: false
        pendingAssistantSettings = savedInstanceState?.getBoolean("assistant_settings") ?: false
        showAssistantFallback = savedInstanceState?.getBoolean("assistant_fallback") ?: false
        assistantSelected = AssistantSettings.isSelected(this)
        val (scroll, content) = AppUi.scrollContent(this, "Start Friday", "", back = true)
        addOption(content, WakeUsagePolicy.Mode.HEY_FRIDAY, "Hey Friday",
            "Say “Hey Friday” while using other apps. Pause or resume from the notification. " +
                "Setup downloads a 61 KB model once. Wake detection runs on your phone.")
        addOption(content, WakeUsagePolicy.Mode.TAP, "Tap to speak",
            "Open Friday and tap the microphone to give a command.")
        status = AppUi.text(this, "", 12f, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status, AppUi.params(this, 16))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            contentDescription = "Hey Friday download"
        }
        content.addView(progress, AppUi.params(this, 8).apply { height = AppUi.dp(this@WakeUsageActivity, 4) })
        apply = AppUi.button(this, "Use Tap to speak", AppUi.ButtonTone.PRIMARY) { useSelection() }
        content.addView(apply, AppUi.params(this, 16))
        assistantFallback = AppUi.button(this, "Open Default apps") {
            openAssistantSettings(AssistantSettings.Destination.DEFAULT_APPS)
        }
        content.addView(assistantFallback, AppUi.params(this, 8))
        cancelDownload = AppUi.button(this, "Cancel download") {
            cancelWork()
            message = "Download cancelled."
            checkModel()
        }
        content.addView(cancelDownload, AppUi.params(this, 8))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        render()
        checkModel()
    }

    private fun addOption(content: LinearLayout, mode: WakeUsagePolicy.Mode, label: String, explanation: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = AppUi.dp(context, 56)
            setPadding(AppUi.dp(context, 8), AppUi.dp(context, 4), AppUi.dp(context, 4), AppUi.dp(context, 4))
        }
        val radio = RadioButton(this).apply {
            text = label
            textSize = 13f
            typeface = resources.getFont(R.font.geist)
            setTextColor(getColor(R.color.text_primary))
            buttonTintList = ColorStateList.valueOf(getColor(R.color.accent))
            minHeight = AppUi.dp(context, 48)
            setPadding(AppUi.dp(context, 4), AppUi.dp(context, 8), AppUi.dp(context, 8), AppUi.dp(context, 8))
            setOnClickListener { selected = mode; message = null; render() }
        }
        row.addView(radio, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = AppUi.dp(this@WakeUsageActivity, 8)
        })
        val info = AppUi.iconButton(this, R.drawable.ic_info_circle, "About $label") {}
        info.setOnClickListener { showHelp(info, explanation) }
        row.addView(info, LinearLayout.LayoutParams(AppUi.dp(this, 48), AppUi.dp(this, 48)))
        options[mode] = radio to row
        content.addView(row, AppUi.params(this, 8))
    }

    private fun showHelp(anchor: View, explanation: String) {
        help?.dismiss()
        val text = AppUi.text(this, explanation, 12f).apply {
            setPadding(AppUi.dp(context, 12), AppUi.dp(context, 12), AppUi.dp(context, 12), AppUi.dp(context, 12))
            isFocusableInTouchMode = true
        }
        val body = ScrollView(this).apply {
            background = AppUi.surface(context, 12)
            addView(text)
        }
        val width = minOf(AppUi.dp(this, 280), resources.displayMetrics.widthPixels - AppUi.dp(this, 40))
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels / 2, View.MeasureSpec.AT_MOST))
        help = PopupWindow(body, width, body.measuredHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = AppUi.dp(this@WakeUsageActivity, 8).toFloat()
            setOnDismissListener { help = null; anchor.requestFocus() }
            showAsDropDown(anchor, anchor.width - width, AppUi.dp(this@WakeUsageActivity, 4))
        }
        text.requestFocus()
    }

    private fun useSelection() {
        message = null
        if (selected == WakeUsagePolicy.Mode.TAP) {
            if (!WakeUsageStore.select(this, selected)) { showError("Couldn't save your choice. Try again."); return }
            cancelWork()
            WakeTrialService.stopForAssistant()
            WakeUsageNotification.cancel(this)
            finish()
            return
        }
        if (model == ModelState.REQUIRED) { downloadModel(); return }
        if (model != ModelState.READY) return
        val state = WakeTrialService.session.snapshot()
        if (state.active && !WakeUsagePolicy.canAdopt(state)) {
            showError("Stop the wake detector test, then try again.")
            return
        }
        if (!WakeUsageStore.consented(this)) {
            FridayDialog.show(this, "Use Hey Friday?",
                "Friday checks for “Hey Friday” on this phone. After a wake, Friday sends your command audio to its Vercel backend and Google Gemini. Friday does not save audio.",
                positive = "Allow Hey Friday", negative = "Not now",
            ) { WakeUsageStore.consent(this); checkPermissions() }
        } else checkPermissions()
    }

    private fun checkPermissions() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS)
        else checkAssistant()
    }

    private fun checkAssistant() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.areNotificationsEnabled() != true ||
            manager.getNotificationChannel(WakeTrialService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            showError("Allow Friday notifications in Android Settings, then try again.")
            return
        }
        assistantSelected = AssistantSettings.isSelected(this)
        if (!assistantSelected) {
            openAssistantSettings()
            return
        }
        startHeyFriday()
    }

    private fun openAssistantSettings(from: AssistantSettings.Destination = AssistantSettings.Destination.ASSISTANT) {
        pendingAssistantSettings = true
        if (AssistantSettings.open(this, from) == null) {
            pendingAssistantSettings = false
            showAssistantFallback = true
            showError("Open Android Settings → Default apps → Digital assistant and choose Friday.")
        }
    }

    private fun startHeyFriday() {
        if (!com.puneet.friday.auth.AccountStore.isSignedIn(this)) {
            startActivity(Intent(this, com.puneet.friday.auth.SignInActivity::class.java))
            showError("Sign in before starting Hey Friday.")
            return
        }
        if (!WakeUsageStore.select(this, WakeUsagePolicy.Mode.HEY_FRIDAY)) {
            showError("Couldn't save your choice. Try again.")
            return
        }
        try {
            startForegroundService(Intent(this, WakeTrialService::class.java).setAction(WakeTrialService.START)
                .putExtra(WakeTrialService.USAGE_REVISION, WakeUsageStore.revision(this))
                .putExtra(WakeTrialService.THRESHOLD, WakeTrialSession.DEFAULT_THRESHOLD)
                .putExtra(WakeTrialService.COMMAND_MODE, true))
            ReadyNotification.setEnabled(this, false)
            finish()
        } catch (_: RuntimeException) {
            showError("Couldn't start the microphone. Try again.")
        }
    }

    private fun showError(value: String) { message = value; render() }

    private fun checkModel() {
        model = ModelState.CHECKING
        render()
        val generation = ++workGeneration
        val task = WakeModelInstaller(filesDir)
        installer = task
        work = worker.submit {
            val result = when {
                "arm64-v8a" !in Build.SUPPORTED_ABIS -> ModelState.UNSUPPORTED
                !MicroWakeWordNative.available -> ModelState.RUNTIME_MISSING
                else -> try {
                    if (task.isInstalled()) ModelState.READY else ModelState.REQUIRED
                } catch (_: IOException) { ModelState.REQUIRED }
                  catch (_: SecurityException) { ModelState.REQUIRED }
            }
            main.post {
                if (!isDestroyed && generation == workGeneration) { model = result; render() }
            }
        }
    }

    private fun downloadModel() {
        model = ModelState.DOWNLOADING
        downloadPercent = 0
        message = null
        render()
        val generation = ++workGeneration
        val task = WakeModelInstaller(filesDir, trace = { Log.d("FridayWakeSetup", it) })
        installer = task
        downloadTimeout = Runnable {
            if (generation == workGeneration) {
                cancelWork()
                model = ModelState.REQUIRED
                showError("Download took too long. Try again.")
            }
        }.also { main.postDelayed(it, 25_000) }
        work = worker.submit {
            var installing = false
            val error = try {
                task.install(onInstalling = { installing = true; main.post {
                    if (!isDestroyed && generation == workGeneration) { model = ModelState.INSTALLING; render() }
                } }) { percent -> main.post {
                    if (!isDestroyed && generation == workGeneration) { downloadPercent = percent; render() }
                } }
                null
            } catch (_: WakeModelInstaller.InvalidModel) {
                "Couldn't verify the download. Try again."
            } catch (error: IOException) {
                Log.d("FridayWakeSetup", "Setup failed: ${error.javaClass.simpleName}")
                if (installing) "Couldn't install Hey Friday. Try again."
                else "Couldn't download Hey Friday. Check your connection and try again."
            } catch (_: SecurityException) {
                "Couldn't save Hey Friday. Try again."
            } catch (error: RuntimeException) {
                Log.d("FridayWakeSetup", "Setup failed: ${error.javaClass.name}")
                "Couldn't install Hey Friday. Try again."
            }
            main.post {
                if (!isDestroyed && generation == workGeneration) {
                    downloadTimeout?.let { main.removeCallbacks(it) }
                    downloadTimeout = null
                    model = if (error == null) ModelState.READY else ModelState.REQUIRED
                    message = if (selected == WakeUsagePolicy.Mode.HEY_FRIDAY) error ?: "Download complete." else null
                    render()
                }
            }
        }
    }

    private fun cancelWork() {
        workGeneration++
        downloadTimeout?.let { main.removeCallbacks(it) }
        downloadTimeout = null
        installer?.cancel()
        work?.cancel(true)
    }

    override fun onStart() { super.onStart(); WakeTrialService.assistantVisible(this) }
    override fun onResume() {
        super.onResume()
        assistantSelected = AssistantSettings.isSelected(this)
        if (assistantSelected) showAssistantFallback = false
        main.post(refresh)
    }
    override fun onPostResume() {
        super.onPostResume()
        if (pendingPermission) { pendingPermission = false; checkAssistant() }
        else if (pendingAssistantSettings) {
            pendingAssistantSettings = false
            if (selected != WakeUsagePolicy.Mode.HEY_FRIDAY) return
            assistantSelected = AssistantSettings.isSelected(this)
            if (assistantSelected) checkPermissions()
            else {
                showAssistantFallback = true
                showError("Friday isn't selected. Choose Friday under Default assistant.")
            }
        }
    }
    override fun onPause() { help?.dismiss(); main.removeCallbacks(refresh); super.onPause() }
    override fun onStop() { WakeTrialService.assistantHidden(this); super.onStop() }
    override fun onDestroy() {
        cancelWork()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selection", selected.value)
        outState.putBoolean("permission", pendingPermission)
        outState.putBoolean("assistant_settings", pendingAssistantSettings)
        outState.putBoolean("assistant_fallback", showAssistantFallback)
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != PERMISSIONS) return
        pendingPermission = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        if (!pendingPermission) showError("Allow microphone and notifications in Android Settings, then try again.")
    }

    private fun render() {
        val state = WakeTrialService.session.snapshot()
        val key = RenderState(selected, state.phase, message, model, downloadPercent, assistantSelected, showAssistantFallback)
        if (key == rendered) return
        rendered = key
        for ((mode, pair) in options) {
            pair.first.isChecked = selected == mode
            pair.second.background = AppUi.surface(this, 12, R.color.surface,
                if (selected == mode) R.color.accent else R.color.border)
        }
        val hey = selected == WakeUsagePolicy.Mode.HEY_FRIDAY
        val value = message ?: if (!hey) "" else when (model) {
            ModelState.CHECKING -> "Checking Hey Friday…"
            ModelState.REQUIRED -> "61 KB download"
            ModelState.DOWNLOADING -> "Downloading Hey Friday · $downloadPercent%"
            ModelState.INSTALLING -> "Installing Hey Friday…"
            ModelState.RUNTIME_MISSING -> "This build doesn't include Hey Friday. Install the wake-enabled APK."
            ModelState.UNSUPPORTED -> "Hey Friday needs a 64-bit ARM phone. You can use Tap to speak."
            ModelState.READY -> if (WakeUsageStore.mode(this) == selected) WakeUsagePolicy.status(state) else ""
        }
        if (status.text.toString() != value) status.text = value
        status.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        val downloading = hey && model in listOf(ModelState.DOWNLOADING, ModelState.INSTALLING)
        progress.visibility = if (downloading) View.VISIBLE else View.GONE
        progress.isIndeterminate = model == ModelState.INSTALLING
        progress.progress = downloadPercent
        cancelDownload.visibility = if (downloading) View.VISIBLE else View.GONE
        assistantFallback.visibility = if (hey && model == ModelState.READY && !assistantSelected && showAssistantFallback)
            View.VISIBLE else View.GONE
        apply.text = when {
            selected == WakeUsagePolicy.Mode.TAP -> "Use Tap to speak"
            model == ModelState.CHECKING -> "Checking…"
            model == ModelState.REQUIRED -> "Download Hey Friday"
            model == ModelState.DOWNLOADING -> "Downloading…"
            model == ModelState.INSTALLING -> "Installing…"
            state.phase == WakeTrialSession.Phase.STOPPING -> "Stopping…"
            model == ModelState.READY && !assistantSelected -> "Set default assistant"
            WakeUsageStore.mode(this) == selected && WakeUsagePolicy.canAdopt(state) -> "Use Hey Friday"
            WakeUsageStore.mode(this) == selected -> "Resume Hey Friday"
            else -> "Use Hey Friday"
        }
        apply.isEnabled = state.phase != WakeTrialSession.Phase.STOPPING &&
            (!hey || model == ModelState.REQUIRED || model == ModelState.READY)
        apply.alpha = if (apply.isEnabled) 1f else 0.5f
    }

    private companion object { const val PERMISSIONS = 763 }
}
