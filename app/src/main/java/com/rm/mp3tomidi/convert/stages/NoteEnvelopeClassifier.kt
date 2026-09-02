package com.rm.mp3tomidi.convert.stages

import com.rm.mp3tomidi.midi.MidiConstants

/**
 * Infers what role a synth voice is playing in the arrangement -- bass, sustained pad, melodic
 * lead, or a short rhythmic pluck/arpeggio -- purely from the shape of its already-transcribed
 * notes (duration, legato vs. staccato spacing, register), and maps that role to a GM program.
 *
 * This exists because YAMNet (see [TimbreClassifier]) is trained on AudioSet, which is
 * real-world/acoustic-instrument-heavy: on a real electronic song, every non-drum stem's YAMNet
 * confidence stayed below the classification threshold (verified against a real Röyksopp track --
 * even a clearly-present synth bass line topped out at 0.048 on the "Synthesizer" class, just
 * under the 0.05 bar), so it never distinguishes a sustained pad from a plucked arpeggio from a
 * bassline the way it reliably distinguishes a guitar from a piano. Note envelope shape is a
 * genre-agnostic, YAMNet-independent signal that's already fully computed by the time a classifier
 * runs (no extra audio processing needed) and speaks directly to compositional role, which is
 * exactly what's missing when timbre recognition itself comes up empty.
 *
 * Checked against real note data from four electronic songs (Röyksopp, ODESZA, Netsky, on top of
 * the original calibration track) before settling on the current thresholds -- two real findings
 * changed the design from its first pass:
 *
 * - Register uses median, not mean, pitch (see below).
 * - Sustained pad content does *not* show up as one long note the way a synth patch played on a
 *   keyboard might suggest -- across every song checked, notes landing in this bucket clustered at
 *   just 0.2-0.45s each, and the original design (requiring both long duration *and* high legato)
 *   never fired once, on any of them. What was consistent instead: legato alone. 6 of 7 real
 *   non-bass, non-vocals stems checked had a legato ratio of 0.6+ -- Basic Pitch's polyphonic
 *   transcription apparently represents a continuously-evolving pad texture as a dense chain of
 *   short, tightly back-to-back notes rather than a single sustained one, so duration turns out to
 *   be the wrong axis to gate on; legato alone is what actually distinguishes it. Dropped the
 *   duration requirement accordingly.
 *
 * The pluck threshold hasn't been exercised by any real data yet (no song checked so far had
 * genuinely staccato content land in one of these buckets) -- still an untested starting point.
 */
object NoteEnvelopeClassifier {

    enum class Role(val gmProgram: Int) {
        BASS(38), // Synth Bass 1
        SUSTAINED_PAD(89), // Pad 2 (warm)
        RHYTHMIC_PLUCK(80), // Lead 1 (square) -- bright, simple waveform typical of plucked arps
        LEAD(81), // Lead 2 (sawtooth)
    }

    private const val MIN_NOTES = 4
    private const val BASS_PITCH_MAX = 48 // C3 and below reads as a bassline regardless of shape
    private const val LEGATO_GAP_MAX_SECONDS = 0.05
    private const val PAD_LEGATO_MIN = 0.6
    private const val PLUCK_DURATION_MAX_SECONDS = 0.25
    private const val PLUCK_LEGATO_MAX = 0.3

    /** Null when there aren't enough notes to say anything meaningful about shape. */
    fun classify(notes: List<NoteEvent>, bpm: Int): Role? {
        if (notes.size < MIN_NOTES) return null

        // Median, not mean: real synth bass audio produced a lot of extra higher-pitched notes
        // in testing (Basic Pitch picking up harmonic overtones as separate notes on
        // harmonically-rich bass timbres), which pulled the *mean* pitch up into the mid-register
        // -- a bass stem with 1780 transcribed notes averaged out to MIDI 59.7, nowhere near bass
        // range. The median is far less sensitive to that kind of one-sided tail.
        val medianPitch = notes.map { it.pitch }.sorted().let { sorted ->
            val mid = sorted.size / 2
            if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
        }
        if (medianPitch <= BASS_PITCH_MAX) return Role.BASS

        val ticksPerSecond = MidiConstants.TICKS_PER_QUARTER_NOTE.toDouble() * bpm / 60.0
        val sorted = notes.sortedBy { it.startTick }
        val durationsSeconds = sorted.map { (it.endTick - it.startTick) / ticksPerSecond }
        val meanDurationSeconds = durationsSeconds.average()

        var legatoCount = 0
        for (i in 0 until sorted.size - 1) {
            val gapSeconds = (sorted[i + 1].startTick - sorted[i].endTick) / ticksPerSecond
            if (gapSeconds <= LEGATO_GAP_MAX_SECONDS) legatoCount++
        }
        val legatoRatio = legatoCount.toDouble() / (sorted.size - 1)

        return when {
            legatoRatio >= PAD_LEGATO_MIN -> Role.SUSTAINED_PAD
            meanDurationSeconds <= PLUCK_DURATION_MAX_SECONDS && legatoRatio <= PLUCK_LEGATO_MAX -> Role.RHYTHMIC_PLUCK
            else -> Role.LEAD
        }
    }
}
