package com.example.assistant.entry

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.example.assistant.DebugTools

class FridayVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        DebugTools.prepareAssistantIntent(args, FridayEntryPoints.listenIntent(context))?.let(::startAssistantActivity)
        finish()
    }
}
