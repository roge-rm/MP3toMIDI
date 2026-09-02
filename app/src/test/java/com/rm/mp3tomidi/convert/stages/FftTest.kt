package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FftTest {

    @Test
    fun `impulse transforms to a flat spectrum`() {
        val re = floatArrayOf(1f, 0f, 0f, 0f)
        val im = FloatArray(4)
        Fft.transform(re, im)
        for (i in 0 until 4) {
            assertEquals(1f, re[i], 1e-6f)
            assertEquals(0f, im[i], 1e-6f)
        }
    }

    @Test
    fun `DC signal transforms to energy only in bin zero`() {
        val n = 8
        val re = FloatArray(n) { 1f }
        val im = FloatArray(n)
        Fft.transform(re, im)
        assertEquals(n.toFloat(), re[0], 1e-4f)
        for (i in 1 until n) {
            assertTrue("bin $i should be ~0, was ${re[i]},${im[i]}", magnitude(re[i], im[i]) < 1e-4f)
        }
    }

    @Test
    fun `pure sine wave peaks at the matching frequency bin`() {
        val n = 64
        val binIndex = 5
        val re = FloatArray(n) { i -> sin(2.0 * PI * binIndex * i / n).toFloat() }
        val im = FloatArray(n)
        Fft.transform(re, im)

        val magnitudes = FloatArray(n / 2 + 1) { k -> magnitude(re[k], im[k]) }
        var peakBin = 0
        for (k in magnitudes.indices) if (magnitudes[k] > magnitudes[peakBin]) peakBin = k

        assertEquals(binIndex, peakBin)
    }

    @Test
    fun `matches direct DFT on a small random-ish signal`() {
        val n = 16
        val x = FloatArray(n) { i -> cos(i * 0.7) .toFloat() + sin(i * 1.3).toFloat() * 0.5f }
        val re = x.copyOf()
        val im = FloatArray(n)
        Fft.transform(re, im)

        for (k in 0 until n) {
            var expectedRe = 0.0
            var expectedIm = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                expectedRe += x[t] * cos(angle)
                expectedIm += x[t] * sin(angle)
            }
            assertTrue(
                "bin $k: expected ($expectedRe,$expectedIm) got (${re[k]},${im[k]})",
                abs(expectedRe - re[k]) < 1e-3 && abs(expectedIm - im[k]) < 1e-3,
            )
        }
    }

    private fun magnitude(re: Float, im: Float): Float = kotlin.math.sqrt(re * re + im * im)
}
