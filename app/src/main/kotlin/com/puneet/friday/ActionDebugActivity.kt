package com.puneet.friday

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.puneet.friday.actions.ActionResult
import com.puneet.friday.actions.AndroidActionExecutor
import com.puneet.friday.actions.PhoneAction
import com.puneet.friday.actions.RequiredAccess
import com.puneet.friday.actions.RingerMode
import com.puneet.friday.actions.validationError
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import java.util.concurrent.Executors

/** Manual controls for real phone actions. Name lookup stays off the UI thread. */
class ActionDebugActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var resultText: TextView
    private lateinit var permissionButton: Button
    private lateinit var executor: AndroidActionExecutor
    private val worker = Executors.newSingleThreadExecutor()
    private val inputValues = linkedMapOf<String, String>()
    private val actionButtons = mutableListOf<Button>()
    private var selectedTool: Tool? = null
    private var selectedMode = RingerMode.NORMAL
    private var activeDialog: Dialog? = null
    private var resumed = false
    private var requestGeneration = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = AndroidActionExecutor(this)
        savedInstanceState?.getBundle("inputs")?.let { inputs ->
            inputs.keySet().forEach { key -> inputValues[key] = inputs.getString(key).orEmpty() }
        }
        selectedMode = RingerMode.entries.firstOrNull { it.name == savedInstanceState?.getString("mode") }
            ?: RingerMode.NORMAL
        val content = AppUi.screen(
            this,
            "Phone actions",
            "Try an action on this phone. Calls ask you to confirm.",
            onBack = ::navigateBack,
        )
        scroll = content.parent.parent as ScrollView
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
        }
        content.addView(body, AppUi.params(this, 0))
        val restoredTool = Tool.entries.firstOrNull { it.name == savedInstanceState?.getString("tool") }
        if (restoredTool == null) showList() else showEditor(restoredTool)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                navigateBack()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        cancelPendingAction()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tool", selectedTool?.name)
        outState.putString("mode", selectedMode.name)
        outState.putBundle("inputs", Bundle().apply {
            inputValues.forEach { (key, value) -> putString(key, value) }
        })
        super.onSaveInstanceState(outState)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33 && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) navigateBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun navigateBack() {
        if (selectedTool == null) finish() else showList()
    }

    private fun showList() {
        cancelPendingAction()
        hideKeyboard()
        selectedTool = null
        actionButtons.clear()
        body.removeAllViews()
        Tool.entries.forEach { tool ->
            body.addView(
                AppUi.navigationButton(this, tool.title, tool.detail) { showEditor(tool) },
                AppUi.params(this, 8),
            )
        }
        body.addView(
            AppUi.button(this, "Manage permissions", AppUi.ButtonTone.QUIET) { openPermissions() },
            AppUi.params(this, 0).apply { topMargin = AppUi.dp(this@ActionDebugActivity, 12) },
        )
        body.requestFocus()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun showEditor(tool: Tool) {
        cancelPendingAction()
        selectedTool = tool
        actionButtons.clear()
        body.removeAllViews()
        body.addView(AppUi.text(this, tool.title, 19f, bold = true), AppUi.params(this, 8))
        when (tool) {
            Tool.CONTACT -> {
                val name = field(body, "contact", "Contact name", "Name in your contacts")
                actionButton("Find contact") { PhoneAction.CallContact(name.text.toString()) }
            }
            Tool.NUMBER -> {
                val number = field(body, "number", "Phone number", "Country code and number", InputType.TYPE_CLASS_PHONE)
                actionButton("Continue") { PhoneAction.CallNumber(number.text.toString()) }
            }
            Tool.ALARM -> {
                body.addView(AppUi.text(this, "24-hour time", 13f, secondary = true), AppUi.params(this, 16))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val hourColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val minuteColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                row.addView(hourColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = AppUi.dp(this@ActionDebugActivity, 12)
                })
                row.addView(minuteColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(row, AppUi.params(this, 0))
                val hour = field(hourColumn, "hour", "Hour", "0–23", InputType.TYPE_CLASS_NUMBER, "6")
                val minute = field(minuteColumn, "minute", "Minute", "0–59", InputType.TYPE_CLASS_NUMBER, "30")
                val label = field(body, "alarm_label", "Label (optional)", "Alarm label", initial = "Friday test")
                actionButton("Send alarm request") {
                    PhoneAction.SetAlarm(
                        hour.text.toString().toIntOrNull() ?: -1,
                        minute.text.toString().toIntOrNull() ?: -1,
                        label.text.toString(),
                    )
                }
            }
            Tool.TIMER -> {
                val seconds = field(body, "seconds", "Duration in seconds", "60", InputType.TYPE_CLASS_NUMBER, "60")
                actionButton("Send timer request") { PhoneAction.SetTimer(seconds.text.toString().toIntOrNull() ?: 0) }
            }
            Tool.APP -> {
                val name = field(body, "app", "App name", "Name on your home screen", initial = "Clock")
                actionButton("Open app") { PhoneAction.OpenApp(name.text.toString()) }
            }
            Tool.HOME -> {
                body.addView(
                    AppUi.text(this, "Return to your launcher. Other apps keep running in the background.", 14f, secondary = true),
                    AppUi.params(this, 20),
                )
                actionButton("Go to Home") { PhoneAction.CloseApp() }
            }
            Tool.SOUND -> {
                val modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                RingerMode.entries.forEach { mode ->
                    val option = RadioButton(this).apply {
                        id = View.generateViewId()
                        text = when (mode) {
                            RingerMode.NORMAL -> "Normal"
                            RingerMode.VIBRATE -> "Vibrate"
                            RingerMode.SILENT -> "Silent"
                        }
                        textSize = 15f
                        minHeight = AppUi.dp(context, 48)
                        setTextColor(getColor(R.color.text_primary))
                        buttonTintList = ColorStateList.valueOf(getColor(R.color.accent_soft))
                        setPadding(AppUi.dp(context, 8), 0, AppUi.dp(context, 8), 0)
                        setOnCheckedChangeListener { _, checked -> if (checked) selectedMode = mode }
                    }
                    modes.addView(option, AppUi.params(this, 0))
                    option.isChecked = mode == selectedMode
                }
                body.addView(modes, AppUi.params(this, 20))
                actionButton("Apply mode") { PhoneAction.SetRingerMode(selectedMode) }
            }
        }
        resultText = AppUi.notice(this, "").apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        permissionButton = AppUi.button(this, "Manage permissions", AppUi.ButtonTone.QUIET) { openPermissions() }.apply {
            visibility = View.GONE
        }
        body.addView(resultText, AppUi.params(this, 8))
        body.addView(permissionButton, AppUi.params(this, 0))
        body.requestFocus()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun field(
        parent: LinearLayout,
        key: String,
        label: String,
        hint: String,
        type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        initial: String = "",
    ): EditText {
        val labelView = AppUi.text(this, label, 13f, secondary = true)
        val input = EditText(this).apply {
            id = View.generateViewId()
            this.hint = hint
            inputType = type
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setText(inputValues[key] ?: initial)
            inputValues[key] = text.toString()
            AppUi.styleField(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                    inputValues[key] = value.toString()
                    if (::resultText.isInitialized) {
                        resultText.visibility = View.GONE
                        permissionButton.visibility = View.GONE
                    }
                }
                override fun afterTextChanged(value: Editable?) = Unit
            })
        }
        labelView.labelFor = input.id
        parent.addView(labelView, AppUi.params(this, 6))
        parent.addView(input, AppUi.params(this, 20))
        return input
    }

    private fun actionButton(label: String, create: () -> PhoneAction) {
        val button = AppUi.button(this, label, AppUi.ButtonTone.PRIMARY) {
            if (busy || !resumed) return@button
            hideKeyboard()
            val action = create()
            val invalid = action.validationError()
            if (invalid != null) showResult(ActionResult.Failed(invalid)) else runAction(action)
        }
        actionButtons.add(button)
        body.addView(button, AppUi.params(this, 16))
    }

    private fun runAction(action: PhoneAction) {
        setBusy(true)
        val generation = ++requestGeneration
        showFeedback("Checking this phone…")
        worker.execute {
            val prepared = executor.prepare(action)
            runOnUiThread {
                if (!resumed || isFinishing || isDestroyed || generation != requestGeneration) return@runOnUiThread
                when (prepared) {
                    is AndroidActionExecutor.Preparation.Finished -> showResult(prepared.result)
                    is AndroidActionExecutor.Preparation.Ready -> finishPreparation(prepared)
                    is AndroidActionExecutor.Preparation.Choice -> {
                        showFeedback("Choose a match to continue.")
                        activeDialog = FridayDialog.choices(
                            this, prepared.question, prepared.options.map { it.label },
                            onCancel = { showCancelled() },
                        ) { index ->
                            activeDialog = null
                            if (resumed) finishPreparation(prepared.options[index].ready)
                        }
                    }
                }
            }
        }
    }

    private fun finishPreparation(ready: AndroidActionExecutor.Preparation.Ready) {
        val target = ready.confirmationTarget
        if (target == null) {
            showResult(ready.run())
            return
        }
        showFeedback("Confirm the call to continue.")
        activeDialog = FridayDialog.show(
            this, "Start a phone call?", "Call $target using this phone?",
            positive = "Call", negative = "Cancel", onCancel = { showCancelled() },
        ) {
            activeDialog = null
            if (resumed) showResult(ready.run())
        }
    }

    private fun showResult(result: ActionResult) {
        setBusy(false)
        val text = when (result) {
            ActionResult.NeedsUnlock -> "Unlock your phone, then try again."
            is ActionResult.Success -> result.confirmation
            is ActionResult.Failed -> result.reason
            is ActionResult.NeedsPermission -> when (result.which) {
                RequiredAccess.MICROPHONE -> "Allow microphone access to use voice input."
                RequiredAccess.CONTACTS -> "Allow contacts access to find a person."
                RequiredAccess.PHONE -> "Allow phone access to place a call."
                RequiredAccess.NOTIFICATION_POLICY -> "Allow Silent mode access in Android Settings."
            }
        }
        showFeedback(text, if (result is ActionResult.Failed) R.color.error else R.color.text_primary)
        permissionButton.visibility = if (result is ActionResult.NeedsPermission) View.VISIBLE else View.GONE
        resultText.post {
            resultText.requestRectangleOnScreen(Rect(0, 0, resultText.width, resultText.height), false)
        }
    }

    private fun showFeedback(text: String, color: Int = R.color.text_secondary) {
        resultText.text = text
        resultText.setTextColor(getColor(color))
        resultText.visibility = View.VISIBLE
        permissionButton.visibility = View.GONE
    }

    private fun showCancelled() {
        activeDialog = null
        setBusy(false)
        showFeedback("Cancelled")
    }

    private fun setBusy(value: Boolean) {
        busy = value
        actionButtons.forEach {
            it.isEnabled = !value
            it.alpha = if (value) 0.7f else 1f
        }
    }

    private fun cancelPendingAction() {
        requestGeneration++
        activeDialog?.dismiss()
        activeDialog = null
        if (busy && ::resultText.isInitialized) showFeedback("Cancelled")
        setBusy(false)
    }

    private fun openPermissions() = startActivity(Intent(this, PermissionsActivity::class.java))

    private fun hideKeyboard() {
        currentFocus?.let { focus ->
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focus.windowToken, 0)
            focus.clearFocus()
        }
    }

    private enum class Tool(val title: String, val detail: String) {
        CONTACT("Call a contact", "Find a name saved on this phone"),
        NUMBER("Call a number", "Enter a phone number"),
        ALARM("Set an alarm", "Choose a time in your clock app"),
        TIMER("Set a timer", "Choose a duration in seconds"),
        APP("Open an app", "Find an installed app by name"),
        HOME("Go to Home", "Return to your launcher"),
        SOUND("Sound mode", "Silent, vibrate or normal"),
    }
}
