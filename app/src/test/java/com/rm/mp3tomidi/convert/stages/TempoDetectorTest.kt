package com.rm.mp3tomidi.convert.stages

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoDetectorTest {

    private val sampleRate = 44100

    @Test
    fun `detects the tempo of a synthetic click track`() {
        for (bpm in listOf(90, 120, 140, 174)) {
            val detected = TempoDetector.detectBpm(clickTrack(bpm, seconds = 20.0), sampleRate)
            assertTrue("expected ~$bpm BPM, got $detected", abs(detected - bpm) <= 2)
        }
    }

    @Test
    fun `is not fooled by ghost clicks at twice the tempo`() {
        // A click track with a quieter "ghost" click exactly halfway between each main click --
        // the dominant periodicity should still be the main click's tempo, not double it.
        val bpm = 120
        val random = Random(bpm)
        val n = (20.0 * sampleRate).toInt()
        val signal = FloatArray(n)
        val periodSamples = (60.0 / bpm * sampleRate).toInt()
        val clickLength = (0.01 * sampleRate).toInt()
        var pos = 0
        while (pos + periodSamples < n) {
            addClick(signal, pos, clickLength, random, amplitude = 1f)
            addClick(signal, pos + periodSamples / 2, clickLength, random, amplitude = 0.3f)
            pos += periodSamples
        }

        val detected = TempoDetector.detectBpm(signal, sampleRate)
        assertTrue("expected ~$bpm BPM, got $detected", abs(detected - bpm) <= 2)
    }

    @Test
    fun `short audio falls back to the default`() {
        assertEquals(TempoDetector.DEFAULT_BPM, TempoDetector.detectBpm(FloatArray(100), sampleRate))
    }

    @Test
    fun `silence falls back to the default`() {
        assertEquals(TempoDetector.DEFAULT_BPM, TempoDetector.detectBpm(FloatArray(sampleRate * 10), sampleRate))
    }

    private fun clickTrack(bpm: Int, seconds: Double): FloatArray {
        val random = Random(bpm)
        val n = (seconds * sampleRate).toInt()
        val signal = FloatArray(n)
        val periodSamples = (60.0 / bpm * sampleRate).toInt()
        val clickLength = (0.01 * sampleRate).toInt()
        var pos = 0
        while (pos + clickLength < n) {
            addClick(signal, pos, clickLength, random, amplitude = 1f)
            pos += periodSamples
        }
        return signal
    }

    private fun addClick(signal: FloatArray, start: Int, length: Int, random: Random, amplitude: Float) {
        for (i in 0 until length) {
            if (start + i >= signal.size) break
            val decay = exp(-i / (length / 4.0)).toFloat()
            signal[start + i] = (random.nextFloat() * 2f - 1f) * decay * amplitude
        }
    }
}
