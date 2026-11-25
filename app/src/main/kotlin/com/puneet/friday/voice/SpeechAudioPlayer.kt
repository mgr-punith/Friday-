package com.puneet.friday.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Plays bounded in-memory speech with focus and callbacks tied to the playback head. */
internal class SpeechAudioPlayer(
    context: Context,
    private val audio: SpeechAudio,
    private val onStart: () -> Unit,
    private val onAudio: (PcmProcessor.Level) -> Unit,
    private val onFinish: () -> Unit,
    private val onFailure: (started: Boolean) -> Unit,
    private val onInterrupted: () -> Unit,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val playbackLock = Any()
    @Volatile private var stopped = false
    @Volatile private var interrupted = false
    @Volatile private var finished = false
    @Volatile private var track: AudioTrack? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change < 0) synchronized(playbackLock) {
                if (!stopped && !finished) {
                    interrupted = true
                    pauseLocked()
                }
            }
        }, main).build()

    fun start() {
        Thread {
            var started = false
            var completed = false
            var player: AudioTrack? = null
            var focusRequested = false
            try {
                if (stopped) return@Thread
                val audioManager = manager ?: return@Thread
                focusRequested = true
                if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    interrupted = true
                    return@Thread
                }
                val activeTrack = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(WavEncoder.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(audio.samples.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                player = activeTrack
                track = activeTrack
                if (stopped || interrupted) return@Thread
                // The full WAV is in memory. A static buffer also plays clips below the streaming start threshold.
                check(activeTrack.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_NON_BLOCKING) == audio.samples.size)
                check(activeTrack.state == AudioTrack.STATE_INITIALIZED)
                if (stopped || interrupted) return@Thread
                val frameSamples = WavEncoder.SAMPLE_RATE / 25
                var published = 0
                val processor = PcmProcessor()
                fun publish(): Int {
                    val played = activeTrack.playbackHeadPosition.coerceIn(0, audio.samples.size)
                    if (played > 0 && !started) {
                        started = true
                        main.post { if (!stopped && !interrupted) onStart() }
                    }
                    while (published + frameSamples <= played) {
                        val frame = audio.samples.copyOfRange(published, published + frameSamples)
                        val level = processor.analyze(frame, analyzeVoicing = false)
                        main.post { if (!stopped && !interrupted) onAudio(level) }
                        published += frameSamples
                    }
                    return played
                }
                val deadline = SystemClock.elapsedRealtime() + audio.durationMs + 3_000
                // Serialize the last cancellation check with play and pause, so cancellation cannot restart audio.
                synchronized(playbackLock) {
                    if (stopped || interrupted) return@Thread
                    activeTrack.play()
                }
                while (!stopped && !interrupted && SystemClock.elapsedRealtime() < deadline) {
                    if (publish() == audio.samples.size) {
                        completed = true
                        break
                    }
                    Thread.sleep(20)
                }
            } catch (_: RuntimeException) {
                // Device fallback runs only if cloud playback never started.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(playbackLock) {
                    finished = true
                    track = null
                }
                runCatching { player?.pause() }
                runCatching { player?.flush() }
                runCatching { player?.release() }
                if (focusRequested) runCatching { manager?.abandonAudioFocusRequest(focus) }
                main.post {
                    if (!stopped) {
                        if (interrupted) onInterrupted() else if (completed) onFinish() else onFailure(started)
                    }
                }
            }
        }.apply { name = "Friday-speech-playback"; isDaemon = true; start() }
    }

    fun stop() {
        synchronized(playbackLock) {
            stopped = true
            pauseLocked()
        }
    }

    private fun pauseLocked() {
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }
}
