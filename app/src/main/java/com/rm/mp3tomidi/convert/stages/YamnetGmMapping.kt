package com.rm.mp3tomidi.convert.stages

import com.rm.mp3tomidi.midi.GmInstrument

/**
 * Maps a subset of YAMNet's 521 AudioSet classes (see tools/yamnet_export/yamnet_class_map.csv)
 * to the closest General MIDI program, for [TimbreClassifier]. Deliberately only covers classes
 * that are actual musical instruments/voices -- generic classes like "Music", "Musical
 * instrument", genre tags, or non-instrument sounds are left unmapped on purpose, so they're
 * simply skipped by [pickBestMatch] rather than winning by default when nothing more specific
 * clears the confidence bar.
 *
 * Several choices here don't have one obviously-correct GM program (AudioSet's ontology is much
 * finer-grained in places than GM1's 128 programs, and coarser in others):
 * - "Acoustic guitar"/"Guitar"/generic plucked-string classes default to steel-string (25) over
 *   nylon (24), since steel is more common outside classical/flamenco contexts; only the
 *   "Ukulele" class (a small nylon-strung instrument) gets the nylon program.
 * - "Organ"/"Electronic organ"/"Hammond organ" all map to Drawbar Organ (16) -- GM's own name
 *   for that program already describes a Hammond-style drawbar organ, so there's no meaningful
 *   distinction to preserve here.
 * - Percussion classes without a real GM melodic-percussion equivalent (drum kit, snare, bass
 *   drum, cymbal, hi-hat, tambourine, maraca, generic rattle) map to the drum kit rather than a
 *   melodic program -- if a non-"drums" stem confidently reads as one of these, that's Demucs
 *   bleeding percussion into the wrong bucket, not a melodic instrument to misassign a pitch to.
 * - Instruments with no GM equivalent at all (didgeridoo, shofar, theremin) map to the closest
 *   timbral cousin (tuba, French horn, and Lead 6 (voice) respectively) rather than being left
 *   unmapped, since "closest available" is exactly what this whole feature is trying to do.
 */
object YamnetGmMapping {

    data class GmMatch(
        val gmProgram: Int,
        val isDrumKit: Boolean = false,
        val isGenericSynth: Boolean = false,
        /** Set by [pickBestMatch] to the winning class's real score; 0f on every literal above. */
        val score: Float = 0f,
    )

    val BY_CLASS_INDEX: Map<Int, GmMatch> = buildMap {
        // Singing/vocal classes -- refines the vocals stem's default beyond a fixed Lead Voice.
        put(24, GmMatch(GmInstrument.LEAD_VOICE)) // Singing
        put(25, GmMatch(52)) // Choir -> Choir Aahs
        put(26, GmMatch(GmInstrument.LEAD_VOICE)) // Yodeling
        put(27, GmMatch(52)) // Chant -> Choir Aahs
        put(28, GmMatch(52)) // Mantra -> Choir Aahs
        put(29, GmMatch(GmInstrument.LEAD_VOICE)) // Child singing
        put(30, GmMatch(54)) // Synthetic singing -> Synth Voice

        // Plucked strings
        put(134, GmMatch(GmInstrument.ACOUSTIC_GUITAR_STEEL)) // Plucked string instrument
        put(135, GmMatch(GmInstrument.ACOUSTIC_GUITAR_STEEL)) // Guitar
        put(136, GmMatch(27)) // Electric guitar -> Electric Guitar (clean)
        put(137, GmMatch(GmInstrument.ELECTRIC_BASS_FINGER)) // Bass guitar
        put(138, GmMatch(GmInstrument.ACOUSTIC_GUITAR_STEEL)) // Acoustic guitar
        put(139, GmMatch(GmInstrument.ACOUSTIC_GUITAR_STEEL)) // Steel guitar, slide guitar
        put(140, GmMatch(27)) // Tapping (guitar technique) -> Electric Guitar (clean)
        put(141, GmMatch(GmInstrument.ACOUSTIC_GUITAR_STEEL)) // Strum
        put(142, GmMatch(105)) // Banjo
        put(143, GmMatch(104)) // Sitar
        put(144, GmMatch(105)) // Mandolin -> Banjo (closest bright plucked string)
        put(145, GmMatch(107)) // Zither -> Koto
        put(146, GmMatch(GmInstrument.ACOUSTIC_GUITAR_NYLON)) // Ukulele

        // Keys
        put(147, GmMatch(GmInstrument.ACOUSTIC_GRAND_PIANO)) // Keyboard (musical)
        put(148, GmMatch(GmInstrument.ACOUSTIC_GRAND_PIANO)) // Piano
        put(149, GmMatch(4)) // Electric piano -> Electric Piano 1
        put(150, GmMatch(16)) // Organ -> Drawbar Organ
        put(151, GmMatch(16)) // Electronic organ -> Drawbar Organ
        put(152, GmMatch(16)) // Hammond organ -> Drawbar Organ
        // "Synthesizer" is too generic to commit to a fixed program the way a specific instrument
        // class can -- a synth could be playing a bassline, a pad, or a plucked arp, each of
        // which wants a different GM program. Flagged so TimbreClassifier refines this via
        // NoteEnvelopeClassifier (note shape) instead of taking it at face value.
        put(153, GmMatch(81, isGenericSynth = true)) // Synthesizer -> Lead 2 (sawtooth), refined
        put(155, GmMatch(6)) // Harpsichord

        // Percussion without a melodic GM equivalent -> the drum kit
        put(157, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Drum kit
        put(158, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Drum machine
        put(159, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Drum
        put(160, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Snare drum
        put(161, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Rimshot
        put(162, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Drum roll
        put(163, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Bass drum
        put(166, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Cymbal
        put(167, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Hi-hat
        put(169, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Tambourine
        put(170, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Rattle (instrument)
        put(171, GmMatch(GmInstrument.STANDARD_DRUM_KIT, isDrumKit = true)) // Maraca

        // Pitched/melodic percussion
        put(164, GmMatch(47)) // Timpani
        put(168, GmMatch(115)) // Wood block -> Woodblock
        put(173, GmMatch(14)) // Tubular bells
        put(174, GmMatch(11)) // Mallet percussion -> Vibraphone
        put(175, GmMatch(12)) // Marimba, xylophone -> Marimba
        put(176, GmMatch(9)) // Glockenspiel
        put(177, GmMatch(11)) // Vibraphone
        put(178, GmMatch(114)) // Steelpan -> Steel Drums

        // Orchestral / brass / bowed strings / woodwinds
        put(179, GmMatch(48)) // Orchestra -> String Ensemble 1
        put(180, GmMatch(61)) // Brass instrument -> Brass Section
        put(181, GmMatch(60)) // French horn
        put(182, GmMatch(GmInstrument.TRUMPET))
        put(183, GmMatch(57)) // Trombone
        put(184, GmMatch(GmInstrument.VIOLIN)) // Bowed string instrument
        put(185, GmMatch(48)) // String section -> String Ensemble 1
        put(186, GmMatch(GmInstrument.VIOLIN)) // Violin, fiddle
        put(187, GmMatch(45)) // Pizzicato -> Pizzicato Strings
        put(188, GmMatch(42)) // Cello
        put(189, GmMatch(32)) // Double bass -> Acoustic Bass
        put(190, GmMatch(GmInstrument.FLUTE)) // Wind instrument, woodwind instrument
        put(191, GmMatch(GmInstrument.FLUTE))
        put(192, GmMatch(GmInstrument.ALTO_SAX)) // Saxophone
        put(193, GmMatch(71)) // Clarinet
        put(194, GmMatch(46)) // Harp -> Orchestral Harp

        // Bells/chimes
        put(195, GmMatch(14)) // Bell -> Tubular Bells
        put(196, GmMatch(14)) // Church bell -> Tubular Bells
        put(197, GmMatch(112)) // Jingle bell -> Tinkle Bell
        put(200, GmMatch(14)) // Chime -> Tubular Bells
        put(201, GmMatch(112)) // Wind chime -> Tinkle Bell
        put(202, GmMatch(14)) // Change ringing (campanology) -> Tubular Bells

        // Reeds/world/misc
        put(203, GmMatch(22)) // Harmonica
        put(204, GmMatch(21)) // Accordion
        put(205, GmMatch(109)) // Bagpipes -> Bag pipe
        put(206, GmMatch(58)) // Didgeridoo -> Tuba (closest low wind drone)
        put(207, GmMatch(60)) // Shofar -> French Horn (closest horn-family instrument)
        put(208, GmMatch(GmInstrument.LEAD_VOICE)) // Theremin -> Lead 6 (voice)
    }

    /**
     * The highest-scoring mapped class that clears [threshold], or null if nothing does -- callers
     * should fall back to a label-based default in that case rather than guess from noise.
     */
    fun pickBestMatch(meanScores: FloatArray, threshold: Float): GmMatch? {
        var bestMatch: GmMatch? = null
        var bestScore = threshold
        for ((index, match) in BY_CLASS_INDEX) {
            val score = meanScores.getOrElse(index) { 0f }
            if (score >= bestScore) {
                bestScore = score
                bestMatch = match.copy(score = score)
            }
        }
        return bestMatch
    }
}
