package com.rm.mp3tomidi.convert.stages

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class BasicPitchTranscriberTest {

    private val transcriber = BasicPitchTranscriber()

    @Test
    fun `velocityFor scales a note's velocity to its loudness relative to the stem peak`() {
        // A note at 50% of the stem's peak amplitude -- constant-amplitude signal, so RMS equals
        // that amplitude exactly.
        val audio = FloatArray(1000) { 0.5f }

        val velocity = transcriber.velocityFor(audio, 0, 1000, peakAmplitude = 1f)

        assertEquals((127 * 0.5f).roundToInt(), velocity)
    }

    @Test
    fun `velocityFor floors a very quiet note instead of letting it go silent`() {
        val audio = FloatArray(1000) { 0.001f }

        val velocity = transcriber.velocityFor(audio, 0, 1000, peakAmplitude = 1f)

        assertEquals(40, velocity) // MIN_VELOCITY
    }

    @Test
    fun `velocityFor caps at 127 even if the note's span happens to exceed the reported peak`() {
        val audio = FloatArray(1000) { 1f }

        val velocity = transcriber.velocityFor(audio, 0, 1000, peakAmplitude = 0.5f)

        assertEquals(127, velocity)
    }
}
