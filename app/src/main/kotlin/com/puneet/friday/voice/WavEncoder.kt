package com.puneet.friday.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    fun encode(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val output = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        output.putAscii("RIFF")
        output.putInt(36 + dataBytes)
        output.putAscii("WAVE")
        output.putAscii("fmt ")
        output.putInt(16)
        output.putShort(1.toShort())
        output.putShort(CHANNELS.toShort())
        output.putInt(SAMPLE_RATE)
        output.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
        output.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
        output.putShort(BITS_PER_SAMPLE.toShort())
        output.putAscii("data")
        output.putInt(dataBytes)
        samples.forEach(output::putShort)
        return output.array()
    }

    fun isFridayWav(bytes: ByteArray): Boolean {
        if (bytes.size < 44) return false
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.ascii(0, 4) != "RIFF" || bytes.ascii(8, 12) != "WAVE") return false
        if (input.getInt(4) + 8 != bytes.size) return false
        return bytes.ascii(12, 16) == "fmt " && input.getInt(16) >= 16 &&
            input.getShort(20).toInt() == 1 && input.getShort(22).toInt() == CHANNELS &&
            input.getInt(24) == SAMPLE_RATE && input.getInt(28) == SAMPLE_RATE * 2 &&
            input.getShort(32).toInt() == 2 && input.getShort(34).toInt() == BITS_PER_SAMPLE &&
            bytes.ascii(36, 40) == "data" && input.getInt(40) == bytes.size - 44 && input.getInt(40) > 0
    }

    private fun ByteBuffer.putAscii(value: String) {
        put(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArray.ascii(start: Int, end: Int): String =
        String(this, start, end - start, Charsets.US_ASCII)
}
