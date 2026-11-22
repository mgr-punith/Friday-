package com.puneet.friday.voice

import android.content.Context
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.puneet.friday.BuildConfig
import com.puneet.friday.DebugTools
import java.util.Locale

/** Keeps Android's optional TTS connection ready while the app process remains alive. */
private object SharedTextToSpeech {
    private val main = Handler(Looper.getMainLooper())
    private val waiters = mutableListOf<(TextToSpeech?) -> Unit>()
    private var engine: TextToSpeech? = null
    private var starting = false
    private var ready = false
    private var playbackOwner: ConfirmationSpeaker? = null

    fun claimPlayback(owner: ConfirmationSpeaker, listener: UtteranceProgressListener) {
        if (playbackOwner !== owner) playbackOwner?.cancelPlayback()
        playbackOwner = owner
        engine?.setOnUtteranceProgressListener(listener)
    }

    fun releasePlayback(owner: ConfirmationSpeaker, stop: Boolean) {
        if (playbackOwner !== owner) return
        if (stop) engine?.stop()
        engine?.setOnUtteranceProgressListener(null)
        playbackOwner = null
    }

    fun stopPlayback() { playbackOwner?.cancelPlayback() }

    fun prepare(context: Context) {
        main.post { startIfNeeded(context.applicationContext) }
    }

    fun acquire(context: Context, callback: (TextToSpeech?) -> Unit) {
        main.post {
            if (ready) {
                callback(engine)
            } else {
                waiters += callback
                startIfNeeded(context.applicationContext)
            }
        }
    }

    private fun startIfNeeded(context: Context) {
        if (ready || starting) return
        starting = true
        engine = TextToSpeech(context) { status ->
            main.post {
                val current = engine
                starting = false
                if (status == TextToSpeech.SUCCESS && current != null && current.setLanguage(Locale.ENGLISH) >= 0) {
                    ready = true
                    Log.i(TAG, "event=tts_ready")
                    completeWaiters(current)
                } else {
                    current?.shutdown()
                    if (engine === current) engine = null
                    ready = false
                    completeWaiters(null)
                }
            }
        }
    }

    private fun completeWaiters(current: TextToSpeech?) {
        val callbacks = waiters.toList()
        waiters.clear()
        callbacks.forEach { it(current) }
    }

    private const val TAG = "FridayVoice"
}

/** Optional device TTS, with cancellation and playback-timed PCM feedback. */
class ConfirmationSpeaker(
    context: Context,
    private var onSpeaking: (Boolean) -> Unit = {},
    private var onAudio: (PcmProcessor.Level) -> Unit = {},
    private var onAvailable: (Boolean) -> Unit = {},
    private var onFinished: () -> Unit = {},
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
    private var closed = false
    private var detached = false
    private var detachedTimeout: Runnable? = null
    private var playbackStart = 0L
    private var queuedAudioMs = 0L
    private var sampleRate = 16_000
    private var channels = 1
    private var format = AudioFormat.ENCODING_PCM_16BIT
    private val frames = ArrayList<AudioFrame>()

    fun warmUp() = SharedTextToSpeech.prepare(appContext)

    fun speak(message: String) {
        val text = message.trim().takeIf { it.isNotEmpty() } ?: return
        stop()
        val id = session.begin()
        // Own pending engine startup too, so a new command can cancel old speech before it starts.
        SharedTextToSpeech.claimPlayback(this, listener)
        DebugTools.beginSpeechPlayback(this)
        pending = text to id
        if (ready) speakPending() else if (!starting) start()
    }

    fun stop() {
        session.cancel()
        pending = null
        frames.clear()
        main.removeCallbacksAndMessages(audioCallbacks)
        SharedTextToSpeech.releasePlayback(this, stop = true)
        DebugTools.endSpeechPlayback(this)
        detachedTimeout?.let(main::removeCallbacks)
        detachedTimeout = null
        playbackStart = 0L
        queuedAudioMs = 0L
        if (speaking) { speaking = false; onSpeaking(false) }
    }

    /** Keep only this short confirmation alive after its Activity closes. No UI callback remains. */
    fun finishAfterScreenCloses(): Boolean {
        if (closed || !session.isActive) return false
        if (detached) return true
        detached = true
        if (BuildConfig.DEBUG) Log.i("FridayVoice", "event=confirmation_detached")
        onSpeaking = {}
        onAudio = {}
        onAvailable = {}
        onFinished = { shutdown() }
        frames.clear()
        main.removeCallbacksAndMessages(audioCallbacks)
        detachedTimeout = Runnable { cancelPlayback() }.also { main.postDelayed(it, 12_000) }
        return true
    }

    internal fun cancelPlayback() {
        val close = detached
        stop()
        if (close) shutdown()
    }

    fun shutdown() {
        if (detached && session.isActive) return
        closed = true
        stop()
        engine = null
        ready = false
        main.removeCallbacksAndMessages(audioCallbacks)
    }

    private fun start() {
        starting = true
        SharedTextToSpeech.acquire(appContext) { current ->
            starting = false
            if (closed) return@acquire
            if (current == null) {
                val id = pending?.second
                pending = null
                onAvailable(false)
                complete(id)
                return@acquire
            }
            engine = current
            ready = true
            onAvailable(true)
            speakPending()
        }
    }

    private fun speakPending() {
        val (text, id) = pending ?: return
        pending = null
        if (!session.accepts(id)) return
        SharedTextToSpeech.claimPlayback(this, listener)
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) complete(id)
    }

    private fun complete(id: String?) {
        if (!session.finish(id)) return
        if (BuildConfig.DEBUG) Log.i("FridayVoice", "event=confirmation_finished detached=$detached")
        SharedTextToSpeech.releasePlayback(this, stop = false)
        DebugTools.endSpeechPlayback(this)
        detachedTimeout?.let(main::removeCallbacks)
        detachedTimeout = null
        frames.clear()
        main.removeCallbacksAndMessages(audioCallbacks)
        if (speaking) { speaking = false; onSpeaking(false) }
        onFinished()
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { main.post {
            if (!session.accepts(id)) return@post
            if (BuildConfig.DEBUG) Log.i("FridayVoice", "event=confirmation_playback_started detached=$detached")
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
                if (detached || !session.accepts(id) || format != AudioFormat.ENCODING_PCM_16BIT) return@post
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

    companion object {
        fun warmUp(context: Context) = SharedTextToSpeech.prepare(context.applicationContext)
        fun stopPlayback() = SharedTextToSpeech.stopPlayback()
    }
}
