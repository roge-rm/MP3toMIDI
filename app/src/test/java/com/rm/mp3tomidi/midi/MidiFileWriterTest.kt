package com.rm.mp3tomidi.midi

import com.rm.mp3tomidi.convert.stages.NoteEvent
import com.rm.mp3tomidi.convert.stages.Stem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiFileWriterTest {

    @Test
    fun `header chunk describes a format 0 single track file`() {
        val bytes = MidiFileWriter.write(stems = emptyList(), ticksPerQuarterNote = 480)

        assertEquals("MThd", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(6, bytes.readInt32(4))
        assertEquals(0, bytes.readInt16(8)) // format 0
        assertEquals(1, bytes.readInt16(10)) // ntrks
        assertEquals(480, bytes.readInt16(12)) // division
        assertEquals("MTrk", String(bytes, 14, 4, Charsets.US_ASCII))
    }

    @Test
    fun `track length matches the bytes that follow it`() {
        val stem = Stem(
            label = "mix",
            gmProgram = 0,
            isDrumKit = false,
            notes = listOf(NoteEvent(startTick = 0, endTick = 960, pitch = 60, velocity = 100)),
        )

        val bytes = MidiFileWriter.write(stems = listOf(stem))

        val trackLength = bytes.readInt32(18)
        assertEquals(bytes.size, 22 + trackLength)
    }

    @Test
    fun `track ends with an end-of-track meta event`() {
        val stem = Stem(
            label = "mix",
            gmProgram = 0,
            isDrumKit = false,
            notes = listOf(NoteEvent(startTick = 0, endTick = 960, pitch = 60, velocity = 100)),
        )

        val bytes = MidiFileWriter.write(stems = listOf(stem))

        val last3 = bytes.copyOfRange(bytes.size - 3, bytes.size)
        assertTrue(last3.contentEquals(byteArrayOf(0xFF.toByte(), 0x2F, 0x00)))
    }

    @Test
    fun `drum stems are placed on the reserved percussion channel`() {
        val drumStem = Stem(label = "drums", gmProgram = 0, isDrumKit = true, notes = emptyList())
        val melodicStem = Stem(label = "bass", gmProgram = 33, isDrumKit = false, notes = emptyList())

        val bytes = MidiFileWriter.write(stems = listOf(drumStem, melodicStem))
        val trackData = bytes.copyOfRange(22, bytes.size)

        // Program-change status bytes are 0xC0 | channel; find each one and check its channel.
        val programChangeChannels = trackData.toList()
            .filter { (it.toInt() and 0xF0) == 0xC0 }
            .map { it.toInt() and 0x0F }

        assertTrue(programChangeChannels.contains(MidiConstants.DRUM_CHANNEL))
        assertTrue(programChangeChannels.any { it != MidiConstants.DRUM_CHANNEL })
    }

    @Test
    fun `format 1 writes one tempo track plus one track per stem`() {
        val stems = listOf(
            Stem(label = "drums", gmProgram = 0, isDrumKit = true, notes = emptyList()),
            Stem(
                label = "bass",
                gmProgram = 33,
                isDrumKit = false,
                notes = listOf(NoteEvent(startTick = 0, endTick = 480, pitch = 40, velocity = 100)),
            ),
        )

        val bytes = MidiFileWriter.write(stems = stems, format = 1)

        assertEquals(1, bytes.readInt16(8)) // format 1
        assertEquals(3, bytes.readInt16(10)) // ntrks: tempo + 2 stems
    }

    @Test
    fun `format 1 round-trips through MidiFileParser with program changes on the right channels`() {
        val stems = listOf(
            Stem(label = "drums", gmProgram = 0, isDrumKit = true, notes = emptyList()),
            Stem(
                label = "bass",
                gmProgram = 33,
                isDrumKit = false,
                notes = listOf(NoteEvent(startTick = 0, endTick = 480, pitch = 40, velocity = 100)),
            ),
        )

        val bytes = MidiFileWriter.write(stems = stems, format = 1)
        val parsed = MidiFileParser.parse(bytes)

        val programChanges = parsed.events.filter { it.type == TimedEvent.Type.PROGRAM_CHANGE }
        assertTrue(programChanges.any { it.channel == MidiConstants.DRUM_CHANNEL && it.data1 == 0 })
        assertTrue(programChanges.any { it.channel != MidiConstants.DRUM_CHANNEL && it.data1 == 33 })
        assertTrue(parsed.events.any { it.type == TimedEvent.Type.NOTE_ON && it.data1 == 40 })
    }

    private fun ByteArray.readInt32(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.readInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
}
