package com.rm.mp3tomidi.convert.stages

import org.junit.Assert.assertEquals
import org.junit.Test

private data class ExpectedNote(val startFrame: Int, val endFrame: Int, val pitch: Int, val amplitude: Float)

/**
 * Expected values in this file were captured by running the real
 * basic_pitch.note_creation.output_to_notes_polyphonic / get_infered_onsets /
 * model_frames_to_time directly (tools/basic_pitch_export/generate_decoder_fixture.py), not
 * derived by hand -- this is ground truth from upstream, not a restatement of our own port.
 */
class BasicPitchNoteDecoderTest {

    @Test
    fun `decode matches the reference Python implementation`() {
        val nTimes = 40
        val nFreqs = 88
        val frames = Array(nTimes) { FloatArray(nFreqs) }
        val onsets = Array(nTimes) { FloatArray(nFreqs) }

        // Note A: clear onset + sustained frame energy on bin 10, frames 2-14.
        onsets[2][10] = 0.9f
        for (t in 2..14) frames[t][10] = 0.6f

        // Note B: shorter note (still above min_note_len=11) on bin 20, frames 5-18.
        onsets[5][20] = 0.8f
        for (t in 5..18) frames[t][20] = 0.5f

        // Too-short note on bin 30: should be dropped (min_note_len=11).
        onsets[8][30] = 0.95f
        for (t in 8..13) frames[t][30] = 0.7f

        // Sub-threshold predicted onset (0.2 < onset_thresh=0.5) on bin 40, but the frame
        // activation still jumps sharply -- get_infered_onsets should surface it anyway once
        // rescaled against the matrix's max onset (0.95 from bin 30).
        onsets[10][40] = 0.2f
        for (t in 10..24) frames[t][40] = 0.6f

        // melodia_trick=False in the fixture this was captured from -- see the dedicated melodia
        // trick test below for that pass, exercised separately so each test stays focused.
        val notes = BasicPitchNoteDecoder.decode(frames, onsets, melodiaTrick = false).sortedBy { it.startFrame }

        val expected = listOf(
            ExpectedNote(2, 15, 31, 0.59999996f),
            ExpectedNote(5, 19, 41, 0.5f),
            ExpectedNote(10, 25, 61, 0.6f),
        )
        assertEquals(expected.size, notes.size)
        expected.zip(notes).forEach { (want, got) ->
            assertEquals(want.startFrame, got.startFrame)
            assertEquals(want.endFrame, got.endFrame)
            assertEquals(want.pitch, got.pitch)
            assertEquals(want.amplitude.toDouble(), got.amplitude.toDouble(), 1e-5)
        }
    }

    @Test
    fun `melodia trick recovers a sustained note that never had a clean onset, without disturbing onset-based notes`() {
        // Ground truth captured via tools/basic_pitch_export/generate_melodia_fixture.py running
        // the real output_to_notes_polyphonic(..., melodia_trick=True).
        val nTimes = 40
        val nFreqs = 88
        val frames = Array(nTimes) { FloatArray(nFreqs) }
        val onsets = Array(nTimes) { FloatArray(nFreqs) }

        // Same two onset-based notes as the test above, so this also verifies melodia trick
        // correctly leaves their already-claimed regions alone.
        onsets[2][10] = 0.9f
        for (t in 2..14) frames[t][10] = 0.6f
        onsets[5][20] = 0.8f
        for (t in 5..18) frames[t][20] = 0.5f

        // Flat sustained energy from frame 0 on bin 50, no onset anywhere -- constant energy has
        // zero frame-to-frame diff, so get_infered_onsets can't surface it either. Only the
        // melodia trick's leftover-energy sweep can find this.
        for (t in 0..29) frames[t][50] = 0.6f

        val notes = BasicPitchNoteDecoder.decode(frames, onsets).sortedBy { it.startFrame }

        val expected = listOf(
            ExpectedNote(0, 29, 71, 0.6f),
            ExpectedNote(2, 15, 31, 0.59999996f),
            ExpectedNote(5, 19, 41, 0.5f),
        )
        assertEquals(expected.size, notes.size)
        expected.zip(notes).forEach { (want, got) ->
            assertEquals(want.startFrame, got.startFrame)
            assertEquals(want.endFrame, got.endFrame)
            assertEquals(want.pitch, got.pitch)
            assertEquals(want.amplitude.toDouble(), got.amplitude.toDouble(), 1e-5)
        }
    }

    @Test
    fun `modelFramesToTime matches the reference Python implementation`() {
        val times = BasicPitchNoteDecoder.modelFramesToTime(200)

        assertEquals(0.0, times[0], 1e-6)
        assertEquals(0.01160998, times[1], 1e-6)
        // Frame 172 starts a second window; the window-edge correction should produce a small
        // backward jump relative to the uncorrected 172 * 256 / 22050 = 1.997278s.
        assertEquals(1.9736961451247166, times[170], 1e-6)
        assertEquals(1.9853061224489796, times[171], 1e-6)
        assertEquals(1.986590022675737, times[172], 1e-6)
        assertEquals(1.9981999999999998, times[173], 1e-6)
        assertEquals(2.3000594104308387, times[199], 1e-6)
    }
}
