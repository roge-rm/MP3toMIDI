package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DrumHitClassifier is a hand-tuned heuristic with no ground truth to verify against (unlike
 * DemucsStemSeparator/BasicPitchTranscriber, which are checked against a real reference
 * implementation). These tests instead confirm it does the sensible thing on synthetic signals
 * built to be clearly representative of each category:
 *  - kick: a low-frequency tone, since kick drums are dominated by fundamental energy well
 *    under 150Hz.
 *  - snare: broadband noise band-passed to 150Hz-1.5kHz, representing a real snare's energy
 *    (shell + wires) -- concentrated in the middle, unlike a kick (dominated by sub-150Hz
 *    fundamental) or a cymbal (dominated by shimmer above a few kHz). A single one-pole lowpass
 *    is too leaky for this (-6dB/octave leaves meaningful energy well above the cutoff, which is
 *    exactly the bug that motivated switching the classifier itself to FFT band power -- see its
 *    doc comment), so the test cascades four stages for a cleaner band limit.
 *  - hi-hat/crash: broadband noise high-pass filtered at 8kHz, representing the shimmer of a
 *    cymbal, distinguished from each other only by how long the burst lasts -- and for "how
 *    long," an exponential decay envelope rather than a hard cutoff to exact zero, since zero
 *    crossing rate only cares about sign, not magnitude: a quiet-but-still-crossing tail behaves
 *    correctly, but a hard-zero tail dilutes the whole window's crossing count and (more
 *    importantly) isn't how a real decaying hi-hat actually sounds anyway.
 *
 * (An earlier version of the hi-hat signal tried building "high-frequency" noise by multiplying
 * white noise by alternating +-1 per sample. That doesn't work: white noise's spectrum is
 * already flat, so shifting it by Nyquist via that modulation leaves it exactly as flat as
 * before. Actually filtering it is the only way to concentrate its energy anywhere.)
 */
class DrumHitClassifierTest {

    private val sampleRate = 44100
    private val random = Random(0)

    @Test
    fun `low tone classifies as kick`() {
        val n = (0.08 * sampleRate).toInt()
        val signal = FloatArray(n) { i -> sin(2.0 * PI * 60.0 * i / sampleRate).toFloat() }

        assertEquals(DrumHitClassifier.Voice.KICK, DrumHitClassifier.classify(signal, 0, sampleRate))
    }

    @Test
    fun `band-passed broadband noise classifies as snare`() {
        val n = (0.08 * sampleRate).toInt()
        val noise = FloatArray(n) { random.nextFloat() * 2f - 1f }
        // A single one-pole stage only rolls off at -6dB/octave, which leaves enough energy
        // above 6kHz to look like a cymbal to the classifier's FFT-based band split -- cascading
        // four stages (-24dB/octave) is closer to a real snare's more contained spectrum.
        val signal = highpassed(cascadedLowpass(noise, 1500f), 150f)

        assertEquals(DrumHitClassifier.Voice.SNARE, DrumHitClassifier.classify(signal, 0, sampleRate))
    }

    private fun cascadedLowpass(x: FloatArray, cutoffHz: Float, stages: Int = 4): FloatArray {
        var y = x
        repeat(stages) { y = AudioFilters.onePoleLowpass(y, cutoffHz, sampleRate) }
        return y
    }

    @Test
    fun `fast-decaying high-passed burst classifies as closed hi-hat`() {
        val n = (0.08 * sampleRate).toInt()
        val decaySamples = 0.005 * sampleRate // 5ms time constant
        val noise = FloatArray(n) { i -> (random.nextFloat() * 2f - 1f) * exp(-i / decaySamples).toFloat() }
        val signal = highpassed(noise, 8000f)

        assertEquals(DrumHitClassifier.Voice.CLOSED_HI_HAT, DrumHitClassifier.classify(signal, 0, sampleRate))
    }

    @Test
    fun `sustained high-passed burst classifies as crash or open hi-hat`() {
        val n = (0.08 * sampleRate).toInt()
        val noise = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val signal = highpassed(noise, 8000f)

        assertEquals(DrumHitClassifier.Voice.CRASH_OR_OPEN_HI_HAT, DrumHitClassifier.classify(signal, 0, sampleRate))
    }

    private fun highpassed(x: FloatArray, cutoffHz: Float): FloatArray {
        val low = AudioFilters.onePoleLowpass(x, cutoffHz, sampleRate)
        return FloatArray(x.size) { x[it] - low[it] }
    }

    @Test
    fun `silence classifies as snare (the catch-all) rather than crashing`() {
        assertEquals(DrumHitClassifier.Voice.SNARE, DrumHitClassifier.classify(FloatArray(4000), 0, sampleRate))
    }
}
