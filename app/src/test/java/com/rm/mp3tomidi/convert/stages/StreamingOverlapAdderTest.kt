package com.rm.mp3tomidi.convert.stages

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StreamingOverlapAdderTest {

    private val sourceCount = 3
    private val channels = 2
    private val segmentLength = 10
    private val totalLength = 47 // deliberately not a multiple of stride, like real audio

    @Test
    fun `streaming flush-as-you-go matches a naive whole-buffer accumulation`() {
        val scheduler = OverlapAddScheduler(totalLength, segmentLength, overlap = 0.25f)
        val placements = scheduler.chunkPlacements()

        val random = Random(42)
        val chunkOutputs = placements.map {
            FloatArray(sourceCount * channels * segmentLength) { random.nextFloat() }
        }

        val expected = naiveAccumulate(scheduler, placements, chunkOutputs)
        val actual = streamingAccumulate(scheduler, placements, chunkOutputs)

        for (s in 0 until sourceCount) {
            assertArrayEquals("source $s", expected[s], actual[s], 1e-6f)
        }
    }

    private fun streamingAccumulate(
        scheduler: OverlapAddScheduler,
        placements: List<ChunkPlacement>,
        chunkOutputs: List<FloatArray>,
    ): Array<FloatArray> {
        val adder = StreamingOverlapAdder(sourceCount, channels, segmentLength)
        val out = Array(sourceCount) { FloatArray(totalLength * channels) }
        var written = 0

        placements.forEachIndexed { index, placement ->
            adder.addChunk(placement, scheduler.weight, chunkOutputs[index])
            val nextOffset = if (index + 1 < placements.size) placements[index + 1].offset else totalLength
            adder.flushUpTo(nextOffset)?.let { flushed ->
                val frameCount = flushed[0].size / channels
                for (s in 0 until sourceCount) {
                    System.arraycopy(flushed[s], 0, out[s], written * channels, flushed[s].size)
                }
                written += frameCount
            }
        }
        return out
    }

    /** Mirrors the whole-buffer approach DemucsStemSeparator used before streaming. */
    private fun naiveAccumulate(
        scheduler: OverlapAddScheduler,
        placements: List<ChunkPlacement>,
        chunkOutputs: List<FloatArray>,
    ): Array<FloatArray> {
        val accum = Array(sourceCount) { FloatArray(totalLength * channels) }
        val sumWeight = FloatArray(totalLength)

        placements.forEachIndexed { index, placement ->
            val chunkOutput = chunkOutputs[index]
            val sourceStride = channels * segmentLength
            for (s in 0 until sourceCount) {
                val sourceBase = s * sourceStride
                for (i in 0 until placement.validLength) {
                    val w = scheduler.weight[placement.trimStart + i]
                    val t = placement.offset + i
                    for (ch in 0 until channels) {
                        val chunkIdx = sourceBase + ch * segmentLength + placement.trimStart + i
                        accum[s][t * channels + ch] += w * chunkOutput[chunkIdx]
                    }
                }
            }
            for (i in 0 until placement.validLength) {
                sumWeight[placement.offset + i] += scheduler.weight[placement.trimStart + i]
            }
        }

        for (source in accum) {
            for (frame in 0 until totalLength) {
                val w = sumWeight[frame].coerceAtLeast(1e-8f)
                for (ch in 0 until channels) {
                    source[frame * channels + ch] /= w
                }
            }
        }
        return accum
    }
}
