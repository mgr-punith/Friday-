package com.puneet.friday.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Interrupts playback only for command recording, not the background wake listener. */
internal class CommandAudioFocus(context: Context, onInterrupted: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var requested = false
    private var closed = false
    @Volatile var interrupted = false
        private set

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener({ change ->
            val stop = synchronized(lock) {
                if (change >= 0 || !requested || closed || interrupted) {
                    false
                } else {
                    interrupted = true
                    true
                }
            }
            if (stop) onInterrupted()
        }, Handler(Looper.getMainLooper()))
        .build()

    fun acquire(): Boolean {
        synchronized(lock) {
            if (requested || closed) return false
            requested = true
        }
        return try {
            manager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && !interrupted
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun close() {
        val release = synchronized(lock) {
            if (closed) return
            closed = true
            requested
        }
        if (release) {
            try {
                manager?.abandonAudioFocusRequest(request)
            } catch (_: RuntimeException) {
                // The recorder is already closed even if the audio service has stopped.
            }
        }
    }
}
