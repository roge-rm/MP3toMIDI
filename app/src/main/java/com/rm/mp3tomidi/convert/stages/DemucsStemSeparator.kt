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
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.coroutines.CancellationException

/**
 * Separates a mixture into htdemucs_6s's 6 stems by running the ONNX export of that model
 * (see tools/demucs_export/) in overlapping [SEGMENT_LENGTH]-sample windows, cross-faded back
 * together per [OverlapAddScheduler] -- the same scheme demucs.apply.apply_model uses, so
 * segment boundaries don't produce audible seams.
 *
 * The model itself (~235MB) isn't bundled in the app; it's downloaded once via [ModelProvider]
 * and cached in app-private storage, so every conversion after the first is fully offline.
 *
 * Separated audio is streamed to disk via [StreamingOverlapAdder] rather than accumulated in
 * memory for the whole song across all 6 stems at once (that's what OOMs on a several-minute
 * song). The decoded *input* mixture is still one in-memory array for the whole song, which
 * remains a scaling limit for very long inputs; streaming that too would need chunked decode
 * with random-access re-seeking instead of AudioDecoder's single-pass whole-file decode.
 *
 * [isCancelled] is polled explicitly between chunks rather than relying on coroutine cancellation
 * (`coroutineContext.ensureActive()`) -- verified on-device that plain cancellation does *not*
 * reliably work here: cancelling a running conversion via `WorkManager.cancelWorkById()` updated
 * WorkManager's own state immediately, but the actual coroutine kept running for many more seconds
 * afterward, completing several more chunks (`ensureActive()` between them never threw). Each
 * chunk's ONNX inference (`session.run(...)`) is a long blocking native/JNI call, not a suspension
 * point, so coroutine cancellation checks only get a chance to run between chunks in the first
 * place -- and even there, it wasn't taking effect. [ListenableWorker.isStopped][androidx.work.ListenableWorker.isStopped]
 * (threaded down from [com.rm.mp3tomidi.convert.ConversionWorker] as [isCancelled]) is a plain
 * synchronous flag WorkManager sets directly, not dependent on that cancellation machinery.
 */
class DemucsStemSeparator : StemSeparator {

    override suspend fun separate(
        context: Context,
        inputAudio: Uri,
        durationUs: Long,
        isCancelled: () -> Boolean,
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): List<RawStem> {
        val modelFile = ModelProvider.ensureAvailable(context, MODEL_SPEC, isCancelled) { fraction ->
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
        val sessionOptions = OrtSession.SessionOptions().apply {
            // With ORT's default settings this model's peak RSS is ~4.9GB (measured directly,
            // both on-device and via onnxruntime's Python bindings on the same .onnx file) and
            // reaches that within the first couple of chunks -- not a leak, just a huge
            // high-water mark that the default memory-pattern planner and arena allocator both
            // retain for the session's lifetime. Disabling both drops it to ~750MB with no
            // accuracy change (same graph, same weights): isolated each setting independently
            // and confirmed empirically before picking this combination, since arena alone
            // made it *worse* in one measurement.
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
        }
        val session = env.createSession(modelFile.absolutePath, sessionOptions)

        val pcmFiles = SOURCES.map { File.createTempFile("stem_$it", ".pcm", context.cacheDir) }
        val outputs = pcmFiles.map { BufferedOutputStream(it.outputStream()) }
        val adder = StreamingOverlapAdder(SOURCES.size, CHANNELS, SEGMENT_LENGTH)

        try {
            val placements = scheduler.chunkPlacements()
            placements.forEachIndexed { index, placement ->
                if (isCancelled()) throw CancellationException("Conversion cancelled")

                val chunkOutput = runChunk(session, env, audio, placement)
                adder.addChunk(placement, scheduler.weight, chunkOutput)

                val nextOffset = if (index + 1 < placements.size) placements[index + 1].offset else audio.frameCount
                adder.flushUpTo(nextOffset)?.let { flushed ->
                    flushed.forEachIndexed { sourceIndex, samples -> writePcm(outputs[sourceIndex], samples) }
                }

                onProgress(
                    "Separating stems (${index + 1}/${placements.size})",
                    lerp(SEPARATE_RANGE_START, SEPARATE_RANGE_END, (index + 1).toFloat() / placements.size),
                )
            }
        } catch (e: Throwable) {
            // On success, these files become RawStem.pcmFile and ConversionPipeline.convert()'s
            // own finally block takes over deleting them once every stage is done. On failure or
            // cancellation here, no RawStem is ever created to hand that ownership off, so this is
            // the only place that still knows about them -- without this, a cancelled or failed
            // separation leaks up to 6 multi-hundred-MB-scale temp PCM files per attempt.
            pcmFiles.forEach { it.delete() }
            throw e
        } finally {
            session.close()
            sessionOptions.close()
            outputs.forEach { it.close() }
        }

        return SOURCES.mapIndexed { index, label ->
            RawStem(
                label = label,
                durationUs = durationUs,
                pcmFile = pcmFiles[index],
                sampleRate = SAMPLE_RATE,
                channelCount = CHANNELS,
            )
        }
    }

    private fun writePcm(output: OutputStream, samples: FloatArray) {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(samples)
        output.write(buffer.array())
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

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2

        // htdemucs_6s's trained segment length: round(44100 * 39/5 seconds).
        const val SEGMENT_LENGTH = 343_980
        const val OVERLAP = 0.25f

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
