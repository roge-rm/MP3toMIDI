package com.rm.mp3tomidi.convert.stages

import android.content.Context

/** Detects note pitch/onset/offset within a single separated stem. */
interface NoteTranscriber {
    suspend fun transcribe(context: Context, stem: RawStem, bpm: Int): List<NoteEvent>
}
