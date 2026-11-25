package com.puneet.friday.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context

/** Records a choice before a request can use the optional question and search path. */
object AnswerConsent {
    fun hasChoice(context: Context): Boolean = preferences(context).contains("answers_allowed_v2")
    fun isAllowed(context: Context): Boolean = preferences(context).getBoolean("answers_allowed_v2", false)

    fun setAllowed(context: Context, allowed: Boolean) {
        preferences(context).edit().putBoolean("answers_allowed_v2", allowed).apply()
    }

    fun show(activity: Activity, onChoice: () -> Unit): Dialog = FridayDialog.show(
        activity,
        "Allow short answers?",
        "Friday sends your recorded questions and replies to Groq. If a question is unclear, Friday asks up to two short questions and listens for your replies. For current information, Groq sends search queries to Exa. Friday keeps no question history. You can turn answers off in Settings.",
        positive = "Allow answers",
        negative = "Commands only",
        onCancel = { setAllowed(activity, false); onChoice() },
    ) { setAllowed(activity, true); onChoice() }

    private fun preferences(context: Context) = context.getSharedPreferences("friday", Context.MODE_PRIVATE)
}
