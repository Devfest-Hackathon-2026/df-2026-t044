package com.example.yobareta.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.min

/**
 * Privacy-first in-RAM circular buffer for 16 kHz mono 16-bit PCM audio.
 *
 * - Default capacity: 15 seconds (240,000 samples = 480 KB in RAM).
 * - Never writes audio to disk.
 * - Provides 1.0 s pre-roll extraction when the on-device gate opens, and
 *   10.0 s instant replay extraction when a 'Called' or 'Re-call' alert fires.
 */
class AudioRingBuffer(
    val sampleRateHz: Int = 16000,
    val capacitySeconds: Int = 15
) {
    private val capacitySamples: Int = sampleRateHz * capacitySeconds
    private val buffer = ShortArray(capacitySamples)
    private var writePos: Int = 0
    private var totalWritten: Long = 0L

    @Synchronized
    fun append(samples: ShortArray, count: Int = samples.size) {
        val n = min(count, samples.size)
        for (i in 0 until n) {
            buffer[writePos] = samples[i]
            writePos = (writePos + 1) % capacitySamples
        }
        totalWritten += n
    }

    @Synchronized
    fun readLastSeconds(seconds: Float): ShortArray {
        val requestedSamples = (seconds * sampleRateHz).toInt().coerceIn(0, capacitySamples)
        val availableSamples = min(totalWritten, capacitySamples.toLong()).toInt()
        val count = min(requestedSamples, availableSamples)
        if (count <= 0) return ShortArray(0)

        val out = ShortArray(count)
        var startIdx = writePos - count
        if (startIdx < 0) startIdx += capacitySamples

        val firstChunk = min(count, capacitySamples - startIdx)
        System.arraycopy(buffer, startIdx, out, 0, firstChunk)
        if (firstChunk < count) {
            System.arraycopy(buffer, 0, out, firstChunk, count - firstChunk)
        }
        return out
    }

    @Synchronized
    fun clear() {
        Arrays.fill(buffer, 0.toShort())
        writePos = 0
        totalWritten = 0L
    }

    companion object {
        fun shortsToPcm16LeBytes(samples: ShortArray): ByteArray {
            val byteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asShortBuffer().put(samples)
            return byteBuffer.array()
        }
    }
}
