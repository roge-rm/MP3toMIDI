package com.rm.mp3tomidi.midi

/** One playback-relevant event, already resolved to a real-time offset from the start of the file. */
data class TimedEvent(
    val timestampMs: Long,
    val channel: Int,
    val type: Type,
    /** MIDI note number (NOTE_ON/NOTE_OFF) or GM program number (PROGRAM_CHANGE). */
    val data1: Int,
    /** Velocity (NOTE_ON/NOTE_OFF only); unused (0) for PROGRAM_CHANGE. */
    val data2: Int = 0,
) {
    enum class Type { NOTE_ON, NOTE_OFF, PROGRAM_CHANGE }
}

data class ParsedMidi(val events: List<TimedEvent>, val durationMs: Long)

/**
 * Parses a general Standard MIDI File (format 0 or 1) into a single flat, time-sorted,
 * already-tempo-resolved event list -- simpler than a streaming/incremental player design (e.g.
 * roge-rm/midiTracker's, built for an SD-card-streaming embedded target) since Android can just
 * hold the whole thing in memory: even a large third-party file is nowhere near a real
 * constraint here, and this app's own [MidiFileWriter] output is always tens of KB.
 *
 * Deliberately out of scope: Control Change, Pitch Bend, Channel/Poly Pressure, and SysEx are
 * all parsed far enough to stay in sync (their data bytes are consumed so running status and
 * subsequent delta-times decode correctly) but dropped rather than surfaced as events --
 * [MidiFileWriter] never emits any of them, and general support is real scope beyond what
 * in-app playback needs right now. Format 2 (independent sequences) and SMPTE time division are
 * both rejected outright, same documented limitation as midiTracker's player -- neither shows up
 * in files this app or virtually any other real-world tool produces.
 */
object MidiFileParser {

    private const val DEFAULT_US_PER_QUARTER = 500_000 // 120 BPM

    private data class RawEvent(val tick: Long, val channel: Int, val type: TimedEvent.Type, val data1: Int, val data2: Int)
    private data class TempoChange(val tick: Long, val usPerQuarter: Int)

    fun parse(bytes: ByteArray): ParsedMidi {
        val reader = ByteReader(bytes)

        require(reader.readFourCC() == "MThd") { "Not a Standard MIDI File (missing MThd)" }
        val headerLength = reader.readInt32()
        val headerEnd = reader.position + headerLength
        val format = reader.readInt16()
        require(format != 2) { "Format 2 (independent sequences) is not supported" }
        val trackCount = reader.readInt16()
        val division = reader.readInt16()
        require(division and 0x8000 == 0) { "SMPTE time division is not supported" }
        reader.position = headerEnd

        val rawEvents = mutableListOf<RawEvent>()
        val tempoChanges = mutableListOf(TempoChange(0, DEFAULT_US_PER_QUARTER))

        repeat(trackCount) {
            require(reader.readFourCC() == "MTrk") { "Expected MTrk chunk" }
            val trackLength = reader.readInt32()
            val trackEnd = reader.position + trackLength
            parseTrack(reader, trackEnd, rawEvents, tempoChanges)
            reader.position = trackEnd
        }

        tempoChanges.sortBy { it.tick }
        val cumulativeMsAtBoundary = DoubleArray(tempoChanges.size)
        for (i in 1 until tempoChanges.size) {
            val ticksInSegment = tempoChanges[i].tick - tempoChanges[i - 1].tick
            val msPerTick = tempoChanges[i - 1].usPerQuarter / 1000.0 / division
            cumulativeMsAtBoundary[i] = cumulativeMsAtBoundary[i - 1] + ticksInSegment * msPerTick
        }

        fun tickToMs(tick: Long): Long {
            var lo = 0
            var hi = tempoChanges.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (tempoChanges[mid].tick <= tick) lo = mid else hi = mid - 1
            }
            val ticksIntoSegment = tick - tempoChanges[lo].tick
            val msPerTick = tempoChanges[lo].usPerQuarter / 1000.0 / division
            return (cumulativeMsAtBoundary[lo] + ticksIntoSegment * msPerTick).toLong()
        }

        val timedEvents = rawEvents
            .map { TimedEvent(tickToMs(it.tick), it.channel, it.type, it.data1, it.data2) }
            .sortedWith(compareBy({ it.timestampMs }, { eventOrder(it.type) }))

        val durationMs = timedEvents.maxOfOrNull { it.timestampMs } ?: 0L
        return ParsedMidi(timedEvents, durationMs)
    }

    private fun eventOrder(type: TimedEvent.Type): Int = when (type) {
        TimedEvent.Type.PROGRAM_CHANGE -> 0
        TimedEvent.Type.NOTE_OFF -> 1
        TimedEvent.Type.NOTE_ON -> 2
    }

    private fun parseTrack(
        reader: ByteReader,
        trackEnd: Int,
        rawEvents: MutableList<RawEvent>,
        tempoChanges: MutableList<TempoChange>,
    ) {
        var tick = 0L
        var runningStatus = 0

        while (reader.position < trackEnd) {
            tick += reader.readVarLen()

            var statusByte = reader.peekByte()
            if (statusByte and 0x80 == 0) {
                // Running status: reuse the previous status byte, this byte is the first data byte.
                statusByte = runningStatus
            } else {
                reader.readByte()
                runningStatus = statusByte
            }

            when {
                statusByte == 0xFF -> {
                    val metaType = reader.readByte()
                    val length = reader.readVarLen().toInt()
                    if (metaType == 0x51 && length == 3) {
                        val b0 = reader.readByte()
                        val b1 = reader.readByte()
                        val b2 = reader.readByte()
                        val usPerQuarter = (b0 shl 16) or (b1 shl 8) or b2
                        tempoChanges += TempoChange(tick, usPerQuarter)
                    } else {
                        reader.skip(length)
                    }
                }
                statusByte == 0xF0 || statusByte == 0xF7 -> {
                    val length = reader.readVarLen().toInt()
                    reader.skip(length)
                }
                else -> {
                    val channel = statusByte and 0x0F
                    when (statusByte and 0xF0) {
                        0x80 -> {
                            val note = reader.readByte()
                            val velocity = reader.readByte()
                            rawEvents += RawEvent(tick, channel, TimedEvent.Type.NOTE_OFF, note, velocity)
                        }
                        0x90 -> {
                            val note = reader.readByte()
                            val velocity = reader.readByte()
                            // A Note On with velocity 0 is a standard shorthand for Note Off (lets
                            // running-status-heavy encoders avoid ever sending an explicit 0x80).
                            val type = if (velocity == 0) TimedEvent.Type.NOTE_OFF else TimedEvent.Type.NOTE_ON
                            rawEvents += RawEvent(tick, channel, type, note, velocity)
                        }
                        0xA0 -> reader.skip(2) // Poly (key) pressure
                        0xB0 -> reader.skip(2) // Control Change
                        0xC0 -> {
                            val program = reader.readByte()
                            rawEvents += RawEvent(tick, channel, TimedEvent.Type.PROGRAM_CHANGE, program, 0)
                        }
                        0xD0 -> reader.skip(1) // Channel pressure
                        0xE0 -> reader.skip(2) // Pitch bend
                        else -> error("Unrecognized status byte 0x${statusByte.toString(16)}")
                    }
                }
            }
        }
    }

    /** Cursor over a MIDI file's bytes, with unsigned-byte access and big-endian/VLQ readers. */
    private class ByteReader(private val bytes: ByteArray) {
        var position: Int = 0

        fun readByte(): Int = bytes[position++].toInt() and 0xFF
        fun peekByte(): Int = bytes[position].toInt() and 0xFF
        fun skip(count: Int) { position += count }

        fun readFourCC(): String {
            val chars = CharArray(4) { readByte().toChar() }
            return String(chars)
        }

        fun readInt16(): Int = (readByte() shl 8) or readByte()
        fun readInt32(): Int = (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

        fun readVarLen(): Long {
            var value = 0L
            while (true) {
                val b = readByte()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) break
            }
            return value
        }
    }
}
