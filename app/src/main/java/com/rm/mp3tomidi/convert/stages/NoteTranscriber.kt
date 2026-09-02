package com.rm.mp3tomidi.convert.stages

import android.content.Context
import com.rm.mp3tomidi.midi.MidiConstants

/** Detects note pitch/onset/offset within a single separated stem. */
interface NoteTranscriber {
    suspend fun transcribe(context: Context, stem: RawStem): List<NoteEvent>
}

/**
 * Placeholder used where a real transcriber isn't appropriate -- currently drums, since Basic
 * Pitch assumes pitched input. Emits one sustained middle-C note spanning the stem's full
 * duration so the MIDI file's length still matches the source audio. Real drum transcription
 * (onset detection + GM percussion classification) is a known follow-up.
 */
class PlaceholderNoteTranscriber : NoteTranscriber {
    override suspend fun transcribe(context: Context, stem: RawStem): List<NoteEvent> {
        val durationTicks = usToTicks(stem.durationUs)
        return listOf(NoteEvent(startTick = 0, endTick = durationTicks, pitch = 60, velocity = 100))
    }

    private fun usToTicks(durationUs: Long): Long {
        val quarterNoteUs = 60_000_000L / MidiConstants.DEFAULT_BPM
        return durationUs * MidiConstants.TICKS_PER_QUARTER_NOTE / quarterNoteUs
    }
}
