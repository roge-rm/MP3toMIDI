package com.rm.mp3tomidi.convert.stages

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import com.rm.mp3tomidi.util.AudioDecoder
import com.rm.mp3tomidi.util.DecodedAudio
import com.rm.mp3tomidi.util.ModelProvider
import com.rm.mp3tomidi.util.ModelSpec
import java.nio.FloatBuffer

/**
 * Separates a mixture into htdemucs_6s's 6 stems by running the ONNX export of that model
 * (see tools/demucs_export/) in overlapping [SEGMENT_LENGTH]-sample windows, cross-faded back
 * together per [OverlapAddScheduler] -- the same scheme demucs.apply.apply_model uses, so
 * segment boundaries don't produce audible seams.
 *
 * The model itself (~235MB) isn't bundled in the app; it's downloaded once via [ModelProvider]
 * and cached in app-private storage, so every conversion after the first is fully offline.
 */
class DemucsStemSeparator : StemSeparator {

    override suspend fun separate(
        context: Context,
        inputAudio: Uri,
        durationUs: Long,
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): List<RawStem> {
        val modelFile = ModelProvider.ensureAvailable(context, MODEL_SPEC) { fraction ->
            onProgress(
                "Downloading separation model (${(fraction * 100).toInt()}%)",
                lerp(DOWNLOAD_RANGE_START, DOWNLOAD_RANGE_END, fraction),
            )
        }

        val audio = AudioDecoder.decode(context, inputAudio, SAMPLE_RATE, CHANNELS)
        val scheduler = OverlapAddScheduler(
            totalLength = audio.frameCount,
            segmentLength = SEGMENT_LENGTH,
            overlap = OVERLAP,
        )

        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

        val accum = Array(SOURCES.size) { FloatArray(audio.frameCount * CHANNELS) }
        val sumWeight = FloatArray(audio.frameCount)

        try {
            val placements = scheduler.chunkPlacements()
            placements.forEachIndexed { index, placement ->
                val chunkOutput = runChunk(session, env, audio, placement)
                accumulate(accum, sumWeight, chunkOutput, placement, scheduler.weight)
                onProgress(
                    "Separating stems (${index + 1}/${placements.size})",
                    lerp(SEPARATE_RANGE_START, SEPARATE_RANGE_END, (index + 1).toFloat() / placements.size),
                )
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

    private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

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

        // These two ranges fill the (0.2, 0.5) progress window ConversionPipeline's
        // "Separating stems" / "Transcribing notes" checkpoints bracket -- see
        // ConversionPipeline.kt.
        private const val DOWNLOAD_RANGE_START = 0.2f
        private const val DOWNLOAD_RANGE_END = 0.35f
        private const val SEPARATE_RANGE_START = 0.35f
        private const val SEPARATE_RANGE_END = 0.5f

        val MODEL_SPEC = ModelSpec(
            fileName = "htdemucs_6s.onnx",
            downloadUrl = "https://github.com/roge-rm/MP3toMIDI/releases/download/htdemucs-6s-v1/htdemucs_6s.onnx",
            sha256 = "5e96a660f3b12bdcde51505736fb3e958c7e12d764c0fb84e0f5a2e526560464",
        )
    }
}
