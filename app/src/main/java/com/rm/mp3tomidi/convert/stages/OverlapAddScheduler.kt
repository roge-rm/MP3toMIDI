package com.rm.mp3tomidi.convert.stages

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One model-input window: [segmentLength] samples built by taking [readLength] real samples
 * starting at [readStart] in the source signal, padded with [padLeft] zeros on the left (and
 * implicitly zeros on the right to fill out the rest). The window is centered on the source
 * region [offset, offset + validLength) rather than right-padded, so the last (short) chunk
 * still gets real audio as context on both sides wherever it's available.
 *
 * After running the model on this window, the [validLength]-sample result for this chunk is
 * the center crop of the model's output starting at [trimStart].
 */
data class ChunkPlacement(
    val offset: Int,
    val validLength: Int,
    val readStart: Int,
    val readLength: Int,
    val padLeft: Int,
    val trimStart: Int,
)

/**
 * Reproduces demucs.apply.apply_model's chunking scheme: overlapping fixed-length windows
 * blended back together with a triangular crossfade, so segment boundaries don't produce
 * audible seams in the separated output.
 */
class OverlapAddScheduler(
    private val totalLength: Int,
    private val segmentLength: Int,
    overlap: Float = 0.25f,
    transitionPower: Float = 1f,
) {
    val stride: Int = ((1f - overlap) * segmentLength).toInt()
    val weight: FloatArray = buildWeight(segmentLength, transitionPower)

    fun chunkPlacements(): List<ChunkPlacement> {
        val placements = mutableListOf<ChunkPlacement>()
        var offset = 0
        while (offset < totalLength) {
            val validLength = min(segmentLength, totalLength - offset)
            val delta = segmentLength - validLength
            val start = offset - delta / 2
            val end = start + segmentLength
            val correctStart = max(0, start)
            val correctEnd = min(totalLength, end)
            placements += ChunkPlacement(
                offset = offset,
                validLength = validLength,
                readStart = correctStart,
                readLength = correctEnd - correctStart,
                padLeft = correctStart - start,
                trimStart = delta / 2,
            )
            offset += stride
        }
        return placements
    }

    companion object {
        private fun buildWeight(segmentLength: Int, transitionPower: Float): FloatArray {
            val half = segmentLength / 2
            val divisor = max(half, segmentLength - half).toFloat()
            return FloatArray(segmentLength) { i ->
                val ramp = if (i < half) i + 1 else segmentLength - i
                val normalized = ramp / divisor
                if (transitionPower == 1f) normalized else normalized.pow(transitionPower)
            }
        }
    }
}
