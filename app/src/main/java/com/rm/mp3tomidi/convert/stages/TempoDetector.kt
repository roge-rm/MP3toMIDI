package com.rm.mp3tomidi.convert.stages

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Estimates a single global tempo (BPM) for a song from its onset-strength envelope, via
 * autocorrelation: the same energy-flux signal DrumOnsetDetector peak-picks for individual hits
 * is instead correlated against time-shifted copies of itself, and the shift ("lag") with the
 * strongest self-similarity is taken as the beat period. Meant to be run once per song against
 * the isolated drums stem (the clearest rhythmic signal available), not per-stem -- MIDI only
 * has one tempo track, and the whole point is to make the exported file's beat grid line up with
 * the source instead of always claiming a fixed 120 BPM regardless of the actual song.
 *
 * Like DrumHitClassifier, there's no ground truth to check this against, only synthetic click
 * tracks at known BPMs (see the test) plus manual sanity-checking on real songs. Autocorrelation
 * of a periodic signal is inherently ambiguous between a tempo and its integer multiples/divisors
 * (half-time, double-time, etc.) -- a mild log-domain prior centered on a typical-song tempo
 * breaks ties among otherwise-similar-strength candidates in favor of the more usual answer, but
 * doesn't rule out a genuine result far from it.
 */
object TempoDetector {

    const val DEFAULT_BPM = 120

    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0
    private const val FRAME_SIZE = 1024
    private const val HOP_SIZE = 512

    // A mild bias toward common tempos, expressed as a Gaussian in octaves (log2 of the ratio to
    // the reference tempo) rather than raw BPM, since "twice as fast" is the natural unit of
    // ambiguity for a periodic signal, not "+120 BPM". Wide sigma: this should only break ties
    // between similarly-strong candidates, not override a clearly dominant one.
    private const val REFERENCE_BPM = 120.0
    private const val PRIOR_SIGMA_OCTAVES = 0.7

    private const val FLUX_EPSILON = 1e-9

    fun detectBpm(mono: FloatArray, sampleRate: Int): Int {
        val flux = AudioFilters.energyFlux(mono, FRAME_SIZE, HOP_SIZE)
        val frameRate = sampleRate.toDouble() / HOP_SIZE

        val minLag = (frameRate * 60.0 / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = (frameRate * 60.0 / MIN_BPM).toInt()
        if (flux.size < 8 || minLag >= maxLag || maxLag >= flux.size - 1) return DEFAULT_BPM
        if (flux.sumOf { it.toDouble() } <= FLUX_EPSILON) return DEFAULT_BPM

        val scores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until flux.size - lag) sum += flux[i] * flux[i + lag]
            scores[lag] = sum * tempoPrior(60.0 * frameRate / lag)
        }

        var bestLag = minLag
        for (lag in minLag..maxLag) if (scores[lag] > scores[bestLag]) bestLag = lag

        val refinedLag = parabolicRefine(scores, bestLag)
        val bpm = 60.0 * frameRate / refinedLag
        return bpm.roundToInt().coerceIn(MIN_BPM.toInt(), MAX_BPM.toInt())
    }

    private fun tempoPrior(bpm: Double): Double {
        val octavesFromReference = ln(bpm / REFERENCE_BPM) / ln(2.0)
        val exponent = (octavesFromReference * octavesFromReference) / (2 * PRIOR_SIGMA_OCTAVES * PRIOR_SIGMA_OCTAVES)
        return exp(-exponent)
    }

    /** Sub-frame peak refinement via parabolic interpolation around the best integer lag. */
    private fun parabolicRefine(scores: DoubleArray, bestLag: Int): Double {
        if (bestLag <= 0 || bestLag >= scores.size - 1) return bestLag.toDouble()
        val y0 = scores[bestLag - 1]
        val y1 = scores[bestLag]
        val y2 = scores[bestLag + 1]
        val denom = y0 - 2 * y1 + y2
        if (denom == 0.0) return bestLag.toDouble()
        return bestLag + 0.5 * (y0 - y2) / denom
    }
}
