package com.rm.mp3tomidi.convert.stages

/**
 * Overlap-add accumulation for [sourceCount] parallel sources (e.g. Demucs' 6 stems), bounded
 * to O(segmentLength) memory regardless of total signal length -- naively accumulating into a
 * whole-song-length buffer per source is what OOMs on a several-minute song times 6 stems.
 *
 * A frame at index t is final (no chunk will ever add to it again) once every chunk that could
 * write to it has been added. Since [OverlapAddScheduler] produces placements in increasing
 * offset order and a chunk only ever writes at or after its own offset, that's exactly every
 * chunk with offset <= t. So after [addChunk] for chunk i, everything before the *next*
 * chunk's offset (or the end of the signal, for the last chunk) is safe to [flushUpTo].
 */
class StreamingOverlapAdder(
    private val sourceCount: Int,
    private val channels: Int,
    private val segmentLength: Int,
    private val minWeight: Float = 1e-8f,
) {
    // A chunk's own contribution can span at most segmentLength frames, and consecutive
    // chunks start `stride` frames apart, so the unflushed span is bounded by
    // stride + segmentLength < 2 * segmentLength.
    private val capacity = 2 * segmentLength
    private val accum = Array(sourceCount) { FloatArray(capacity * channels) }
    private val weight = FloatArray(capacity)
    private var bufferStartFrame = 0
    private var bufferLength = 0

    /** [chunkOutput] is flat (source, channel, sample), as produced by the separation model. */
    fun addChunk(placement: ChunkPlacement, chunkWeight: FloatArray, chunkOutput: FloatArray) {
        val localOffset = placement.offset - bufferStartFrame
        check(localOffset + placement.validLength <= capacity) {
            "Overlap-add buffer too small: needed ${localOffset + placement.validLength}, have $capacity"
        }
        val sourceStride = channels * segmentLength

        for (s in 0 until sourceCount) {
            val sourceBase = s * sourceStride
            val sourceAccum = accum[s]
            for (i in 0 until placement.validLength) {
                val w = chunkWeight[placement.trimStart + i]
                val localFrame = localOffset + i
                for (ch in 0 until channels) {
                    val chunkIdx = sourceBase + ch * segmentLength + placement.trimStart + i
                    sourceAccum[localFrame * channels + ch] += w * chunkOutput[chunkIdx]
                }
            }
        }
        for (i in 0 until placement.validLength) {
            weight[localOffset + i] += chunkWeight[placement.trimStart + i]
        }
        bufferLength = maxOf(bufferLength, localOffset + placement.validLength)
    }

    /**
     * Normalizes and returns each source's interleaved PCM for frames finalized now that no
     * future chunk starts before [nextChunkOffset], or null if nothing new is finalized yet.
     */
    fun flushUpTo(nextChunkOffset: Int): Array<FloatArray>? {
        val flushCount = nextChunkOffset - bufferStartFrame
        if (flushCount <= 0) return null

        val result = Array(sourceCount) { s ->
            FloatArray(flushCount * channels).also { out ->
                for (f in 0 until flushCount) {
                    val w = weight[f].coerceAtLeast(minWeight)
                    for (ch in 0 until channels) {
                        out[f * channels + ch] = accum[s][f * channels + ch] / w
                    }
                }
            }
        }

        val remaining = bufferLength - flushCount
        for (s in 0 until sourceCount) {
            System.arraycopy(accum[s], flushCount * channels, accum[s], 0, remaining * channels)
            java.util.Arrays.fill(accum[s], remaining * channels, bufferLength * channels, 0f)
        }
        System.arraycopy(weight, flushCount, weight, 0, remaining)
        java.util.Arrays.fill(weight, remaining, bufferLength, 0f)

        bufferStartFrame = nextChunkOffset
        bufferLength = remaining
        return result
    }
}
