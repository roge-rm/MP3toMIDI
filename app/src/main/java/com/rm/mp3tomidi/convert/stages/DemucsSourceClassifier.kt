package com.rm.mp3tomidi.convert.stages

import com.rm.mp3tomidi.midi.GmInstrument

/**
 * htdemucs_6s's 6 stems already tell us the instrument category, so mapping to General MIDI
 * is a direct lookup rather than a learned timbre classifier.
 */
class DemucsSourceClassifier : InstrumentClassifier {
    override fun classify(stem: RawStem, notes: List<NoteEvent>): Stem {
        val (program, isDrumKit) = GM_BY_SOURCE[stem.label] ?: (GmInstrument.ACOUSTIC_GRAND_PIANO to false)
        return Stem(label = stem.label, gmProgram = program, isDrumKit = isDrumKit, notes = notes)
    }

    companion object {
        private val GM_BY_SOURCE: Map<String, Pair<Int, Boolean>> = mapOf(
            "drums" to (GmInstrument.STANDARD_DRUM_KIT to true),
            "bass" to (GmInstrument.ELECTRIC_BASS_FINGER to false),
            "vocals" to (GmInstrument.LEAD_VOICE to false),
            "guitar" to (GmInstrument.ACOUSTIC_GUITAR_STEEL to false),
            "piano" to (GmInstrument.ACOUSTIC_GRAND_PIANO to false),
            "other" to (GmInstrument.ACOUSTIC_GRAND_PIANO to false),
        )
    }
}
