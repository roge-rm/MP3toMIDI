package com.rm.mp3tomidi.midi

import com.rm.mp3tomidi.convert.stages.NoteEvent
import com.rm.mp3tomidi.convert.stages.Stem
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiFileParserTest {

    @Test
    fun `round-trips this app's own MidiFileWriter output`() {
        val drumStem = Stem(
            label = "drums",
            gmProgram = 0,
            isDrumKit = true,
            notes = listOf(NoteEvent(startTick = 0, endTick = 240, pitch = 36, velocity = 100)),
        )
        val pianoStem = Stem(
            label = "piano",
            gmProgram = 4, // electric piano
            isDrumKit = false,
            notes = listOf(
                NoteEvent(startTick = 480, endTick = 960, pitch = 60, velocity = 90),
                NoteEvent(startTick = 960, endTick = 1440, pitch = 64, velocity = 90),
            ),
        )

        // Default 480 ticks/quarter at 120 BPM (the writer's own default) makes 1 quarter note
        // exactly 500ms -- clean round numbers, no floating-point rounding to worry about.
        val bytes = MidiFileWriter.write(listOf(drumStem, pianoStem), bpm = 120)
        val parsed = MidiFileParser.parse(bytes)

        assertEquals(1500L, parsed.durationMs) // last event (endTick 1440 = 3 quarter notes) at 120 BPM

        val programChanges = parsed.events.filter { it.type == TimedEvent.Type.PROGRAM_CHANGE }
        assertEquals(
            setOf(
                TimedEvent(0, MidiConstants.DRUM_CHANNEL, TimedEvent.Type.PROGRAM_CHANGE, 0),
                TimedEvent(0, 0, TimedEvent.Type.PROGRAM_CHANGE, 4),
            ),
            programChanges.toSet(),
        )

        val noteOns = parsed.events.filter { it.type == TimedEvent.Type.NOTE_ON }
        assertEquals(
            setOf(
                TimedEvent(0, MidiConstants.DRUM_CHANNEL, TimedEvent.Type.NOTE_ON, 36, 100),
                TimedEvent(500, 0, TimedEvent.Type.NOTE_ON, 60, 90),
                TimedEvent(1000, 0, TimedEvent.Type.NOTE_ON, 64, 90),
            ),
            noteOns.toSet(),
        )

        val noteOffs = parsed.events.filter { it.type == TimedEvent.Type.NOTE_OFF }
        assertEquals(
            setOf(
                TimedEvent(250, MidiConstants.DRUM_CHANNEL, TimedEvent.Type.NOTE_OFF, 36, 0),
                TimedEvent(1000, 0, TimedEvent.Type.NOTE_OFF, 60, 0),
                TimedEvent(1500, 0, TimedEvent.Type.NOTE_OFF, 64, 0),
            ),
            noteOffs.toSet(),
        )
    }

    @Test
    fun `applies a tempo change from a non-first track globally to every track`() {
        // Track 0: a Note On at tick 960 (two quarter notes in), no tempo events of its own.
        val track0 = trackBytes {
            writeEvent(delta = 960, bytes = byteArrayOf(0x90.toByte(), 60, 100))
        }
        // Track 1: a Set Tempo at tick 480 (halfway to that note), changing 120 BPM -> 240 BPM.
        val track1 = trackBytes {
            writeEvent(delta = 480, bytes = byteArrayOf(0xFF.toByte(), 0x51, 0x03, 0x03, 0xD0.toByte(), 0x90.toByte()))
        }
        val bytes = smfHeader(format = 1, trackCount = 2, division = 480) + track0 + track1

        val parsed = MidiFileParser.parse(bytes)

        // First quarter (tick 0->480) at the default 120 BPM = 500ms, second quarter (tick
        // 480->960) at the new 240 BPM = 250ms -- 750ms total, not 1000ms (what a parser that
        // ignored the other track's tempo event, or applied it too late, would compute).
        val noteOn = parsed.events.single { it.type == TimedEvent.Type.NOTE_ON }
        assertEquals(750L, noteOn.timestampMs)
    }

    @Test
    fun `a Note On with velocity 0 decodes as Note Off, including under running status`() {
        val track = trackBytes {
            writeEvent(delta = 0, bytes = byteArrayOf(0x90.toByte(), 60, 100))
            // Running status: no repeated 0x90 status byte, just the two data bytes.
            writeEvent(delta = 100, bytes = byteArrayOf(60, 0))
        }
        val bytes = smfHeader(format = 0, trackCount = 1, division = 480) + track

        val parsed = MidiFileParser.parse(bytes)

        assertEquals(
            listOf(
                TimedEvent(0, 0, TimedEvent.Type.NOTE_ON, 60, 100),
                TimedEvent(0, 0, TimedEvent.Type.NOTE_OFF, 60, 0),
            ).map { it.type },
            parsed.events.map { it.type },
        )
        assertEquals(100L * 500_000 / 1000 / 480, parsed.events[1].timestampMs)
    }

    private fun smfHeader(format: Int, trackCount: Int, division: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(intToBytes(6, 4))
        out.write(intToBytes(format, 2))
        out.write(intToBytes(trackCount, 2))
        out.write(intToBytes(division, 2))
        return out.toByteArray()
    }

    private fun trackBytes(build: TrackBuilder.() -> Unit): ByteArray {
        val builder = TrackBuilder().apply(build)
        builder.writeEvent(delta = 0, bytes = byteArrayOf(0xFF.toByte(), 0x2F, 0x00)) // end of track
        val body = builder.output.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MTrk".toByteArray())
        out.write(intToBytes(body.size, 4))
        out.write(body)
        return out.toByteArray()
    }

    private class TrackBuilder {
        val output = ByteArrayOutputStream()

        fun writeEvent(delta: Long, bytes: ByteArray) {
            output.write(vlq(delta))
            output.write(bytes)
        }

        private fun vlq(value: Long): ByteArray {
            var v = value
            val bytes = mutableListOf((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) {
                bytes.add(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            return bytes.reversed().map { it.toByte() }.toByteArray()
        }
    }

    private fun intToBytes(value: Int, byteCount: Int): ByteArray =
        ByteArray(byteCount) { i -> ((value shr (8 * (byteCount - 1 - i))) and 0xFF).toByte() }
}
