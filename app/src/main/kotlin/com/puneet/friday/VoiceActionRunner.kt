package com.puneet.friday

import android.app.Activity
import android.app.Dialog
import com.puneet.friday.actions.ActionResult
import com.puneet.friday.actions.AndroidActionExecutor
import com.puneet.friday.actions.DeviceLock
import com.puneet.friday.actions.PhoneAction
import com.puneet.friday.ui.FridayDialog

/** Completes a prepared action from the visible assistant UI. */
class VoiceActionRunner(
    private val activity: Activity,
    private val onResult: (ActionResult, Long) -> Unit,
    private val speakChoices: (String) -> Unit,
    private val stopSpeech: () -> Unit,
) {
    private var activeDialog: Dialog? = null
    private var speakingChoices = false

    fun handle(prepared: AndroidActionExecutor.Preparation) {
        val action = when (prepared) {
            is AndroidActionExecutor.Preparation.Ready -> prepared.action
            is AndroidActionExecutor.Preparation.Choice -> prepared.action
            is AndroidActionExecutor.Preparation.Finished -> null
        }
        if (action != null && DeviceLock.requiresUnlock(activity, action)) {
            onResult(ActionResult.NeedsUnlock, 0L)
            return
        }
        when (prepared) {
            is AndroidActionExecutor.Preparation.Finished -> onResult(prepared.result, 0L)
            is AndroidActionExecutor.Preparation.Ready -> finish(prepared)
            is AndroidActionExecutor.Preparation.Choice -> {
                activeDialog = FridayDialog.choices(
                    activity,
                    prepared.question,
                    prepared.options.map { it.label },
                    onCancel = { completeCancelled() },
                ) { index ->
                    activeDialog = null
                    stopChoiceSpeech()
                    prepared.options.getOrNull(index)?.ready?.let(::finish)
                        ?: onResult(ActionResult.Failed("Try again"), 0L)
                }
                if (prepared.action is PhoneAction.CallContact && !DeviceLock.isLocked(activity)) {
                    val labels = prepared.options.take(5).map { it.spokenLabel }
                    val remaining = if (prepared.options.size > labels.size) ". More matches are on screen" else ""
                    speakingChoices = true
                    speakChoices(labels.joinToString(". ", postfix = "$remaining. ${prepared.question}"))
                }
            }
        }
    }

    fun cancel() {
        activeDialog?.dismiss()
        activeDialog = null
        stopChoiceSpeech()
    }

    private fun finish(ready: AndroidActionExecutor.Preparation.Ready) {
        if (DeviceLock.requiresUnlock(activity, ready.action)) {
            onResult(ActionResult.NeedsUnlock, 0L)
            return
        }
        val target = ready.confirmationTarget
        if (target == null) {
            run(ready)
            return
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val direct = ready.runDirectIfUnlocked?.invoke()
        if (direct != null) {
            onResult(direct, android.os.SystemClock.elapsedRealtime() - started)
            return
        }
        activeDialog = FridayDialog.show(
            activity,
            "Start a phone call?",
            "Call $target using this phone?",
            positive = "Call",
            negative = "Cancel",
            onCancel = { completeCancelled() },
        ) {
            activeDialog = null
            run(ready)
        }
    }

    private fun run(ready: AndroidActionExecutor.Preparation.Ready) {
        val started = android.os.SystemClock.elapsedRealtime()
        val result = ready.run()
        onResult(result, android.os.SystemClock.elapsedRealtime() - started)
    }

    private fun completeCancelled() {
        activeDialog = null
        stopChoiceSpeech()
        onResult(ActionResult.Failed("Cancelled"), 0L)
    }

    private fun stopChoiceSpeech() {
        if (!speakingChoices) return
        speakingChoices = false
        stopSpeech()
    }
}
