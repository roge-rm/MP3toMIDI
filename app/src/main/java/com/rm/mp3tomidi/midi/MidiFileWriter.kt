package com.rm.mp3tomidi.midi

import com.rm.mp3tomidi.convert.stages.Stem
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Writes a Standard MIDI File, format 0 (single track, all instruments on separate channels). */
object MidiFileWriter {

    fun write(
        stems: List<Stem>,
        ticksPerQuarterNote: Int = MidiConstants.TICKS_PER_QUARTER_NOTE,
        bpm: Int = MidiConstants.DEFAULT_BPM,
    ): ByteArray {
        val trackData = buildTrackData(stems, bpm)

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeBytes("MThd")
            data.writeInt(6)
            data.writeShort(0) // format 0: single track
            data.writeShort(1) // ntrks
            data.writeShort(ticksPerQuarterNote)

            data.writeBytes("MTrk")
            data.writeInt(trackData.size)
            data.write(trackData)
        }
        return out.toByteArray()
    }

    private fun buildTrackData(stems: List<Stem>, bpm: Int): ByteArray {
        val events = mutableListOf<TimedEvent>()

        val microsPerQuarterNote = 60_000_000 / bpm
        events += TimedEvent(0, EventOrder.META, tempoEvent(microsPerQuarterNote))

        // Channel 9 (0-indexed) is reserved for percussion by the GM spec; cycle everything
        // else through the remaining 15 channels, wrapping if there are more melodic stems
        // than channels available.
        val melodicChannels = (0..15).filter { it != MidiConstants.DRUM_CHANNEL }
        var nextMelodicChannelIndex = 0

        for (stem in stems) {
            val channel = if (stem.isDrumKit) {
                MidiConstants.DRUM_CHANNEL
            } else {
                melodicChannels[nextMelodicChannelIndex % melodicChannels.size].also { nextMelodicChannelIndex++ }
            }

            events += TimedEvent(0, EventOrder.PROGRAM_CHANGE, programChangeEvent(channel, stem.gmProgram))

            for (note in stem.notes) {
                events += TimedEvent(note.startTick, EventOrder.NOTE_ON, noteEvent(0x90, channel, note.pitch, note.velocity))
                events += TimedEvent(note.endTick, EventOrder.NOTE_OFF, noteEvent(0x80, channel, note.pitch, 0))
            }
        }

        events.sortWith(compareBy({ it.tick }, { it.order }))

        val out = ByteArrayOutputStream()
        var previousTick = 0L
        for (event in events) {
            out.write(vlq(event.tick - previousTick))
            out.write(event.bytes)
            previousTick = event.tick
        }
        out.write(vlq(0))
        out.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00)) // end of track

        return out.toByteArray()
    }

    private fun tempoEvent(microsPerQuarterNote: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0x51, 0x03,
        ((microsPerQuarterNote shr 16) and 0xFF).toByte(),
        ((microsPerQuarterNote shr 8) and 0xFF).toByte(),
        (microsPerQuarterNote and 0xFF).toByte(),
    )

    private fun programChangeEvent(channel: Int, program: Int): ByteArray = byteArrayOf(
        (0xC0 or channel).toByte(),
        program.toByte(),
    )

    private fun noteEvent(status: Int, channel: Int, pitch: Int, velocity: Int): ByteArray = byteArrayOf(
        (status or channel).toByte(),
        pitch.toByte(),
        velocity.toByte(),
    )

    /** Encodes a delta-time as a MIDI variable-length quantity (big-endian, 7 bits per byte). */
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

    private data class TimedEvent(val tick: Long, val order: Int, val bytes: ByteArray)

    private object EventOrder {
        const val META = 0
        const val PROGRAM_CHANGE = 1
        const val NOTE_OFF = 2
        const val NOTE_ON = 3
    }
}
