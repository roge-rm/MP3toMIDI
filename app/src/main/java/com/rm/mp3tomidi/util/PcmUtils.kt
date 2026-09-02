package com.rm.mp3tomidi.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared interleaved-PCM remixing/resampling, used both when decoding input audio and when a
 * separated stem (already PCM) needs conforming to a different model's expected format. */
object PcmUtils {

    /** Reads a raw interleaved 32-bit float PCM file, little-endian (see DemucsStemSeparator). */
    fun readInterleavedPcm(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    fun remixChannels(pcm: FloatArray, sourceChannels: Int, targetChannels: Int): FloatArray {
        if (sourceChannels == targetChannels) return pcm
        val frameCount = pcm.size / sourceChannels
        val out = FloatArray(frameCount * targetChannels)
        for (frame in 0 until frameCount) {
            if (sourceChannels == 1 && targetChannels > 1) {
                val sample = pcm[frame]
                for (ch in 0 until targetChannels) out[frame * targetChannels + ch] = sample
            } else {
                // Downmix (or channel-count-mismatch fallback): average available source channels
                // into every target channel.
                var sum = 0f
                for (ch in 0 until sourceChannels) sum += pcm[frame * sourceChannels + ch]
                val mono = sum / sourceChannels
                for (ch in 0 until targetChannels) out[frame * targetChannels + ch] = mono
            }
        }
        return out
    }

    fun resampleLinear(pcm: FloatArray, channels: Int, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return pcm
        val frameCount = pcm.size / channels
        val outFrameCount = ((frameCount.toLong() * toRate) / fromRate).toInt()
        val out = FloatArray(outFrameCount * channels)
        val ratio = fromRate.toDouble() / toRate
        for (outFrame in 0 until outFrameCount) {
            val srcPos = outFrame * ratio
            val srcFrame0 = srcPos.toInt()
            val srcFrame1 = minOf(srcFrame0 + 1, frameCount - 1)
            val frac = (srcPos - srcFrame0).toFloat()
            for (ch in 0 until channels) {
                val a = pcm[srcFrame0 * channels + ch]
                val b = pcm[srcFrame1 * channels + ch]
                out[outFrame * channels + ch] = a + (b - a) * frac
            }
        }
        return out
    }
}
