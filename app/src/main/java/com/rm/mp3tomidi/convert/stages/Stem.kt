package com.rm.mp3tomidi.convert.stages

/** A single transcribed note, in absolute MIDI ticks. */
data class NoteEvent(
    val startTick: Long,
    val endTick: Long,
    val pitch: Int,
    val velocity: Int,
)

/** One separated audio source before transcription/classification. */
data class RawStem(
    val label: String,
    val durationUs: Long,
    /** Interleaved PCM, [sampleRate]/[channelCount] as produced by the separator. */
    val interleavedPcm: FloatArray,
    val sampleRate: Int,
    val channelCount: Int,
)

/** A fully processed stem: identified instrument + its transcribed notes, ready for MIDI assembly. */
data class Stem(
    val label: String,
    val gmProgram: Int,
    val isDrumKit: Boolean,
    val notes: List<NoteEvent>,
)
