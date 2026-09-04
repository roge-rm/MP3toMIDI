package com.rm.mp3tomidi.convert

import com.rm.mp3tomidi.convert.stages.Stem

/** [AnalysisWorker]'s output, cached to disk (see [IntermediateResultStore]) for [ReviewDialog]
 * to display and [WriteWorker] to turn into the final MIDI output(s). */
data class IntermediateResult(
    val stems: List<Stem>,
    val bpm: Int,
    val options: ConversionOptions,
)
