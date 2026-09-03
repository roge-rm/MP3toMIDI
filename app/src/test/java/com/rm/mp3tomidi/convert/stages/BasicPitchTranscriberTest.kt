package com.rm.mp3tomidi.convert.stages

import org.junit.Assert.assertEquals
import org.junit.Test

class BasicPitchTranscriberTest {

    private val transcriber = BasicPitchTranscriber()

    private fun note(start: Int, end: Int, pitch: Int, amplitude: Float) =
        BasicPitchNoteDecoder.RawNote(start, end, pitch, amplitude)

    @Test
    fun `merges same-pitch notes separated by a small gap into one`() {
        val notes = listOf(
            note(0, 10, 60, 0.5f),
            note(12, 20, 60, 0.7f), // gap of 2 frames from the previous note's end -- merges
        )

        val merged = transcriber.mergeRepeatedNotes(notes)

        assertEquals(1, merged.size)
        assertEquals(note(0, 20, 60, 0.7f), merged.single())
    }

    @Test
    fun `does not merge same-pitch notes separated by a large gap`() {
        val notes = listOf(
            note(0, 10, 60, 0.5f),
            note(30, 40, 60, 0.7f), // well beyond the merge threshold -- a real separate note
        )

        val merged = transcriber.mergeRepeatedNotes(notes)

        assertEquals(listOf(note(0, 10, 60, 0.5f), note(30, 40, 60, 0.7f)), merged)
    }

    @Test
    fun `does not merge notes at different pitches regardless of gap`() {
        val notes = listOf(
            note(0, 10, 60, 0.5f),
            note(11, 20, 61, 0.7f), // adjacent in time, but a different pitch -- never merges
        )

        val merged = transcriber.mergeRepeatedNotes(notes)

        assertEquals(listOf(note(0, 10, 60, 0.5f), note(11, 20, 61, 0.7f)), merged)
    }

    @Test
    fun `merges a whole run of fragmented same-pitch notes into one, taking the loudest amplitude`() {
        val notes = listOf(
            note(0, 10, 60, 0.3f),
            note(12, 22, 60, 0.9f),
            note(24, 34, 60, 0.4f),
        )

        val merged = transcriber.mergeRepeatedNotes(notes)

        assertEquals(1, merged.size)
        assertEquals(note(0, 34, 60, 0.9f), merged.single())
    }

    @Test
    fun `leaves unrelated pitches independently intact when merging`() {
        val notes = listOf(
            note(0, 10, 60, 0.5f),
            note(12, 20, 60, 0.6f), // merges with the note above
            note(0, 15, 64, 0.8f), // different pitch, untouched
        )

        val merged = transcriber.mergeRepeatedNotes(notes).sortedBy { it.pitch }

        assertEquals(
            listOf(note(0, 20, 60, 0.6f), note(0, 15, 64, 0.8f)),
            merged,
        )
    }
}
