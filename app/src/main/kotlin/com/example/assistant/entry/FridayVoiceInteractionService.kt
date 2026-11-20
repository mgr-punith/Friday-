package com.example.assistant.entry

import android.service.voice.VoiceInteractionService
import com.example.assistant.DebugTools

class FridayVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        DebugTools.onKeyguardLaunch(this)
    }

    override fun onReady() {
        super.onReady()
        DebugTools.onVoiceServiceReady(this)
    }

    override fun onShutdown() {
        DebugTools.onVoiceServiceStopped(this)
        super.onShutdown()
    }

    override fun onDestroy() {
        DebugTools.onVoiceServiceStopped(this)
        super.onDestroy()
    }
}
