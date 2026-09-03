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
}
