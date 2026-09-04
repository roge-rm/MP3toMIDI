package com.rm.mp3tomidi.convert.stages

import android.content.Context

/** Detects note pitch/onset/offset within a single separated stem. */
interface NoteTranscriber {
    /** [frameThresh] is Basic Pitch's note-sustain-energy threshold; ignored by transcribers
     * (e.g. drums) that don't decode via [BasicPitchNoteDecoder]. */
    suspend fun transcribe(
        context: Context,
        stem: RawStem,
        bpm: Int,
        frameThresh: Float = BasicPitchNoteDecoder.DEFAULT_FRAME_THRESH,
    ): List<NoteEvent>
}
