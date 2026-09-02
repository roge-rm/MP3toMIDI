package com.rm.mp3tomidi.convert.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteEnvelopeClassifierTest {

    private val bpm = 120
    // At 120 BPM, TICKS_PER_QUARTER_NOTE=480 -> 960 ticks/second.
    private val ticksPerSecond = 960.0

    @Test
    fun `overlapping sustained chords classify as a pad`() {
        // Four notes, each 2 seconds long, each starting before the previous one ends (legato).
        val notes = (0 until 4).map { i ->
            val start = (i * 1.5 * ticksPerSecond).toLong()
            val end = start + (2.0 * ticksPerSecond).toLong()
            NoteEvent(startTick = start, endTick = end, pitch = 60, velocity = 90)
        }

        assertEquals(NoteEnvelopeClassifier.Role.SUSTAINED_PAD, NoteEnvelopeClassifier.classify(notes, bpm))
    }

    @Test
    fun `short staccato notes with gaps classify as a rhythmic pluck`() {
        // 8 short (0.1s) notes, each separated by a 0.15s gap -- a 16th-note-ish arpeggio.
        val notes = (0 until 8).map { i ->
            val start = (i * 0.25 * ticksPerSecond).toLong()
            val end = start + (0.1 * ticksPerSecond).toLong()
            NoteEvent(startTick = start, endTick = end, pitch = 72, velocity = 100)
        }

        assertEquals(NoteEnvelopeClassifier.Role.RHYTHMIC_PLUCK, NoteEnvelopeClassifier.classify(notes, bpm))
    }

    @Test
    fun `clearly detached moderate-length notes classify as a lead`() {
        // Notes of moderate length (0.4s) separated by clearly audible gaps (0.15s) -- distinctly
        // articulated, neither a legato-connected pad nor a fast pluck/arpeggio.
        val notes = (0 until 6).map { i ->
            val start = (i * 0.55 * ticksPerSecond).toLong()
            val end = start + (0.4 * ticksPerSecond).toLong()
            NoteEvent(startTick = start, endTick = end, pitch = 67 + (i % 3), velocity = 95)
        }

        assertEquals(NoteEnvelopeClassifier.Role.LEAD, NoteEnvelopeClassifier.classify(notes, bpm))
    }

    @Test
    fun `low register classifies as bass regardless of note shape`() {
        // Long, legato notes (would otherwise look like a pad) but in a clearly low register.
        val notes = (0 until 4).map { i ->
            val start = (i * 1.5 * ticksPerSecond).toLong()
            val end = start + (2.0 * ticksPerSecond).toLong()
            NoteEvent(startTick = start, endTick = end, pitch = 36, velocity = 100)
        }

        assertEquals(NoteEnvelopeClassifier.Role.BASS, NoteEnvelopeClassifier.classify(notes, bpm))
    }

    @Test
    fun `a majority-low-register bass line still classifies as bass despite a harmonic-overtone tail`() {
        // Mirrors a real bass stem captured from an electronic song, where Basic Pitch's
        // polyphonic transcription picked up harmonic overtones as extra higher-pitched notes
        // (1780 transcribed notes averaged out to MIDI 59.7 -- nowhere near bass range by mean).
        // 20 genuine low bass notes plus 15 higher-pitched "overtone" notes: the mean (~56.6) is
        // above BASS_PITCH_MAX, but the note-count-majority median (36) is not.
        val bassNotes = (0 until 20).map { i ->
            val start = (i * 0.3 * ticksPerSecond).toLong()
            NoteEvent(startTick = start, endTick = start + (0.25 * ticksPerSecond).toLong(), pitch = 36, velocity = 100)
        }
        val overtoneNotes = (0 until 15).map { i ->
            val start = (i * 0.4 * ticksPerSecond).toLong()
            NoteEvent(startTick = start, endTick = start + (0.05 * ticksPerSecond).toLong(), pitch = 84, velocity = 40)
        }

        assertEquals(NoteEnvelopeClassifier.Role.BASS, NoteEnvelopeClassifier.classify(bassNotes + overtoneNotes, bpm))
    }

    @Test
    fun `too few notes returns null rather than guessing`() {
        val notes = listOf(
            NoteEvent(startTick = 0, endTick = 480, pitch = 60, velocity = 90),
            NoteEvent(startTick = 480, endTick = 960, pitch = 62, velocity = 90),
        )

        assertNull(NoteEnvelopeClassifier.classify(notes, bpm))
    }
}
