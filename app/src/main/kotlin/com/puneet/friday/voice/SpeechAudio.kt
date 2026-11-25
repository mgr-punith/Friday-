package com.puneet.friday.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded PCM for cloud speech; accepts WAV metadata chunks, never URLs or files. */
internal data class SpeechAudio(val samples: ShortArray) {
    val durationMs: Long get() = samples.size * 1_000L / WavEncoder.SAMPLE_RATE

    companion object {
        fun parse(bytes: ByteArray): SpeechAudio? {
            if (bytes.size !in 46..1_920_044) return null
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun name(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
            if (name(0) != "RIFF" || name(8) != "WAVE" || input.getInt(4).toLong() + 8 != bytes.size.toLong()) return null
            var format = false
            var audio: ShortArray? = null
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val length = input.getInt(offset + 4)
                if (length < 0 || offset.toLong() + 8 + length > bytes.size) return null
                val start = offset + 8
                when (name(offset)) {
                    "fmt " -> {
                        if (format || length < 16 || input.getShort(start).toInt() != 1 ||
                            input.getShort(start + 2).toInt() != 1 || input.getInt(start + 4) != WavEncoder.SAMPLE_RATE ||
                            input.getInt(start + 8) != WavEncoder.SAMPLE_RATE * 2 || input.getShort(start + 12).toInt() != 2 ||
                            input.getShort(start + 14).toInt() != 16) return null
                        format = true
                    }
                    "data" -> {
                        if (audio != null || length == 0 || length % 2 != 0 || length > WavEncoder.SAMPLE_RATE * 2 * 60) return null
                        audio = ShortArray(length / 2) { index -> input.getShort(start + index * 2) }
                    }
                }
                offset = start + length + length % 2
            }
            return if (format && offset == bytes.size) audio?.let(::SpeechAudio) else null
        }
    }
}
