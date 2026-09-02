package com.rm.mp3tomidi.convert.stages

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlapAddSchedulerTest {

    // Expected values cross-checked against demucs.apply's TensorChunk/apply_model logic in
    // Python for totalLength=25, segmentLength=10, overlap=0.25 (stride=7).
    @Test
    fun `chunk placements match demucs' TensorChunk centering`() {
        val scheduler = OverlapAddScheduler(totalLength = 25, segmentLength = 10, overlap = 0.25f)

        assertEquals(7, scheduler.stride)

        val placements = scheduler.chunkPlacements()
        assertEquals(listOf(0, 7, 14, 21), placements.map { it.offset })
        assertEquals(listOf(10, 10, 10, 4), placements.map { it.validLength })

        val last = placements.last()
        assertEquals(18, last.readStart)
        assertEquals(7, last.readLength)
        assertEquals(0, last.padLeft)
        assertEquals(3, last.trimStart)

        val fullChunk = placements.first()
        assertEquals(0, fullChunk.readStart)
        assertEquals(10, fullChunk.readLength)
        assertEquals(0, fullChunk.padLeft)
        assertEquals(0, fullChunk.trimStart)
    }

    @Test
    fun `weight window is a normalized triangle peaking at 1`() {
        val scheduler = OverlapAddScheduler(totalLength = 100, segmentLength = 10, overlap = 0.25f)

        assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 1.0f, 0.8f, 0.6f, 0.4f, 0.2f), scheduler.weight.toList())
    }

    @Test
    fun `a signal shorter than one segment is centered with symmetric padding`() {
        // totalLength=5 < stride=7, so there's exactly one chunk, and it's shorter than
        // segmentLength=10 everywhere -- the whole thing is padding, split left/right.
        val scheduler = OverlapAddScheduler(totalLength = 5, segmentLength = 10, overlap = 0.25f)

        val placements = scheduler.chunkPlacements()
        assertEquals(1, placements.size)
        val only = placements.single()
        assertEquals(0, only.offset)
        assertEquals(5, only.validLength)
        assertEquals(0, only.readStart)
        assertEquals(5, only.readLength)
        assertEquals(2, only.padLeft)
        assertEquals(2, only.trimStart)
    }
}
