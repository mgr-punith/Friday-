package com.example.assistant.entry

import android.annotation.SuppressLint
import android.content.Intent
import android.speech.RecognitionService

/** Required by Android 8–11 when Friday is selected as the assistant. Friday records in its visible UI. */
class FridayRecognitionService : RecognitionService() {
    @SuppressLint("WrongConstant")
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        // Keep the compatibility stub independent from Android's SpeechRecognizer API.
        listener.error(EntryPointPolicy.RECOGNITION_ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
