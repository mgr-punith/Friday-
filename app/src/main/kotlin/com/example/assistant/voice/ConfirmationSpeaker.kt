package com.example.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.ArrayDeque
import java.util.Locale

class ConfirmationSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var starting = false
    private var ready = false
    private val pending = ArrayDeque<String>()

    fun speak(message: String) {
        val text = message.trim().takeIf { it.isNotEmpty() } ?: return
        if (ready) {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            return
        }
        pending.clear()
        pending.addLast(text)
        if (!starting) start()
    }

    fun shutdown() {
        pending.clear()
        ready = false
        starting = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun start() {
        starting = true
        engine = TextToSpeech(appContext) { status ->
            starting = false
            val current = engine
            if (status != TextToSpeech.SUCCESS || current == null) {
                pending.clear()
                current?.shutdown()
                engine = null
                return@TextToSpeech
            }
            val language = current.setLanguage(Locale.ENGLISH)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                pending.clear()
                current.shutdown()
                engine = null
                return@TextToSpeech
            }
            ready = true
            pending.pollLast()?.let {
                current.speak(it, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            }
            pending.clear()
        }
    }

    private companion object {
        const val UTTERANCE_ID = "friday_confirmation"
    }
}
