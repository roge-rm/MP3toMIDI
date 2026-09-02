package com.rm.mp3tomidi.convert.stages

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import com.rm.mp3tomidi.util.AudioDecoder
import com.rm.mp3tomidi.util.DecodedAudio
import java.nio.FloatBuffer

/**
 * Separates a mixture into htdemucs_6s's 6 stems by running the ONNX export of that model
 * (see tools/demucs_export/) in overlapping [SEGMENT_LENGTH]-sample windows, cross-faded back
 * together per [OverlapAddScheduler] -- the same scheme demucs.apply.apply_model uses, so
 * segment boundaries don't produce audible seams.
 */
class DemucsStemSeparator(
    private val assetPath: String = "models/htdemucs_6s.onnx",
) : StemSeparator {

    override suspend fun separate(context: Context, inputAudio: Uri, durationUs: Long): List<RawStem> {
        val audio = AudioDecoder.decode(context, inputAudio, SAMPLE_RATE, CHANNELS)
        val scheduler = OverlapAddScheduler(
            totalLength = audio.frameCount,
            segmentLength = SEGMENT_LENGTH,
            overlap = OVERLAP,
        )

        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(assetPath).use { it.readBytes() }
        val session = env.createSession(modelBytes, OrtSession.SessionOptions())

        val accum = Array(SOURCES.size) { FloatArray(audio.frameCount * CHANNELS) }
        val sumWeight = FloatArray(audio.frameCount)

        try {
            for (placement in scheduler.chunkPlacements()) {
                val chunkOutput = runChunk(session, env, audio, placement)
                accumulate(accum, sumWeight, chunkOutput, placement, scheduler.weight)
            }
        } finally {
            session.close()
        }

        normalize(accum, sumWeight)

        return SOURCES.mapIndexed { index, label ->
            RawStem(
                label = label,
                durationUs = durationUs,
                interleavedPcm = accum[index],
                sampleRate = SAMPLE_RATE,
                channelCount = CHANNELS,
            )
        }
    }

    /** Runs one model-input window; returns the flat (source, channel, sample) output. */
    private fun runChunk(
        session: OrtSession,
        env: OrtEnvironment,
        audio: DecodedAudio,
        placement: ChunkPlacement,
    ): FloatArray {
        val input = FloatArray(CHANNELS * SEGMENT_LENGTH)
        for (ch in 0 until CHANNELS) {
            val channelBase = ch * SEGMENT_LENGTH
            for (i in 0 until placement.readLength) {
                val srcFrame = placement.readStart + i
                input[channelBase + placement.padLeft + i] = audio.interleavedPcm[srcFrame * CHANNELS + ch]
            }
        }

        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), INPUT_SHAPE)
        return inputTensor.use {
            session.run(mapOf(INPUT_NAME to it)).use { result ->
                val floatBuffer = (result[0] as OnnxTensor).floatBuffer
                val out = FloatArray(floatBuffer.remaining())
                floatBuffer.get(out)
                out
            }
        }
    }

    private fun accumulate(
        accum: Array<FloatArray>,
        sumWeight: FloatArray,
        chunkOutput: FloatArray,
        placement: ChunkPlacement,
        weight: FloatArray,
    ) {
        for (sourceIndex in SOURCES.indices) {
            val sourceBase = sourceIndex * CHANNELS * SEGMENT_LENGTH
            for (i in 0 until placement.validLength) {
                val w = weight[placement.trimStart + i]
                val t = placement.offset + i
                for (ch in 0 until CHANNELS) {
                    val chunkIdx = sourceBase + ch * SEGMENT_LENGTH + placement.trimStart + i
                    accum[sourceIndex][t * CHANNELS + ch] += w * chunkOutput[chunkIdx]
                }
            }
        }
        for (i in 0 until placement.validLength) {
            sumWeight[placement.offset + i] += weight[placement.trimStart + i]
        }
    }

    private fun normalize(accum: Array<FloatArray>, sumWeight: FloatArray) {
        val frameCount = sumWeight.size
        for (source in accum) {
            for (frame in 0 until frameCount) {
                val w = sumWeight[frame].coerceAtLeast(MIN_WEIGHT)
                for (ch in 0 until CHANNELS) {
                    source[frame * CHANNELS + ch] /= w
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2

        // htdemucs_6s's trained segment length: round(44100 * 39/5 seconds).
        const val SEGMENT_LENGTH = 343_980
        const val OVERLAP = 0.25f
        private const val MIN_WEIGHT = 1e-8f

        private const val INPUT_NAME = "mixture"
        private val INPUT_SHAPE = longArrayOf(1, CHANNELS.toLong(), SEGMENT_LENGTH.toLong())

        val SOURCES = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
    }
}
