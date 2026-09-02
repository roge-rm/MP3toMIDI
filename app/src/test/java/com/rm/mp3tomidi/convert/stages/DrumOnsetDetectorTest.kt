package com.rm.mp3tomidi.convert.stages

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
}
