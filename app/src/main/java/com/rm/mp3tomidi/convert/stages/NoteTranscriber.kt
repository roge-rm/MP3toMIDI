package com.rm.mp3tomidi.convert.stages

import com.rm.mp3tomidi.midi.MidiConstants

/** Detects note pitch/onset/offset within a single separated stem. */
interface NoteTranscriber {
    suspend fun transcribe(stem: RawStem): List<NoteEvent>
}

/**
 * Placeholder used until a real transcription model (e.g. Basic Pitch, or a pYIN/CREPE-style
 * pitch tracker for monophonic stems) is wired in. Emits one sustained middle-C note spanning
 * the stem's full duration so the MIDI file's length matches the source audio.
 */
class PlaceholderNoteTranscriber : NoteTranscriber {
    override suspend fun transcribe(stem: RawStem): List<NoteEvent> {
        val durationTicks = usToTicks(stem.durationUs)
        return listOf(NoteEvent(startTick = 0, endTick = durationTicks, pitch = 60, velocity = 100))
    }

    private fun usToTicks(durationUs: Long): Long {
        val quarterNoteUs = 60_000_000L / MidiConstants.DEFAULT_BPM
        return durationUs * MidiConstants.TICKS_PER_QUARTER_NOTE / quarterNoteUs
    }
}
