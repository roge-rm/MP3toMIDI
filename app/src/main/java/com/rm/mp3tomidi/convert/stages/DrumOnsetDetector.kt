package com.rm.mp3tomidi.convert.stages

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Percussive onset detection via energy flux: short-term RMS energy per frame, half-wave
 * rectified frame-to-frame difference, then peak-picking above an adaptive (mean + k*stddev)
 * threshold with a minimum inter-onset spacing.
 *
 * Runs two independent passes -- one on full-band energy (catches loud, broadband hits like kick
 * and snare), one on high-band-only energy (catches quiet high-frequency hits like closed
 * hi-hats) -- and merges them, rather than a single full-band pass. A single adaptive threshold
 * badly under-detects hi-hats specifically: verified on a real electronic song's drum stem, where
 * a full-band-only pass missed 57% of the high-frequency transients an independent high-band scan
 * could find, more than double the ~20-24% missed on a jungle or rock track whose full-band energy
 * is less dominated by kick/bass. The high-band pass uses [AudioFilters.highBandEnergyFlux] (a
 * real per-frame FFT) rather than a time-domain highpass filter -- see that function's doc for why
 * a filter leaks too much low-frequency energy through to be trustworthy here.
 */
object DrumOnsetDetector {

    fun detect(
        mono: FloatArray,
        sampleRate: Int,
        frameSize: Int = 512,
        hopSize: Int = 256,
        minIntervalMs: Int = 60,
        thresholdMultiplier: Float = 1.5f,
        highBandCutoffHz: Float = 6000f,
    ): List<Int> {
        val fullBandOnsets = detectFromFlux(
            AudioFilters.energyFlux(mono, frameSize, hopSize),
            hopSize,
            sampleRate,
            minIntervalMs,
            thresholdMultiplier,
        )
        val highBandOnsets = detectFromFlux(
            AudioFilters.highBandEnergyFlux(mono, frameSize, hopSize, sampleRate, highBandCutoffHz),
            hopSize,
            sampleRate,
            minIntervalMs,
            thresholdMultiplier,
        )
        return mergeOnsets(fullBandOnsets, highBandOnsets, minIntervalMs, sampleRate)
    }

    private fun detectFromFlux(
        flux: FloatArray,
        hopSize: Int,
        sampleRate: Int,
        minIntervalMs: Int,
        thresholdMultiplier: Float,
    ): List<Int> {
        val numFrames = flux.size
        if (numFrames < 3) return emptyList()

        val mean = flux.average().toFloat()
        var variance = 0.0
        for (v in flux) variance += (v - mean).toDouble() * (v - mean)
        val stddev = sqrt(variance / flux.size).toFloat()
        val threshold = mean + thresholdMultiplier * stddev

        val minIntervalFrames = max(1, (minIntervalMs.toLong() * sampleRate / 1000).toInt() / hopSize)
        val onsets = mutableListOf<Int>()
        var lastOnsetFrame = -minIntervalFrames
        for (f in 1 until numFrames - 1) {
            val isPeak = flux[f] > threshold && flux[f] >= flux[f - 1] && flux[f] >= flux[f + 1]
            if (isPeak && f - lastOnsetFrame >= minIntervalFrames) {
                onsets += f * hopSize
                lastOnsetFrame = f
            }
        }
        return onsets
    }

    /** Combines both passes' onsets, then re-applies the minimum-spacing gate across the merged,
     * time-sorted result so a high-band detection of a hit the full-band pass already caught
     * doesn't get double-counted as a second onset. */
    private fun mergeOnsets(a: List<Int>, b: List<Int>, minIntervalMs: Int, sampleRate: Int): List<Int> {
        val combined = (a + b).toSortedSet()
        val minIntervalSamples = (minIntervalMs.toLong() * sampleRate / 1000).toInt()
        val merged = mutableListOf<Int>()
        var lastSample = -minIntervalSamples
        for (onset in combined) {
            if (onset - lastSample >= minIntervalSamples) {
                merged += onset
                lastSample = onset
            }
        }
        return merged
    }
}
