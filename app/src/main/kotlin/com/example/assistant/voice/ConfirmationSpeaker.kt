package com.example.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Optional device TTS, with cancellation and playback-timed PCM feedback. */
class ConfirmationSpeaker(
    context: Context,
    private val onSpeaking: (Boolean) -> Unit = {},
    private val onAudio: (PcmProcessor.Level) -> Unit = {},
    private val onAvailable: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val session = SpeechSession()
    private val audioCallbacks = Any()
    private var engine: TextToSpeech? = null
    private var starting = false
    private var ready = false
    private var pending: Pair<String, String>? = null
    private var speaking = false
    private var playbackStart = 0L
    private var queuedAudioMs = 0L
    private var sampleRate = 16_000
    private var channels = 1
    private var format = AudioFormat.ENCODING_PCM_16BIT
    private val frames = ArrayList<AudioFrame>()

    fun speak(message: String) {
        val text = message.trim().takeIf { it.isNotEmpty() } ?: return
        stop()
        val id = session.begin()
        pending = text to id
        if (ready) speakPending() else if (!starting) start()
    }

    fun stop() {
        session.cancel()
        pending = null
        frames.clear()
        main.removeCallbacksAndMessages(audioCallbacks)
        engine?.stop()
        playbackStart = 0L
        queuedAudioMs = 0L
        if (speaking) { speaking = false; onSpeaking(false) }
    }

    fun shutdown() {
        stop()
        ready = false
        starting = false
        engine?.shutdown()
        engine = null
        main.removeCallbacksAndMessages(null)
    }

    private fun start() {
        starting = true
        engine = TextToSpeech(appContext) { status ->
            main.post {
                starting = false
                val current = engine ?: return@post
                if (status != TextToSpeech.SUCCESS || current.setLanguage(Locale.ENGLISH) < 0) {
                    pending = null
                    session.cancel()
                    current.shutdown()
                    engine = null
                    onAvailable(false)
                    return@post
                }
                ready = true
                onAvailable(true)
                current.setOnUtteranceProgressListener(listener)
                speakPending()
            }
        }
    }

    private fun speakPending() {
        val (text, id) = pending ?: return
        pending = null
        if (!session.accepts(id)) return
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) complete(id)
    }

    private fun complete(id: String?) {
        if (!session.finish(id)) return
        frames.clear()
        main.removeCallbacksAndMessages(audioCallbacks)
        if (speaking) { speaking = false; onSpeaking(false) }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { main.post {
            if (!session.accepts(id)) return@post
            speaking = true
            playbackStart = SystemClock.uptimeMillis()
            onSpeaking(true)
            frames.forEach { schedule(id, it) }
            frames.clear()
        } }

        override fun onDone(id: String?) { main.post { complete(id) } }
        @Deprecated("Required legacy TTS callback")
        override fun onError(id: String?) { main.post { complete(id) } }
        override fun onError(id: String?, errorCode: Int) { main.post { complete(id) } }
        override fun onStop(id: String?, interrupted: Boolean) { main.post { complete(id) } }

        override fun onBeginSynthesis(id: String?, rate: Int, audioFormat: Int, channelCount: Int) {
            main.post {
                if (!session.accepts(id)) return@post
                sampleRate = rate.coerceAtLeast(1)
                channels = channelCount.coerceAtLeast(1)
                format = audioFormat
                queuedAudioMs = 0L
            }
        }

        override fun onAudioAvailable(id: String?, audio: ByteArray?) {
            if (audio == null) return
            main.post {
                if (!session.accepts(id) || format != AudioFormat.ENCODING_PCM_16BIT) return@post
                val stride = channels * 2
                val count = audio.size / stride
                val chunkSize = maxOf(1, sampleRate / 25)
                val processor = PcmProcessor(sampleRate)
                var offset = 0
                while (offset < count) {
                    val length = minOf(chunkSize, count - offset)
                    val samples = ShortArray(length) { index ->
                        val byteIndex = (offset + index) * stride
                        ((audio[byteIndex].toInt() and 255) or (audio[byteIndex + 1].toInt() shl 8)).toShort()
                    }
                    val frame = AudioFrame(queuedAudioMs + offset * 1_000L / sampleRate,
                        processor.analyze(samples, analyzeVoicing = false))
                    if (speaking) schedule(id, frame) else if (frames.size < 300) frames.add(frame)
                    offset += length
                }
                queuedAudioMs += count * 1_000L / sampleRate
            }
        }
    }

    private fun schedule(id: String?, frame: AudioFrame) {
        main.postAtTime({ if (session.accepts(id) && speaking) onAudio(frame.level) },
            audioCallbacks, playbackStart + frame.offsetMs)
    }

    private data class AudioFrame(val offsetMs: Long, val level: PcmProcessor.Level)
}
