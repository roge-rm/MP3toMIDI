package com.rm.mp3tomidi.convert.stages

import kotlin.math.roundToInt
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
    fun `caps a merge chain at 2 originals instead of collapsing a long run into one note`() {
        // A real fast repeated-pitch passage (tremolo, arpeggio) produces this exact same
        // near-zero-gap signature as genuine single-note fragmentation -- see this class's doc.
        // Without a cap, this whole run collapses into one implausible note.
        val notes = listOf(
            note(0, 10, 60, 0.3f),
            note(12, 22, 60, 0.9f),
            note(24, 34, 60, 0.4f),
            note(36, 46, 60, 0.6f),
        )

        val merged = transcriber.mergeRepeatedNotes(notes).sortedBy { it.startFrame }

        assertEquals(
            listOf(note(0, 22, 60, 0.9f), note(24, 46, 60, 0.6f)),
            merged,
        )
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

    @Test
    fun `velocityFor scales a note's velocity to its loudness relative to the stem peak`() {
        // A note at 50% of the stem's peak amplitude -- constant-amplitude signal, so RMS equals
        // that amplitude exactly.
        val audio = FloatArray(1000) { 0.5f }

        val velocity = transcriber.velocityFor(audio, 0, 1000, peakAmplitude = 1f)

        assertEquals((127 * 0.5f).roundToInt(), velocity)
    }

    @Test
    fun `velocityFor floors a very quiet note instead of letting it go silent`() {
        val audio = FloatArray(1000) { 0.001f }

        val velocity = transcriber.velocityFor(audio, 0, 1000, peakAmplitude = 1f)

        assertEquals(40, velocity) // MIN_VELOCITY
    }

    @Test
    fun `velocityFor caps at 127 even if the note's span happens to exceed the reported peak`() {
        val audio = FloatArray(1000) { 1f }

        val velocity = transcriber.velocityFor(audio, 0, 1000, peakAmplitude = 0.5f)

        assertEquals(127, velocity)
    }
}
