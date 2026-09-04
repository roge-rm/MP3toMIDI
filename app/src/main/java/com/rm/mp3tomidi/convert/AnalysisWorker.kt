package com.rm.mp3tomidi.convert

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * First half of the conversion pipeline: separate -> transcribe -> classify, stopping short of
 * writing any MIDI output. Split out of the single worker that used to run separate-through-write
 * in one uninterruptible pass, so [ReviewDialog] has a real pause point with real detected-
 * instrument data to show, between this worker succeeding and [WriteWorker] running. Results are
 * cached to disk (see [IntermediateResultStore]) rather than held in WorkManager's own Data (which
 * has a small size limit unsuited to potentially tens of thousands of note events).
 */
class AnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse) ?: return Result.failure()
        // Not used by this worker's own pipeline -- carried through purely so a fresh ViewModel
        // reconnecting by tag after process death can restore the write destination too, the same
        // way KEY_INPUT_URI already gets echoed back below. Without this, Write MIDI fails right
        // after a reconnect because the new ViewModel's outputUri/outputDirUri were never set.
        val outputUri = inputData.getString(KEY_OUTPUT_URI)
        val outputDirUri = inputData.getString(KEY_OUTPUT_DIR_URI)
        val options = ConversionOptions(
            includedStemLabels = inputData.getStringArray(KEY_INCLUDED_STEMS)?.toSet()
                ?: ConversionOptions().includedStemLabels,
            noteFrameThreshold = inputData.getFloat(KEY_NOTE_FRAME_THRESHOLD, ConversionOptions.DEFAULT_NOTE_FRAME_THRESHOLD),
            silentStemRmsRatio = inputData.getFloat(KEY_SILENT_STEM_RATIO, ConversionOptions.DEFAULT_SILENT_STEM_RMS_RATIO),
            outputMode = inputData.getString(KEY_OUTPUT_MODE)?.let(OutputMode::valueOf) ?: OutputMode.SINGLE_MERGED,
        )

        ConversionNotifications.ensureChannel(applicationContext)
        setForeground(ConversionNotifications.foregroundInfo(applicationContext, "Starting…", 0))

        return try {
            val pipeline = ConversionPipeline()
            val result = pipeline.convert(applicationContext, inputUri, { isStopped }, options) { stage, fraction ->
                // Echoed back for the same reason ConversionWorker used to: a fresh ViewModel
                // reconnecting by tag after process death has no other way to restore the
                // SOURCE display, since WorkInfo doesn't expose the original input Data.
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_STAGE to stage,
                        KEY_PROGRESS_FRACTION to fraction,
                        KEY_INPUT_URI to inputUri.toString(),
                        KEY_OUTPUT_URI to outputUri,
                        KEY_OUTPUT_DIR_URI to outputDirUri,
                    ),
                )
                setForeground(ConversionNotifications.foregroundInfo(applicationContext, stage, (fraction * 100).toInt()))
            }

            val cacheFile = File.createTempFile("conversion_intermediate", ".bin", applicationContext.cacheDir)
            IntermediateResultStore.write(cacheFile, IntermediateResult(result.stems, result.bpm, options))

            Result.success(
                workDataOf(
                    KEY_INTERMEDIATE_PATH to cacheFile.absolutePath,
                    KEY_INPUT_URI to inputUri.toString(),
                    KEY_OUTPUT_URI to outputUri,
                    KEY_OUTPUT_DIR_URI to outputDirUri,
                ),
            )
        } catch (e: CancellationException) {
            // Must propagate, not be reported as a Result.failure() -- see ConversionPipeline/
            // DemucsStemSeparator's cleanup, which only runs if this unwinds like a real thrown
            // exception rather than being swallowed into a completed-with-failure work item.
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        }
    }

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_DIR_URI = "output_dir_uri"
        const val KEY_INCLUDED_STEMS = "included_stems"
        const val KEY_NOTE_FRAME_THRESHOLD = "note_frame_threshold"
        const val KEY_SILENT_STEM_RATIO = "silent_stem_ratio"
        const val KEY_OUTPUT_MODE = "output_mode"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        const val KEY_INTERMEDIATE_PATH = "intermediate_path"
        const val KEY_ERROR = "error"

        // Distinct from WriteWorker.WORK_TAG so MainViewModel's reconnect-by-tag logic can tell
        // which phase is running after process death and route to the right UI.
        const val WORK_TAG = "analysis_work"

        fun buildRequest(
            inputUri: Uri,
            outputUri: Uri?,
            outputDirUri: Uri?,
            options: ConversionOptions,
        ): OneTimeWorkRequest {
            val data = Data.Builder()
                .putString(KEY_INPUT_URI, inputUri.toString())
                .putString(KEY_OUTPUT_URI, outputUri?.toString())
                .putString(KEY_OUTPUT_DIR_URI, outputDirUri?.toString())
                .putStringArray(KEY_INCLUDED_STEMS, options.includedStemLabels.toTypedArray())
                .putFloat(KEY_NOTE_FRAME_THRESHOLD, options.noteFrameThreshold)
                .putFloat(KEY_SILENT_STEM_RATIO, options.silentStemRmsRatio)
                .putString(KEY_OUTPUT_MODE, options.outputMode.name)
                .build()
            return OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(data)
                .addTag(WORK_TAG)
                .build()
        }
    }
}
