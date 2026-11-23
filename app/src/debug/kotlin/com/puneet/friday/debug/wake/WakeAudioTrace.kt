package com.puneet.friday.debug.wake

import android.content.Context
import android.os.SystemClock
import com.puneet.friday.voice.WavEncoder
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Explicit debug capture. The only audio written is a bounded window before an accepted wake. */
internal object WakeAudioTrace {
    private const val TRACE_MS = 15 * 60 * 1_000L
    private const val MAX_CLIPS = 16
    private const val DIRECTORY = "wake-audio-trace"
    private val worker = ThreadPoolExecutor(
        0, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        ThreadPoolExecutor.DiscardPolicy(),
    )
    private var enabledUntil = 0L
    private var revision = 0L

    @Synchronized fun isActive(): Boolean = SystemClock.elapsedRealtime() < enabledUntil

    @Synchronized fun start() {
        revision++
        enabledUntil = SystemClock.elapsedRealtime() + TRACE_MS
    }

    @Synchronized fun stop() {
        enabledUntil = 0L
        revision++
    }

    @Synchronized fun begin(context: Context): Capture? =
        if (isActive()) Capture(context.applicationContext, revision) else null

    fun count(context: Context): Int = clips(directory(context)).size

    @Synchronized fun clear(context: Context) {
        revision++
        clips(directory(context)).forEach(File::delete)
    }

    @Synchronized private fun accepts(ticket: Long): Boolean = revision == ticket && isActive()

    private fun directory(context: Context) = File(context.noBackupFilesDir, DIRECTORY)

    private fun clips(directory: File) = directory.listFiles { file ->
        file.isFile && file.extension == "wav"
    }.orEmpty().sortedBy { it.name }

    @Synchronized private fun save(context: Context, ticket: Long, time: Long, samples: ShortArray) {
        if (!accepts(ticket)) return
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return
        val destination = File(directory, "$time.wav")
        val pending = File(directory, "$time.pending")
        try {
            pending.writeBytes(WavEncoder.encode(samples))
            if (pending.renameTo(destination)) clips(directory).dropLast(MAX_CLIPS).forEach(File::delete)
        } catch (_: java.io.IOException) {
            // Tracing must not interrupt wake listening when storage is unavailable.
        } finally {
            pending.delete()
        }
    }

    internal class Capture(private val context: Context, private val ticket: Long) {
        private val buffer = WakeAudioBuffer()
        private var closed = false

        fun append(samples: ShortArray, count: Int, triggered: Boolean) {
            if (closed) return
            if (!accepts(ticket)) {
                close()
                return
            }
            buffer.append(samples, count)
            if (triggered) {
                val clip = buffer.copy()
                val time = System.currentTimeMillis()
                worker.execute {
                    try {
                        save(context, ticket, time, clip)
                    } finally {
                        clip.fill(0)
                    }
                }
            }
        }

        fun close() {
            closed = true
            buffer.clear()
        }
    }
}

internal class WakeAudioBuffer(capacity: Int = WavEncoder.SAMPLE_RATE * 2) {
    private val samples = ShortArray(capacity)
    private var end = 0
    private var size = 0

    fun append(input: ShortArray, count: Int) {
        for (index in 0 until count) {
            samples[end] = input[index]
            if (++end == samples.size) end = 0
        }
        size = minOf(size + count, samples.size)
    }

    fun copy(): ShortArray {
        var start = (end - size + samples.size) % samples.size
        return ShortArray(size) {
            val value = samples[start]
            if (++start == samples.size) start = 0
            value
        }
    }

    fun clear() {
        samples.fill(0)
        end = 0
        size = 0
    }
}
