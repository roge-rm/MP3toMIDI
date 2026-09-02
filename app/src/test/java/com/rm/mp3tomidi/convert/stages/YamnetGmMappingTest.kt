package com.rm.mp3tomidi.convert.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YamnetGmMappingTest {

    private val threshold = 0.05f

    @Test
    fun `picks the highest-scoring mapped class above threshold`() {
        val scores = FloatArray(521)
        scores[148] = 0.10f // Piano
        scores[150] = 0.30f // Organ -- should win, higher score
        scores[24] = 0.20f // Singing

        val match = YamnetGmMapping.pickBestMatch(scores, threshold)

        assertEquals(16, match?.gmProgram) // Drawbar Organ
        assertEquals(false, match?.isDrumKit)
    }

    @Test
    fun `returns null when nothing clears the threshold`() {
        val scores = FloatArray(521)
        scores[148] = 0.02f // Piano, below threshold
        scores[132] = 0.90f // "Music" -- high score but not a mapped instrument class

        assertNull(YamnetGmMapping.pickBestMatch(scores, threshold))
    }

    @Test
    fun `unmapped generic classes never win even at high confidence`() {
        val scores = FloatArray(521)
        scores[132] = 0.99f // Music (unmapped, generic)
        scores[494] = 0.99f // Silence (unmapped, generic)
        scores[133] = 0.99f // Musical instrument (unmapped, generic)
        scores[137] = 0.08f // Bass guitar, the only real mapped instrument present

        val match = YamnetGmMapping.pickBestMatch(scores, threshold)

        assertEquals(33, match?.gmProgram) // Electric Bass (finger)
    }

    @Test
    fun `percussion classes without a melodic GM equivalent map to the drum kit`() {
        val scores = FloatArray(521)
        scores[167] = 0.5f // Hi-hat

        val match = YamnetGmMapping.pickBestMatch(scores, threshold)

        assertEquals(true, match?.isDrumKit)
    }

    @Test
    fun `pitched percussion maps to its own melodic program, not the drum kit`() {
        val scores = FloatArray(521)
        scores[164] = 0.5f // Timpani

        val match = YamnetGmMapping.pickBestMatch(scores, threshold)

        assertEquals(47, match?.gmProgram) // Timpani
        assertEquals(false, match?.isDrumKit)
    }

    @Test
    fun `a score exactly at the threshold clears it`() {
        val scores = FloatArray(521)
        scores[137] = threshold // Bass guitar, exactly at the boundary

        val match = YamnetGmMapping.pickBestMatch(scores, threshold)

        assertEquals(33, match?.gmProgram)
    }

    @Test
    fun `the generic synthesizer class is flagged for envelope-based refinement`() {
        val scores = FloatArray(521)
        scores[153] = 0.5f // Synthesizer

        val match = YamnetGmMapping.pickBestMatch(scores, threshold)

        assertEquals(true, match?.isGenericSynth)
    }

    @Test
    fun `a specific instrument class is not flagged as generic synth`() {
        val scores = FloatArray(521)
        scores[137] = 0.5f // Bass guitar

        val match = YamnetGmMapping.pickBestMatch(scores, threshold)

        assertEquals(false, match?.isGenericSynth)
    }
}
