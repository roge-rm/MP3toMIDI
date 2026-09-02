package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFiltersTest {

    @Test
    fun `rms of known values`() {
        assertEquals(1.0f, AudioFilters.rms(floatArrayOf(1f, -1f, 1f, -1f)), 1e-6f)
        assertEquals(3.5355339f, AudioFilters.rms(floatArrayOf(3f, 4f)), 1e-5f)
        assertEquals(0f, AudioFilters.rms(FloatArray(0)), 1e-6f)
    }

    @Test
    fun `rms respects the given range`() {
        val x = floatArrayOf(0f, 0f, 5f, 5f, 0f)
        assertEquals(5f, AudioFilters.rms(x, 2, 4), 1e-6f)
    }

    @Test
    fun `zeroCrossingRate of known signals`() {
        assertEquals(1.0f, AudioFilters.zeroCrossingRate(floatArrayOf(1f, -1f, 1f, -1f, 1f)), 1e-6f)
        assertEquals(0.0f, AudioFilters.zeroCrossingRate(floatArrayOf(1f, 1f, 1f, 1f)), 1e-6f)
    }

    @Test
    fun `onePoleLowpass passes low frequencies and attenuates high frequencies`() {
        val sampleRate = 44100
        val duration = 0.2f
        val n = (sampleRate * duration).toInt()

        val low = sineWave(50f, sampleRate, n)
        val high = sineWave(8000f, sampleRate, n)

        val lowFiltered = AudioFilters.onePoleLowpass(low, 150f, sampleRate)
        val highFiltered = AudioFilters.onePoleLowpass(high, 150f, sampleRate)

        // Skip the filter's startup transient before measuring steady-state RMS.
        val settle = 2000
        val lowRatio = AudioFilters.rms(lowFiltered, settle, n) / AudioFilters.rms(low, settle, n)
        val highRatio = AudioFilters.rms(highFiltered, settle, n) / AudioFilters.rms(high, settle, n)

        assertTrue("expected the 50Hz tone to pass through mostly unattenuated, got ratio=$lowRatio", lowRatio > 0.9f)
        assertTrue("expected the 8kHz tone to be strongly attenuated, got ratio=$highRatio", highRatio < 0.1f)
    }

    private fun sineWave(frequencyHz: Float, sampleRate: Int, n: Int): FloatArray =
        FloatArray(n) { i -> sin(2.0 * PI * frequencyHz * i / sampleRate).toFloat() }
}
