package com.puneet.friday.voice

import android.content.Context
import android.media.AudioFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
    private var onInterrupted: () -> Unit = onFinished,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val session = SpeechSession()
    private val audioCallbacks = Any()
    private var engine: TextToSpeech? = null
    private var starting = false
    private var ready = false
    private var pending: PendingSpeech? = null
    private var cloudTimeout: Runnable? = null
    private var audioPlayer: SpeechAudioPlayer? = null
    private var restoreVoice: (() -> Unit)? = null
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

    fun speak(message: String) = speak(message, offlineOnly = false)

    fun speakOffline(message: String) = speak(message, offlineOnly = true)

    fun speakAnswer(message: String, loadAudio: (cancelled: () -> Boolean) -> ByteArray?) {
        if (closed) return
        val text = message.trim().takeIf { it.isNotEmpty() } ?: return
        stop()
        val id = session.begin()
        SharedTextToSpeech.claimPlayback(this, listener)
        DebugTools.beginSpeechPlayback(this)
        // A slow backend cannot delay device speech indefinitely.
        cloudTimeout = Runnable { if (session.accepts(id)) speak(text) }.also { main.postDelayed(it, 5_000) }
        Thread {
            val bytes = try { loadAudio { !session.accepts(id) } } catch (_: Exception) { null }
            val audio = bytes?.let(SpeechAudio::parse)
            main.post {
                if (!session.accepts(id)) return@post
                if (audio == null) { speak(text); return@post }
                onAvailable(true)
                audioPlayer = SpeechAudioPlayer(appContext, audio,
                    onStart = {
                        if (session.accepts(id)) {
                            cloudTimeout?.let(main::removeCallbacks)
                            cloudTimeout = null
                            speaking = true
                            if (BuildConfig.DEBUG) Log.i("FridayVoice", "event=cloud_speech_started")
                            onSpeaking(true)
                        }
                    },
                    onAudio = { level -> if (session.accepts(id) && !detached) onAudio(level) },
                    onFinish = { complete(id) },
                    onFailure = { started -> if (session.accepts(id)) { if (started) complete(id, interrupted = true) else speak(text) } },
                    onInterrupted = { complete(id, interrupted = true) },
                ).also { it.start() }
            }
        }.apply { name = "Friday-speech-fetch"; isDaemon = true; start() }
    }

    private fun speak(message: String, offlineOnly: Boolean) {
        if (closed) return
        val text = message.trim().takeIf { it.isNotEmpty() } ?: return
        stop()
        val id = session.begin()
        // Own pending engine startup too, so a new command can cancel old speech before it starts.
        SharedTextToSpeech.claimPlayback(this, listener)
        DebugTools.beginSpeechPlayback(this)
        pending = PendingSpeech(text, id, offlineOnly)
        if (ready) speakPending() else if (!starting) start()
    }

    fun stop() {
        session.cancel()
        cloudTimeout?.let(main::removeCallbacks)
        cloudTimeout = null
        audioPlayer?.stop()
        audioPlayer = null
        pending = null
        frames.clear()
        main.removeCallbacksAndMessages(audioCallbacks)
        SharedTextToSpeech.releasePlayback(this, stop = true)
        restoreSpeechVoice()
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
        onInterrupted = { shutdown() }
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
            engine = current
            ready = current != null
            val waiting = pending?.takeIf { session.accepts(it.id) } ?: return@acquire
            if (current == null) {
                pending = null
                onAvailable(false)
                complete(waiting.id)
                return@acquire
            }
            onAvailable(true)
            speakPending()
        }
    }

    private fun speakPending() {
        val (text, id, offlineOnly) = pending ?: return
        pending = null
        if (!session.accepts(id)) return
        val current = engine
        if (offlineOnly && (current == null || !selectOfflineVoice(current))) {
            complete(id)
            return
        }
        SharedTextToSpeech.claimPlayback(this, listener)
        if (current?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) complete(id)
    }

    private fun complete(id: String?, interrupted: Boolean = false) {
        if (!session.finish(id)) return
        cloudTimeout?.let(main::removeCallbacks)
        cloudTimeout = null
        audioPlayer?.stop()
        audioPlayer = null
        if (BuildConfig.DEBUG) Log.i("FridayVoice", "event=confirmation_finished detached=$detached")
        SharedTextToSpeech.releasePlayback(this, stop = false)
        restoreSpeechVoice()
        DebugTools.endSpeechPlayback(this)
        detachedTimeout?.let(main::removeCallbacks)
        detachedTimeout = null
        frames.clear()
        main.removeCallbacksAndMessages(audioCallbacks)
        if (speaking) { speaking = false; onSpeaking(false) }
        if (interrupted) onInterrupted() else onFinished()
    }

    private fun selectOfflineVoice(current: TextToSpeech): Boolean = runCatching {
        val original = current.voice
        if (original?.isInstalledOffline() == true) return true
        val available = current.voices.orEmpty().filter { it.locale.language == Locale.ENGLISH.language && it.isInstalledOffline() }
        val voice = available.firstOrNull { it.locale == original?.locale } ?: available.firstOrNull() ?: return false
        if (current.setVoice(voice) != TextToSpeech.SUCCESS) return false
        restoreVoice = {
            if (original == null) current.setLanguage(Locale.ENGLISH) else current.setVoice(original)
        }
        current.voice?.isInstalledOffline() == true
    }.getOrDefault(false)

    private fun restoreSpeechVoice() {
        val restore = restoreVoice ?: return
        restoreVoice = null
        runCatching { restore() }
    }

    private fun Voice.isInstalledOffline(): Boolean =
        !isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()

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
        override fun onError(id: String?) { main.post { complete(id, interrupted = true) } }
        override fun onError(id: String?, errorCode: Int) { main.post { complete(id, interrupted = true) } }
        override fun onStop(id: String?, interrupted: Boolean) { main.post { complete(id, interrupted) } }

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

    private data class PendingSpeech(val text: String, val id: String, val offlineOnly: Boolean)
    private data class AudioFrame(val offsetMs: Long, val level: PcmProcessor.Level)

    companion object {
        fun warmUp(context: Context) = SharedTextToSpeech.prepare(context.applicationContext)
        fun stopPlayback() = SharedTextToSpeech.stopPlayback()
    }
}
