package com.puneet.friday.debug.wake

import android.content.res.AssetManager
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

internal class FridayKeywordDetector(
    private val model: FridayKeywordModel,
    sensitivity: Float,
    private val observeScore: ((margin: Float, streak: Int) -> Unit)? = null,
) : WakeDetector {
    private val features = FridayKeywordFeatures(model.melBank)
    private val cutoff = model.cutoffFor(sensitivity)
        .let { threshold -> ln(threshold / (1f - threshold)) }
    private var above = 0
    private var lastWake = -32_000L
    private var samplesRead = 0L
    var wakeSampleCount = 0
        private set

    override fun process(samples: ShortArray, count: Int): Boolean {
        require(count in 1..samples.size)
        wakeSampleCount = 0
        for (index in 0 until count) {
            samplesRead++
            if (!features.append(samples[index])) continue
            val score = if (features.hasRecentSound) model.score(features.input) else Float.NEGATIVE_INFINITY
            val qualifies = score >= cutoff
            if (!qualifies) {
                above = 0
            } else if (above < 3) {
                above++
                if (above == 3 && samplesRead - lastWake >= 32_000L) {
                    lastWake = samplesRead
                    wakeSampleCount = index + 1
                }
            }
            observeScore?.invoke(score - cutoff, above)
        }
        return wakeSampleCount != 0
    }

    override fun close() = Unit
}

internal class FridayKeywordModel private constructor(values: ByteBuffer, val cutoff: Float) {
    val melBank = values.takeFloats(FridayKeywordFeatures.BANDS * FridayKeywordFeatures.BINS)
    private val first = Convolution(values, 1, 10, 24, 144, 5, 7, 2, 3)
    private val second = Convolution(values, 10, 14, 12, 48, 3, 5, 2, 2)
    private val third = Convolution(values, 14, 16, 6, 24, 3, 5, 2, 2)
    private val weights = values.takeFloats(96)
    private val bias = values.float

    fun cutoffFor(sensitivity: Float): Float {
        val margin = minOf(cutoff, 1f - cutoff)
        return (cutoff + (sensitivity - 1f) * margin).coerceAtMost(0.999999f)
    }

    fun score(input: FloatArray): Float {
        val hidden = third.run(second.run(first.run(input)))
        var result = bias
        // Adaptive average pooling maps the three rows to two overlapping pairs.
        for (channel in 0 until 16) for (row in 0 until 2) for (column in 0 until 3) {
            val start = channel * 36 + row * 12 + column * 4
            var sum = 0f
            for (offset in 0 until 4) sum += hidden[start + offset] + hidden[start + 12 + offset]
            result += sum * 0.125f * weights[channel * 6 + row * 3 + column]
        }
        return result
    }

    private class Convolution(
        values: ByteBuffer,
        private val inputChannels: Int,
        private val outputChannels: Int,
        private val rows: Int,
        private val columns: Int,
        private val kernelRows: Int,
        private val kernelColumns: Int,
        private val rowStride: Int,
        private val columnStride: Int,
    ) {
        private val weights = values.takeFloats(outputChannels * inputChannels * kernelRows * kernelColumns)
        private val biases = values.takeFloats(outputChannels)
        private val outputRows = rows / rowStride
        private val outputColumns = columns / columnStride
        private val output = FloatArray(outputChannels * outputRows * outputColumns)

        fun run(input: FloatArray): FloatArray {
            var destination = 0
            for (channel in 0 until outputChannels) {
                for (row in 0 until outputRows) {
                    val top = row * rowStride - kernelRows / 2
                    val rowStart = maxOf(0, -top)
                    val rowEnd = minOf(kernelRows, rows - top)
                    for (column in 0 until outputColumns) {
                        val left = column * columnStride - kernelColumns / 2
                        val columnStart = maxOf(0, -left)
                        val columnEnd = minOf(kernelColumns, columns - left)
                        var sum = biases[channel]
                        for (source in 0 until inputChannels) {
                            val kernel = (channel * inputChannels + source) * kernelRows * kernelColumns
                            val plane = source * rows * columns
                            for (dy in rowStart until rowEnd) {
                                var sample = plane + (top + dy) * columns + left + columnStart
                                var weight = kernel + dy * kernelColumns + columnStart
                                for (dx in columnStart until columnEnd) sum += input[sample++] * weights[weight++]
                            }
                        }
                        output[destination++] = maxOf(sum, 0f)
                    }
                }
            }
            return output
        }
    }

    companion object {
        const val ASSET = "friday-keyword.bin"
        private const val MAGIC = 0x46574B31
        private const val BYTES = 4 * (2 + 24 * 257 + 350 + 10 + 2100 + 14 + 3360 + 16 + 96 + 1)
        const val MISSING = "Install the wake-enabled test build to use Hey Friday."
        private const val INVALID = "Couldn't load Hey Friday. Reinstall the wake-enabled test build."

        fun available(assets: AssetManager): Boolean = try {
            assets.open(ASSET).use { true }
        } catch (_: IOException) {
            false
        }

        fun load(assets: AssetManager): FridayKeywordModel =
            assets.open(ASSET).use(::read)

        fun read(input: InputStream): FridayKeywordModel {
            val bytes = ByteArray(BYTES)
            val stream = DataInputStream(input)
            stream.readFully(bytes)
            check(stream.read() == -1) { INVALID }
            val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(values.int == MAGIC) { INVALID }
            val cutoff = values.float
            check(cutoff > 0f && cutoff < 1f) { INVALID }
            return FridayKeywordModel(values, cutoff)
        }

        private fun ByteBuffer.takeFloats(count: Int) = FloatArray(count) {
            float.also { check(it.isFinite()) { INVALID } }
        }
    }
}
