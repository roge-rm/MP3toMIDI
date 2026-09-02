package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumOnsetDetectorTest {

    @Test
    fun `finds onsets at known burst positions in an otherwise silent signal`() {
        val sampleRate = 44100
        val totalSamples = sampleRate * 2 // 2 seconds
        val burstStarts = listOf(8_820, 35_280, 66_150) // 0.2s, 0.8s, 1.5s apart -- well over minIntervalMs
        val burstLength = (0.02 * sampleRate).toInt() // 20ms

        val random = Random(0)
        val signal = FloatArray(totalSamples)
        for (start in burstStarts) {
            for (i in 0 until burstLength) {
                signal[start + i] = (random.nextFloat() * 2f - 1f)
            }
        }

        val onsets = DrumOnsetDetector.detect(signal, sampleRate)

        assertEquals(burstStarts.size, onsets.size)
        // A frame-based energy detector can flag an onset slightly before the true start, since
        // a frame overlapping the burst's leading edge already shows a rising-energy jump --
        // bounded by one analysis frame's width (512 samples, the default frameSize).
        val frameSize = 512
        for ((expected, actual) in burstStarts.zip(onsets)) {
            assertTrue(
                "expected onset near $expected, got $actual",
                kotlin.math.abs(expected - actual) <= frameSize,
            )
        }
    }

    @Test
    fun `silence produces no onsets`() {
        val onsets = DrumOnsetDetector.detect(FloatArray(44100), 44100)
        assertEquals(emptyList<Int>(), onsets)
    }

    @Test
    fun `a quiet high-frequency burst is still found among loud low-frequency ones`() {
        // Mirrors the real bug: a single full-band adaptive threshold gets set by loud, frequent
        // low-frequency (kick-like) transients, so a much quieter high-frequency (hi-hat-like)
        // burst never crosses it on its own -- verified on real audio (an electronic song's drums
        // missed 57% of independently-detectable high-band transients this way). The dual-band
        // pass should catch it via its own, separately-calibrated high-band threshold.
        val sampleRate = 44100
        val totalSamples = sampleRate * 2
        val burstLength = (0.02 * sampleRate).toInt()
        val signal = FloatArray(totalSamples)

        val kickStarts = (0 until 8).map { it * (0.2 * sampleRate).toInt() }
        for (start in kickStarts) {
            for (i in 0 until burstLength) {
                signal[start + i] = sin(2.0 * PI * 60.0 * i / sampleRate).toFloat()
            }
        }

        // Sits roughly midway between the 3rd and 4th kicks -- well over minIntervalMs (60ms)
        // from either -- at 1/20th the kicks' amplitude.
        val quietBurstStart = kickStarts[2] + (0.1 * sampleRate).toInt()
        for (i in 0 until burstLength) {
            signal[quietBurstStart + i] += 0.05f * sin(2.0 * PI * 9000.0 * i / sampleRate).toFloat()
        }

        val onsets = DrumOnsetDetector.detect(signal, sampleRate)

        val frameSize = 512
        assertTrue(
            "expected an onset near the quiet high-frequency burst at $quietBurstStart, got $onsets",
            onsets.any { kotlin.math.abs(it - quietBurstStart) <= frameSize },
        )
    }
}
