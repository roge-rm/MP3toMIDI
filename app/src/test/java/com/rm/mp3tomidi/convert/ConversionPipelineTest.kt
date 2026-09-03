package com.rm.mp3tomidi.convert

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionPipelineTest {

    @Test
    fun `rmsOf computes root-mean-square of a PCM buffer`() {
        val pcm = floatArrayOf(1f, -1f, 1f, -1f)

        assertEquals(1f, ConversionPipeline.rmsOf(pcm), 1e-6f)
    }

    @Test
    fun `rmsOf returns zero for an empty buffer`() {
        assertEquals(0f, ConversionPipeline.rmsOf(FloatArray(0)), 1e-6f)
    }

    @Test
    fun `silentPitchedLabels flags a stem far quieter than the loudest as noise floor`() {
        // Ratios drawn from real measurements: confidently-bogus stems sat at 0.3-2.5% of the
        // loudest stem, every real stem observed sat at 5.8% or higher.
        val rmsByLabel = mapOf(
            "bass" to 0.13f,
            "vocals" to 0.11f,
            "guitar" to 0.043f,
            "piano" to 0.0012f, // ~0.9% of bass -- matches the real OK Go measurement
        )

        val silent = ConversionPipeline.silentPitchedLabels(rmsByLabel)

        assertEquals(setOf("piano"), silent)
    }

    @Test
    fun `silentPitchedLabels keeps a stem within the real-instrument range`() {
        val rmsByLabel = mapOf(
            "bass" to 0.144f,
            "piano" to 0.035f, // ~24% of bass -- matches the real Vision One measurement
        )

        val silent = ConversionPipeline.silentPitchedLabels(rmsByLabel)

        assertEquals(emptySet<String>(), silent)
    }

    @Test
    fun `silentPitchedLabels treats all-silent stems as nothing to drop`() {
        val silent = ConversionPipeline.silentPitchedLabels(mapOf("bass" to 0f, "piano" to 0f))

        assertEquals(emptySet<String>(), silent)
    }

    @Test
    fun `silentPitchedLabels handles an empty map`() {
        val silent = ConversionPipeline.silentPitchedLabels(emptyMap())

        assertEquals(emptySet<String>(), silent)
    }

    @Test
    fun `velocityScales gives the loudest stem a scale of 1`() {
        val scales = ConversionPipeline.velocityScales(mapOf("bass" to 0.13f, "vocals" to 0.11f))

        assertEquals(1f, scales.getValue("bass"), 1e-6f)
    }

    @Test
    fun `velocityScales scales a quieter stem down proportionally to its real loudness`() {
        // vocals at 50% of bass's RMS -- expect roughly half the velocity scale, well above the floor
        val scales = ConversionPipeline.velocityScales(mapOf("bass" to 0.10f, "vocals" to 0.05f))

        assertEquals(0.5f, scales.getValue("vocals"), 1e-6f)
    }

    @Test
    fun `velocityScales floors a much quieter stem instead of letting it go silent`() {
        // ~8% of the loudest stem -- below MIN_BALANCE_SCALE, should be floored rather than ~0.08
        val scales = ConversionPipeline.velocityScales(mapOf("bass" to 0.10f, "piano" to 0.008f), minScale = 0.35f)

        assertEquals(0.35f, scales.getValue("piano"), 1e-6f)
    }

    @Test
    fun `velocityScales returns empty for an all-silent map`() {
        assertEquals(emptyMap<String, Float>(), ConversionPipeline.velocityScales(mapOf("bass" to 0f)))
    }
}
