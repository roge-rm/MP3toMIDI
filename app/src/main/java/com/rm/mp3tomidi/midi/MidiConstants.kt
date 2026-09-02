package com.rm.mp3tomidi.midi

object MidiConstants {
    const val TICKS_PER_QUARTER_NOTE = 480
    const val DEFAULT_BPM = 120
    /** Channel 10 (1-indexed) / index 9 is reserved by the GM spec for percussion. */
    const val DRUM_CHANNEL = 9
}
