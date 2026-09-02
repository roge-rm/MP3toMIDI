package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Heuristic drum-hit classifier: a handful of GM percussion voices distinguished by FFT band-power
 * ratios and decay shape on a short window after each onset. Unlike DemucsStemSeparator/
 * BasicPitchTranscriber, there's no upstream reference implementation or training data behind
 * this -- it's a hand-tuned heuristic meant to give a plausible-sounding drum part, not an
 * accurate transcription. Real per-hit drum classification is a research problem in its own
 * right; this is the pragmatic on-device subset.
 *
 * An earlier version measured "low-frequency energy" via a single-pole lowpass filter's own RMS
 * output. That doesn't work: a one-pole filter only rolls off at -6dB/octave, so a "150Hz
 * lowpass" still passes a large fraction of energy from content centered hundreds of Hz above its
 * cutoff. Verified against a real drum stem (FFT ground truth on captured onset windows): hits
 * with only ~10% of their spectral power below 150Hz (centroid ~300-1200Hz -- textbook snare
 * body/wires) still showed a one-pole "low ratio" of 0.53-0.65, comfortably over the old 0.5
 * threshold, which is why that version classified 91% of a real song's drum hits as kick with
 * zero hi-hats. Measuring the same ratio via actual FFT bin power fixed it (340/572 kick vs.
 * 522/572 before, on the same audio) and produces a clearly bimodal low-band-power distribution
 * (most hits are either <25% or >85% low-band power, not smeared across the middle the way the
 * leaky filter's output was).
 */
object DrumHitClassifier {

    enum class Voice(val gmPitch: Int) {
        KICK(36),
        SNARE(38),
        CLOSED_HI_HAT(42),
        CRASH_OR_OPEN_HI_HAT(49),
    }

    private const val LOW_CUTOFF_HZ = 150f
    private const val HIGH_CUTOFF_HZ = 6000f
    private const val WINDOW_SECONDS = 0.05f
    private const val EARLY_WINDOW_SECONDS = 0.02f

    // Thresholds calibrated against FFT band-power ratios measured on a real separated drum
    // stem: low-band power is clearly bimodal (most hits <0.25 or >0.85, few in between), and
    // high-band power rarely exceeds ~0.1 even for hits with audible high-frequency content, so
    // it doesn't take much to flag genuine cymbal-dominant hits.
    private const val LOW_RATIO_KICK_THRESHOLD = 0.55f
    private const val HIGH_RATIO_CYMBAL_THRESHOLD = 0.15f
    private const val ZCR_NOISE_MIN = 0.15f
    private const val DECAY_RATIO_CLOSED_MAX = 0.35f

    fun classify(mono: FloatArray, onsetSample: Int, sampleRate: Int): Voice {
        val windowEnd = min(mono.size, onsetSample + (WINDOW_SECONDS * sampleRate).toInt())
        if (onsetSample >= windowEnd) return Voice.SNARE
        val window = mono.copyOfRange(onsetSample, windowEnd)

        val totalRms = AudioFilters.rms(window)
        if (totalRms <= 0f) return Voice.SNARE

        val (lowRatio, highRatio) = bandPowerRatios(window, sampleRate, LOW_CUTOFF_HZ, HIGH_CUTOFF_HZ)

        if (lowRatio >= LOW_RATIO_KICK_THRESHOLD) return Voice.KICK

        if (highRatio >= HIGH_RATIO_CYMBAL_THRESHOLD && AudioFilters.zeroCrossingRate(window) >= ZCR_NOISE_MIN) {
            val earlyEnd = min(window.size, (EARLY_WINDOW_SECONDS * sampleRate).toInt())
            val early = AudioFilters.rms(window, 0, earlyEnd)
            val late = AudioFilters.rms(window, earlyEnd, window.size)
            return if (early > 0f && late / early < DECAY_RATIO_CLOSED_MAX) {
                Voice.CLOSED_HI_HAT
            } else {
                Voice.CRASH_OR_OPEN_HI_HAT
            }
        }

        return Voice.SNARE
    }

    /** Fraction of [window]'s spectral power below [lowCutoffHz] and at/above [highCutoffHz]. */
    private fun bandPowerRatios(
        window: FloatArray,
        sampleRate: Int,
        lowCutoffHz: Float,
        highCutoffHz: Float,
    ): Pair<Float, Float> {
        var size = 1
        while (size < window.size) size = size shl 1

        val re = FloatArray(size)
        val im = FloatArray(size)
        val n = window.size
        for (i in window.indices) {
            val hann = if (n > 1) 0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat() else 1f
            re[i] = window[i] * hann
        }
        Fft.transform(re, im)

        val binHz = sampleRate.toFloat() / size
        var lowPower = 0.0
        var highPower = 0.0
        var totalPower = 0.0
        for (k in 0..size / 2) {
            val power = (re[k] * re[k] + im[k] * im[k]).toDouble()
            totalPower += power
            val freq = k * binHz
            if (freq < lowCutoffHz) lowPower += power
            if (freq >= highCutoffHz) highPower += power
        }
        if (totalPower <= 0.0) return 0f to 0f
        return (lowPower / totalPower).toFloat() to (highPower / totalPower).toFloat()
    }
}
