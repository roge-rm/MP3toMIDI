package com.rm.mp3tomidi.convert.stages

import com.rm.mp3tomidi.midi.GmInstrument

/** Identifies a separated stem's timbre and maps it to the closest General MIDI program. */
interface InstrumentClassifier {
    fun classify(stem: RawStem, notes: List<NoteEvent>): Stem
}

/**
 * Placeholder used until a real timbre classifier (a small CNN over mel-spectrograms, trained on
 * e.g. NSynth/IRMAS) is wired in. Maps every stem to Acoustic Grand Piano.
 */
class PlaceholderInstrumentClassifier : InstrumentClassifier {
    override fun classify(stem: RawStem, notes: List<NoteEvent>): Stem {
        return Stem(
            label = stem.label,
            gmProgram = GmInstrument.ACOUSTIC_GRAND_PIANO,
            isDrumKit = false,
            notes = notes,
        )
    }
}
