package com.rm.mp3tomidi.convert.stages

import java.io.File

/** A single transcribed note, in absolute MIDI ticks. */
data class NoteEvent(
    val startTick: Long,
    val endTick: Long,
    val pitch: Int,
    val velocity: Int,
)

/**
 * One separated audio source before transcription/classification.
 *
 * The audio lives on disk ([pcmFile]: raw interleaved 32-bit float PCM, little-endian, at
 * [sampleRate]/[channelCount]) rather than in memory -- a few minutes of song times 6 stems
 * held as in-memory FloatArrays at once is enough to OOM on a typical Android heap. Read it
 * incrementally (e.g. windowed, as a real transcriber would) rather than loading it whole.
 */
data class RawStem(
    val label: String,
    val durationUs: Long,
    val pcmFile: File,
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
