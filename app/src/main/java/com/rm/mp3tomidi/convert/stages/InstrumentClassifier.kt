package com.rm.mp3tomidi.convert.stages

import android.content.Context

/** Identifies a separated stem's timbre and maps it to the closest General MIDI program. */
interface InstrumentClassifier {
    suspend fun classify(context: Context, stem: RawStem, notes: List<NoteEvent>, bpm: Int): Stem
}
