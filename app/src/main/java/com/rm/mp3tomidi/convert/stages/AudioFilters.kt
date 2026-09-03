package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/** Small DSP primitives shared by the drum onset/classification heuristics. */
object AudioFilters {

    /**
     * One-pole IIR lowpass. The complementary highpass at the same cutoff is
     * `x[i] - onePoleLowpass(x, cutoff)[i]`.
     */
    fun onePoleLowpass(x: FloatArray, cutoffHz: Float, sampleRate: Int): FloatArray {
        val alpha = 1f - exp(-2f * PI.toFloat() * cutoffHz / sampleRate)
        val y = FloatArray(x.size)
        var prev = 0f
        for (i in x.indices) {
            prev += alpha * (x[i] - prev)
            y[i] = prev
        }
        return y
    }

    fun rms(x: FloatArray, fromIndex: Int = 0, toIndex: Int = x.size): Float {
        if (toIndex <= fromIndex) return 0f
        var sum = 0.0
        for (i in fromIndex until toIndex) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (toIndex - fromIndex)).toFloat()
    }

    fun peak(x: FloatArray, fromIndex: Int = 0, toIndex: Int = x.size): Float {
        var max = 0f
        for (i in fromIndex until toIndex) {
            val v = if (x[i] < 0f) -x[i] else x[i]
            if (v > max) max = v
        }
        return max
    }

    fun zeroCrossingRate(x: FloatArray): Float {
        if (x.size < 2) return 0f
        var crossings = 0
        for (i in 1 until x.size) {
            if ((x[i - 1] >= 0f) != (x[i] >= 0f)) crossings++
        }
        return crossings.toFloat() / (x.size - 1)
    }

    /**
     * Half-wave-rectified frame-to-frame RMS difference: a cheap proxy for "how much new energy
     * arrived" per frame, used as the onset-strength signal by both [DrumOnsetDetector] (peak-pick
     * it directly) and [TempoDetector] (autocorrelate it to find the dominant periodicity).
     */
    fun energyFlux(x: FloatArray, frameSize: Int, hopSize: Int): FloatArray {
        if (x.size < frameSize) return FloatArray(0)
        val numFrames = (x.size - frameSize) / hopSize + 1
        if (numFrames < 2) return FloatArray(0)

        val energies = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            energies[f] = rms(x, f * hopSize, f * hopSize + frameSize)
        }

        val flux = FloatArray(numFrames)
        for (f in 1 until numFrames) flux[f] = maxOf(0f, energies[f] - energies[f - 1])
        return flux
    }

    /**
     * Same idea as [energyFlux], but the per-frame energy only counts spectral power at or above
     * [cutoffHz] (via a real FFT -- [Fft] requires a power-of-two size, which the frame sizes used
     * here already are) instead of full-band time-domain RMS.
     *
     * [DrumOnsetDetector] uses this as a second, independent onset-detection pass because a single
     * full-band adaptive threshold badly under-detects quiet high-frequency hits (hi-hats) when
     * louder low-frequency ones (kick/bass) dominate the track's overall energy statistics --
     * confirmed on real audio: an electronic song's drums missed 57% of its independently-detectable
     * high-band transients this way, more than double the ~20-24% missed on a jungle or rock track
     * with a more balanced full-band energy budget. A one-pole highpass filter was tried first and
     * rejected for the same leakiness reason documented on DrumHitClassifier -- it let enough
     * low-frequency energy through to spuriously flag kick decay tails as "high-band" onsets.
     */
    fun highBandEnergyFlux(x: FloatArray, frameSize: Int, hopSize: Int, sampleRate: Int, cutoffHz: Float): FloatArray {
        if (x.size < frameSize) return FloatArray(0)
        val numFrames = (x.size - frameSize) / hopSize + 1
        if (numFrames < 2) return FloatArray(0)

        val binHz = sampleRate.toFloat() / frameSize
        val energies = FloatArray(numFrames)
        val re = FloatArray(frameSize)
        val im = FloatArray(frameSize)
        for (f in 0 until numFrames) {
            val start = f * hopSize
            for (i in 0 until frameSize) {
                val hann = 0.5f - 0.5f * cos(2.0 * PI * i / (frameSize - 1)).toFloat()
                re[i] = x[start + i] * hann
                im[i] = 0f
            }
            Fft.transform(re, im)

            var highPower = 0.0
            for (k in 0..frameSize / 2) {
                if (k * binHz >= cutoffHz) highPower += (re[k] * re[k] + im[k] * im[k]).toDouble()
            }
            energies[f] = sqrt(highPower).toFloat()
        }

        val flux = FloatArray(numFrames)
        for (f in 1 until numFrames) flux[f] = maxOf(0f, energies[f] - energies[f - 1])
        return flux
    }
}
