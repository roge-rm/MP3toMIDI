package com.rm.mp3tomidi.convert.stages

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Percussive onset detection via energy flux: short-term RMS energy per frame, half-wave
 * rectified frame-to-frame difference, then peak-picking above an adaptive (mean + k*stddev)
 * threshold with a minimum inter-onset spacing. A cheaper cousin of spectral flux -- since this
 * only ever runs on an already-isolated drum stem, plain energy transients are enough to find
 * hits without needing an FFT.
 */
object DrumOnsetDetector {

    fun detect(
        mono: FloatArray,
        sampleRate: Int,
        frameSize: Int = 512,
        hopSize: Int = 256,
        minIntervalMs: Int = 60,
        thresholdMultiplier: Float = 1.5f,
    ): List<Int> {
        if (mono.size < frameSize) return emptyList()
        val numFrames = (mono.size - frameSize) / hopSize + 1
        if (numFrames < 3) return emptyList()

        val energies = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            energies[f] = AudioFilters.rms(mono, f * hopSize, f * hopSize + frameSize)
        }

        val flux = FloatArray(numFrames)
        for (f in 1 until numFrames) flux[f] = max(0f, energies[f] - energies[f - 1])

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
}
