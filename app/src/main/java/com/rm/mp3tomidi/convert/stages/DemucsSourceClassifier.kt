package com.rm.mp3tomidi.convert.stages

import android.content.Context
import com.rm.mp3tomidi.midi.GmInstrument

/**
 * Fixed lookup from a Demucs stem label to a single default GM program, ignoring the stem's
 * actual audio content. Used directly for the drums stem (Demucs's own label is already the
 * ground truth there) and as [TimbreClassifier]'s fallback when no real timbre classification
 * clears its confidence threshold.
 */
class DemucsSourceClassifier : InstrumentClassifier {
    override suspend fun classify(context: Context, stem: RawStem, notes: List<NoteEvent>): Stem {
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
