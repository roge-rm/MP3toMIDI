package com.rm.mp3tomidi.convert

/** The user's edits made in [ReviewDialog], applied by [WriteWorker] on top of a cached
 * [IntermediateResult] before writing the final MIDI output(s). */
data class ReviewSelections(
    val excludedStemLabels: Set<String> = emptySet(),
    val gmProgramOverrides: Map<String, Int> = emptyMap(),
    val bpmOverride: Int? = null,
)
