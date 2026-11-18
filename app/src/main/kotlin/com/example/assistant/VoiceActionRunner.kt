package com.example.assistant

import android.app.Activity
import android.app.Dialog
import com.example.assistant.actions.ActionResult
import com.example.assistant.actions.AndroidActionExecutor
import com.example.assistant.ui.FridayDialog

/** Completes a prepared action from the visible assistant UI. */
class VoiceActionRunner(
    private val activity: Activity,
    private val onResult: (ActionResult, Long) -> Unit,
) {
    private var activeDialog: Dialog? = null

    fun handle(prepared: AndroidActionExecutor.Preparation) {
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
                    prepared.options.getOrNull(index)?.ready?.let(::finish)
                        ?: onResult(ActionResult.Failed("Try again"), 0L)
                }
            }
        }
    }

    fun cancel() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    private fun finish(ready: AndroidActionExecutor.Preparation.Ready) {
        val target = ready.confirmationTarget
        if (target == null) {
            run(ready)
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
        onResult(ActionResult.Failed("Cancelled"), 0L)
    }
}
