package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
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

    fun zeroCrossingRate(x: FloatArray): Float {
        if (x.size < 2) return 0f
        var crossings = 0
        for (i in 1 until x.size) {
            if ((x[i - 1] >= 0f) != (x[i] >= 0f)) crossings++
        }
        return crossings.toFloat() / (x.size - 1)
    }
}
