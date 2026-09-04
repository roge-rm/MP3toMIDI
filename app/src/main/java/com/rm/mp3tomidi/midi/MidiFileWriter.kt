package com.rm.mp3tomidi.midi

import com.rm.mp3tomidi.convert.stages.Stem
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Writes a Standard MIDI File: format 0 (single track, all instruments on separate channels) or
 * format 1 (one track per stem, plus a tempo-only track 0). */
object MidiFileWriter {

    fun write(
        stems: List<Stem>,
        ticksPerQuarterNote: Int = MidiConstants.TICKS_PER_QUARTER_NOTE,
        bpm: Int = MidiConstants.DEFAULT_BPM,
        format: Int = 0,
    ): ByteArray {
        val trackDatas = if (format == 1) buildMultiTrackData(stems, bpm) else listOf(buildTrackData(stems, bpm))

        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeBytes("MThd")
            data.writeInt(6)
            data.writeShort(format)
            data.writeShort(trackDatas.size)
            data.writeShort(ticksPerQuarterNote)

            for (trackData in trackDatas) {
                data.writeBytes("MTrk")
                data.writeInt(trackData.size)
                data.write(trackData)
            }
        }
        return out.toByteArray()
    }

    private fun buildTrackData(stems: List<Stem>, bpm: Int): ByteArray {
        val events = mutableListOf<TimedEvent>()
        events += TimedEvent(0, EventOrder.META, tempoEvent(microsPerQuarterNote(bpm)))

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
            events += stemEvents(stem, channel)
        }

        return trackBytes(events)
    }

    /** Track 0 carries only the tempo, so a format-1 file's per-stem tracks (1..n) each hold
     * exactly one instrument's events -- the conventional format-1 layout. */
    private fun buildMultiTrackData(stems: List<Stem>, bpm: Int): List<ByteArray> {
        val tempoTrack = trackBytes(listOf(TimedEvent(0, EventOrder.META, tempoEvent(microsPerQuarterNote(bpm)))))

        val melodicChannels = (0..15).filter { it != MidiConstants.DRUM_CHANNEL }
        var nextMelodicChannelIndex = 0

        val stemTracks = stems.map { stem ->
            val channel = if (stem.isDrumKit) {
                MidiConstants.DRUM_CHANNEL
            } else {
                melodicChannels[nextMelodicChannelIndex % melodicChannels.size].also { nextMelodicChannelIndex++ }
            }
            trackBytes(stemEvents(stem, channel))
        }

        return listOf(tempoTrack) + stemTracks
    }

    private fun stemEvents(stem: Stem, channel: Int): List<TimedEvent> {
        val events = mutableListOf<TimedEvent>()
        events += TimedEvent(0, EventOrder.PROGRAM_CHANGE, programChangeEvent(channel, stem.gmProgram))
        for (note in stem.notes) {
            events += TimedEvent(note.startTick, EventOrder.NOTE_ON, noteEvent(0x90, channel, note.pitch, note.velocity))
            events += TimedEvent(note.endTick, EventOrder.NOTE_OFF, noteEvent(0x80, channel, note.pitch, 0))
        }
        return events
    }

    private fun microsPerQuarterNote(bpm: Int): Int = 60_000_000 / bpm

    private fun trackBytes(unsortedEvents: List<TimedEvent>): ByteArray {
        val events = unsortedEvents.sortedWith(compareBy({ it.tick }, { it.order }))

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
