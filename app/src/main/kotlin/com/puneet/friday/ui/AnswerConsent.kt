package com.puneet.friday.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context

/** Records a choice before a request can use the optional question and search path. */
object AnswerConsent {
    fun hasChoice(context: Context): Boolean = preferences(context).contains("answers_allowed_v5")
    fun isAllowed(context: Context): Boolean = preferences(context).getBoolean("answers_allowed_v5", false)

    fun setAllowed(context: Context, allowed: Boolean) {
        preferences(context).edit().putBoolean("answers_allowed_v5", allowed).apply()
    }

    fun show(activity: Activity, onChoice: () -> Unit): Dialog = FridayDialog.show(
        activity,
        "Allow short answers and voice?",
        "Friday sends recorded commands, questions and replies to its backend and NVIDIA or Groq for speech recognition. Groq chooses phone actions and answers questions. Friday can ask up to two clarification questions and listen for replies. For current information, Groq sends search queries to Exa. ElevenLabs receives answers, clarification questions and short action confirmations to create speech. It may retain that text and audio under its data policy. Contact names and choices use device speech. Device speech is the fallback. Friday keeps no question or voice history. You can turn answers and ElevenLabs speech off in Settings.",
        positive = "Allow",
        negative = "Commands only",
        onCancel = { setAllowed(activity, false); onChoice() },
    ) { setAllowed(activity, true); onChoice() }

    private fun preferences(context: Context) = context.getSharedPreferences("friday", Context.MODE_PRIVATE)
}
