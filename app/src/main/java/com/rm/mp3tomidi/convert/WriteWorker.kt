package com.rm.mp3tomidi.convert

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rm.mp3tomidi.convert.stages.Stem
import com.rm.mp3tomidi.midi.MidiFileWriter
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Second half of the split conversion pipeline: loads [AnalysisWorker]'s cached
 * [IntermediateResult], applies the user's [ReviewDialog] choices, and writes the final MIDI
 * output(s). Short-running compared to [AnalysisWorker] (no ML inference), so cancellation is
 * just plain [isStopped] checks between per-stem writes rather than the more aggressive polling
 * discipline separation/transcription need.
 */
class WriteWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val intermediatePath = inputData.getString(KEY_INTERMEDIATE_PATH) ?: return Result.failure()
        val outputMode = inputData.getString(KEY_OUTPUT_MODE)?.let(OutputMode::valueOf) ?: OutputMode.SINGLE_MERGED
        val outputUri = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
        val outputDirUri = inputData.getString(KEY_OUTPUT_DIR_URI)?.let(Uri::parse)
        val excludedLabels = inputData.getStringArray(KEY_EXCLUDED_STEMS)?.toSet() ?: emptySet()
        val overrideLabels = inputData.getStringArray(KEY_OVERRIDE_LABELS) ?: emptyArray()
        val overridePrograms = inputData.getIntArray(KEY_OVERRIDE_PROGRAMS) ?: IntArray(0)
        val gmOverrides = overrideLabels.zip(overridePrograms.toList()).toMap()
        val bpmOverride = inputData.getInt(KEY_BPM_OVERRIDE, NO_BPM_OVERRIDE).takeIf { it != NO_BPM_OVERRIDE }

        ConversionNotifications.ensureChannel(applicationContext)
        setForeground(ConversionNotifications.foregroundInfo(applicationContext, "Writing MIDI file", 0))

        val cacheFile = File(intermediatePath)
        return try {
            val intermediate = IntermediateResultStore.read(cacheFile)
            val bpm = bpmOverride ?: intermediate.bpm
            val stems = intermediate.stems
                .filter { it.label !in excludedLabels }
                .map { stem -> gmOverrides[stem.label]?.let { stem.copy(gmProgram = it) } ?: stem }

            when (outputMode) {
                OutputMode.SINGLE_MERGED -> writeSingleFile(outputUri, stems, bpm, format = 0)
                OutputMode.SINGLE_MULTI_TRACK -> writeSingleFile(outputUri, stems, bpm, format = 1)
                OutputMode.SEPARATE_FILES -> writeSeparateFiles(outputDirUri, stems, bpm)
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        } finally {
            cacheFile.delete()
        }
    }

    private fun writeSingleFile(outputUri: Uri?, stems: List<Stem>, bpm: Int, format: Int) {
        requireNotNull(outputUri) { "Missing output file for single-file output mode" }
        val midiBytes = MidiFileWriter.write(stems, bpm = bpm, format = format)
        val written = applicationContext.contentResolver.openOutputStream(outputUri)?.use {
            it.write(midiBytes)
            true
        } ?: false
        if (!written) error("Couldn't open output stream for $outputUri")
    }

    private suspend fun writeSeparateFiles(outputDirUri: Uri?, stems: List<Stem>, bpm: Int) {
        requireNotNull(outputDirUri) { "Missing output folder for separate-files output mode" }
        val dir = requireNotNull(DocumentFile.fromTreeUri(applicationContext, outputDirUri)) {
            "Couldn't resolve output folder $outputDirUri"
        }
        stems.forEachIndexed { index, stem ->
            if (isStopped) return@forEachIndexed
            val midiBytes = MidiFileWriter.write(listOf(stem), bpm = bpm, format = 0)
            // "application/octet-stream", not "audio/midi" -- same MimeTypeMap-appends-a-bogus-
            // extension bug MainScreen's single-output-file picker already works around (see its
            // createOutputLauncher doc): a generic type with no canonical extension can't trigger it.
            val file = requireNotNull(dir.createFile("application/octet-stream", "${stem.label}.mid")) {
                "Couldn't create ${stem.label}.mid in $outputDirUri"
            }
            applicationContext.contentResolver.openOutputStream(file.uri)?.use { it.write(midiBytes) }
            setProgress(workDataOf(KEY_PROGRESS_FRACTION to (index + 1).toFloat() / stems.size))
        }
    }

    companion object {
        const val KEY_INTERMEDIATE_PATH = "intermediate_path"
        const val KEY_OUTPUT_MODE = "output_mode"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_DIR_URI = "output_dir_uri"
        const val KEY_EXCLUDED_STEMS = "excluded_stems"
        const val KEY_OVERRIDE_LABELS = "override_labels"
        const val KEY_OVERRIDE_PROGRAMS = "override_programs"
        const val KEY_BPM_OVERRIDE = "bpm_override"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        const val KEY_ERROR = "error"
        private const val NO_BPM_OVERRIDE = -1

        const val WORK_TAG = "write_work"

        fun buildRequest(
            intermediatePath: String,
            outputMode: OutputMode,
            outputUri: Uri?,
            outputDirUri: Uri?,
            selections: ReviewSelections,
        ): OneTimeWorkRequest {
            val data = Data.Builder()
                .putString(KEY_INTERMEDIATE_PATH, intermediatePath)
                .putString(KEY_OUTPUT_MODE, outputMode.name)
                .putString(KEY_OUTPUT_URI, outputUri?.toString())
                .putString(KEY_OUTPUT_DIR_URI, outputDirUri?.toString())
                .putStringArray(KEY_EXCLUDED_STEMS, selections.excludedStemLabels.toTypedArray())
                .putStringArray(KEY_OVERRIDE_LABELS, selections.gmProgramOverrides.keys.toTypedArray())
                .putIntArray(KEY_OVERRIDE_PROGRAMS, selections.gmProgramOverrides.values.toIntArray())
                .putInt(KEY_BPM_OVERRIDE, selections.bpmOverride ?: NO_BPM_OVERRIDE)
                .build()
            return OneTimeWorkRequestBuilder<WriteWorker>()
                .setInputData(data)
                .addTag(WORK_TAG)
                .build()
        }
    }
}
