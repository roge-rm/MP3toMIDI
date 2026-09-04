package com.rm.mp3tomidi.convert

import com.rm.mp3tomidi.convert.stages.DemucsStemSeparator

enum class OutputMode {
    /** Today's default: every included stem merged into one format-0 track. */
    SINGLE_MERGED,

    /** One format-1 file, each included stem on its own track. */
    SINGLE_MULTI_TRACK,

    /** One independent format-0 file per included stem, written into a chosen output folder. */
    SEPARATE_FILES,
}

/**
 * User-chosen knobs threaded through [ConversionPipeline] and, downstream, [WriteWorker]/
 * [com.rm.mp3tomidi.midi.MidiFileWriter]. Only [noteFrameThreshold] is exposed as a "sensitivity"
 * slider, not Basic Pitch's `onsetThresh` -- its effect on the result is much harder for a
 * non-technical user to predict, so it stays fixed at its calibrated default.
 */
data class ConversionOptions(
    val includedStemLabels: Set<String> = DemucsStemSeparator.SOURCES.toSet(),
    val noteFrameThreshold: Float = DEFAULT_NOTE_FRAME_THRESHOLD,
    val silentStemRmsRatio: Float = DEFAULT_SILENT_STEM_RMS_RATIO,
    val outputMode: OutputMode = OutputMode.SINGLE_MERGED,
) {
    companion object {
        // Matches BasicPitchNoteDecoder's own default frameThresh.
        const val DEFAULT_NOTE_FRAME_THRESHOLD = 0.3f

        // Matches ConversionPipeline's previously-hardcoded MIN_STEM_RMS_RATIO.
        const val DEFAULT_SILENT_STEM_RMS_RATIO = 0.04f
    }
}
