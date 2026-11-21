package com.puneet.friday.debug.wake

import java.io.File
import java.io.DataInputStream

internal interface WakeDetector : AutoCloseable {
    fun process(samples: ShortArray, count: Int): Boolean
}

internal class MicroWakeWordDetector(filesDir: File, threshold: Float) : WakeDetector {
    private val owner = Thread.currentThread()
    private var handle: Long

    init {
        check(MicroWakeWordNative.available) { "Detector runtime missing. Install the wake-test debug build." }
        val file = File(filesDir, WakeTrialModel.RELATIVE_PATH)
        check(file.isFile && file.length() == WakeTrialModel.BYTES.toLong()) {
            "Wake model missing. Install the approved test model."
        }
        val bytes = ByteArray(WakeTrialModel.BYTES)
        DataInputStream(file.inputStream()).use {
            it.readFully(bytes)
            check(it.read() == -1) { "Wake model doesn't match the approved version." }
        }
        check(WakeTrialModel.accepts(bytes)) { "Wake model doesn't match the approved version." }
        handle = MicroWakeWordNative.create(bytes, threshold)
        check(handle != 0L) { "Couldn't load the wake model." }
    }

    override fun process(samples: ShortArray, count: Int): Boolean {
        check(Thread.currentThread() === owner && handle != 0L) { "Detector is not active" }
        require(count in 1..480 && count <= samples.size)
        val result = MicroWakeWordNative.process(handle, samples, count)
        check(result >= 0) { "Detector couldn't read audio." }
        return result == 1
    }

    override fun close() {
        check(Thread.currentThread() === owner)
        if (handle != 0L) {
            MicroWakeWordNative.destroy(handle)
            handle = 0
        }
    }
}

internal object MicroWakeWordNative {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("microwakeword")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    external fun create(model: ByteArray, threshold: Float): Long
    external fun process(handle: Long, samples: ShortArray, count: Int): Int
    external fun destroy(handle: Long)
}
