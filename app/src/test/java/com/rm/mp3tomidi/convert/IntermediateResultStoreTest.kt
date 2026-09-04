package com.rm.mp3tomidi.convert

import com.rm.mp3tomidi.convert.stages.NoteEvent
import com.rm.mp3tomidi.convert.stages.Stem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class IntermediateResultStoreTest {

    @Test
    fun `round-trips a multi-stem result with representative note data`() {
        val stems = listOf(
            Stem(
                label = "drums",
                gmProgram = 0,
                isDrumKit = true,
                notes = (0 until 50).map { NoteEvent(startTick = it * 100L, endTick = it * 100L + 40, pitch = 36, velocity = 100) },
            ),
            Stem(
                label = "bass",
                gmProgram = 33,
                isDrumKit = false,
                notes = listOf(NoteEvent(startTick = 0, endTick = 480, pitch = 40, velocity = 90)),
                confidence = 0.22f,
            ),
            Stem(label = "piano", gmProgram = 0, isDrumKit = false, notes = emptyList()),
        )
        val result = IntermediateResult(
            stems = stems,
            bpm = 128,
            options = ConversionOptions(
                includedStemLabels = setOf("drums", "bass", "piano"),
                noteFrameThreshold = 0.25f,
                silentStemRmsRatio = 0.05f,
                outputMode = OutputMode.SINGLE_MULTI_TRACK,
            ),
        )
        val file = File.createTempFile("intermediate_result_test", ".bin")

        try {
            IntermediateResultStore.write(file, result)
            val read = IntermediateResultStore.read(file)

            assertEquals(result, read)
        } finally {
            file.delete()
        }
    }
}
