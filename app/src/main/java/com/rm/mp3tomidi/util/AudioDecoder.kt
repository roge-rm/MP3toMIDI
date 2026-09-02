package com.rm.mp3tomidi.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded PCM audio, resampled/remixed to [sampleRate]/[channelCount]. */
data class DecodedAudio(
    val interleavedPcm: FloatArray,
    val sampleRate: Int,
    val channelCount: Int,
) {
    val frameCount: Int get() = interleavedPcm.size / channelCount
}

/** Decodes a compressed audio file to PCM via MediaExtractor/MediaCodec, then conforms it to a target format. */
object AudioDecoder {

    fun decode(context: Context, uri: Uri, targetSampleRate: Int, targetChannelCount: Int): DecodedAudio {
        val raw = decodeToNativeFormat(context, uri)
        val remixed = remixChannels(raw.interleavedPcm, raw.channelCount, targetChannelCount)
        val resampled = if (raw.sampleRate == targetSampleRate) {
            remixed
        } else {
            resampleLinear(remixed, targetChannelCount, raw.sampleRate, targetSampleRate)
        }
        return DecodedAudio(resampled, targetSampleRate, targetChannelCount)
    }

    private fun decodeToNativeFormat(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val trackIndex = (0 until extractor.trackCount).first { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmChunks = mutableListOf<ShortArray>()
            var totalSamples = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = ShortArray(bufferInfo.size / 2)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            pcmChunks += shorts
                            totalSamples += shorts.size
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            val pcm = FloatArray(totalSamples)
            var offset = 0
            for (chunk in pcmChunks) {
                for (sample in chunk) {
                    pcm[offset++] = sample / 32768f
                }
            }
            return DecodedAudio(pcm, sampleRate, channelCount)
        } finally {
            extractor.release()
        }
    }

    private fun remixChannels(pcm: FloatArray, sourceChannels: Int, targetChannels: Int): FloatArray {
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

    private fun resampleLinear(pcm: FloatArray, channels: Int, fromRate: Int, toRate: Int): FloatArray {
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

    private const val TIMEOUT_US = 10_000L
}
