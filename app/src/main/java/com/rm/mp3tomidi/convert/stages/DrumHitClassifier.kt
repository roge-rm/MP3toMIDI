package com.rm.mp3tomidi.convert.stages

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Heuristic drum-hit classifier: a handful of GM percussion voices distinguished by FFT band-power
 * ratios and decay shape on a short window after each onset. Unlike DemucsStemSeparator/
 * BasicPitchTranscriber, there's no upstream reference implementation or training data behind
 * this -- it's a hand-tuned heuristic meant to give a plausible-sounding drum part, not an
 * accurate transcription. Real per-hit drum classification is a research problem in its own
 * right; this is the pragmatic on-device subset.
 *
 * An earlier version measured "low-frequency energy" via a single-pole lowpass filter's own RMS
 * output. That doesn't work: a one-pole filter only rolls off at -6dB/octave, so a "150Hz
 * lowpass" still passes a large fraction of energy from content centered hundreds of Hz above its
 * cutoff. Verified against a real drum stem (FFT ground truth on captured onset windows): hits
 * with only ~10% of their spectral power below 150Hz (centroid ~300-1200Hz -- textbook snare
 * body/wires) still showed a one-pole "low ratio" of 0.53-0.65, comfortably over the old 0.5
 * threshold, which is why that version classified 91% of a real song's drum hits as kick with
 * zero hi-hats. Measuring the same ratio via actual FFT bin power fixed it (340/572 kick vs.
 * 522/572 before, on the same audio) and produces a clearly bimodal low-band-power distribution
 * (most hits are either <25% or >85% low-band power, not smeared across the middle the way the
 * leaky filter's output was).
 *
 * The closed-vs-open/crash decay check below went through the same real-audio-invalidation
 * process. The first version measured early/late RMS within the same fixed 50ms window used for
 * band-power classification, calibrated only against a synthetic 5ms-time-constant "closed" burst
 * -- checked against real cymbal-family hits from three very different songs (jungle, rock,
 * electronic) and found *zero* of 186 hits ever classified as closed, with decay ratios clustering
 * at 0.4-1.5 regardless of genre: a real closed hi-hat's decay is nowhere near as fast as 5ms, and
 * a fixed 50ms window is too short to see the actual divergence from a genuinely sustained
 * open/crash hit. Widening the window naively introduced a worse confound: with hits as little as
 * 60ms apart (see DrumOnsetDetector's minIntervalMs), a longer fixed window routinely bled into
 * the *next* onset, at one point producing a "late" RMS higher than "early" (a subsequent, louder
 * hit landing inside what was supposed to be this hit's decay tail). Capping the decay window at
 * whichever comes first -- [MAX_DECAY_WINDOW_SECONDS] or the next onset -- fixed both problems at
 * once and produces a genre-plausible, non-degenerate split on all three real songs (e.g. ~95%
 * closed on a steady rock hi-hat pattern vs. a more even mix on a sparser electronic track),
 * instead of the same uniform all-or-nothing answer regardless of what's actually playing.
 *
 * [Voice.CRASH_OR_OPEN_HI_HAT]'s GM mapping was itself a real bug, reported by a user listening
 * to real converted output ("way too many audible crashes... instead of open hats and closed
 * hats I am hearing closed hats and crashes"). It originally mapped to GM 49 (Crash Cymbal 1) --
 * see that enum entry's doc for why open hi-hat (46) is the right default instead.
 */
object DrumHitClassifier {

    enum class Voice(val gmPitch: Int) {
        KICK(36),
        SNARE(38),
        CLOSED_HI_HAT(42),
        // Genuinely ambiguous from decay shape alone (see class doc) between two GM voices that
        // sound very different: a true crash is a rare accent hit, while an open hi-hat is a
        // routine, frequent part of most grooves. Mapped to open hi-hat (46), not crash (49) --
        // measured on a real song (Kraak & Smaak "I Don't Know Why"): this bucket fired on 22% of
        // *all* drum hits, an implausible rate for genuine crashes but an ordinary one for open
        // hi-hats. Defaulting to crash instead (the original mapping) made every one of those
        // audible as a crash cymbal, confirmed as a real user-reported bug ("way too many audible
        // crashes... instead of open hats and closed hats I am hearing closed hats and crashes").
        CRASH_OR_OPEN_HI_HAT(46),
    }

    private const val LOW_CUTOFF_HZ = 150f
    private const val HIGH_CUTOFF_HZ = 6000f
    private const val WINDOW_SECONDS = 0.05f
    private const val EARLY_WINDOW_SECONDS = 0.02f
    private const val MAX_DECAY_WINDOW_SECONDS = 0.15f

    // Thresholds calibrated against FFT band-power ratios measured on a real separated drum
    // stem: low-band power is clearly bimodal (most hits <0.25 or >0.85, few in between), and
    // high-band power rarely exceeds ~0.1 even for hits with audible high-frequency content, so
    // it doesn't take much to flag genuine cymbal-dominant hits.
    private const val LOW_RATIO_KICK_THRESHOLD = 0.55f
    private const val HIGH_RATIO_CYMBAL_THRESHOLD = 0.15f
    private const val ZCR_NOISE_MIN = 0.15f
    private const val DECAY_RATIO_CLOSED_MAX = 0.4f

    /**
     * [nextOnsetSample] bounds the closed-vs-open decay measurement so it never bleeds into a
     * subsequent hit -- defaults to the end of [mono] (no bound) for isolated bursts, e.g. in
     * tests.
     */
    fun classify(mono: FloatArray, onsetSample: Int, sampleRate: Int, nextOnsetSample: Int = mono.size): Voice {
        val windowEnd = min(mono.size, onsetSample + (WINDOW_SECONDS * sampleRate).toInt())
        if (onsetSample >= windowEnd) return Voice.SNARE
        val window = mono.copyOfRange(onsetSample, windowEnd)

        val totalRms = AudioFilters.rms(window)
        if (totalRms <= 0f) return Voice.SNARE

        val (lowRatio, highRatio) = bandPowerRatios(window, sampleRate, LOW_CUTOFF_HZ, HIGH_CUTOFF_HZ)

        if (lowRatio >= LOW_RATIO_KICK_THRESHOLD) return Voice.KICK

        if (highRatio >= HIGH_RATIO_CYMBAL_THRESHOLD && AudioFilters.zeroCrossingRate(window) >= ZCR_NOISE_MIN) {
            val decayEnd = minOf(mono.size, onsetSample + (MAX_DECAY_WINDOW_SECONDS * sampleRate).toInt(), nextOnsetSample)
            val earlyEnd = minOf(decayEnd, onsetSample + (EARLY_WINDOW_SECONDS * sampleRate).toInt())
            val early = AudioFilters.rms(mono, onsetSample, earlyEnd)
            val late = AudioFilters.rms(mono, earlyEnd, decayEnd)
            return if (early > 0f && late / early < DECAY_RATIO_CLOSED_MAX) {
                Voice.CLOSED_HI_HAT
            } else {
                Voice.CRASH_OR_OPEN_HI_HAT
            }
        }

        return Voice.SNARE
    }

    /** Fraction of [window]'s spectral power below [lowCutoffHz] and at/above [highCutoffHz]. */
    private fun bandPowerRatios(
        window: FloatArray,
        sampleRate: Int,
        lowCutoffHz: Float,
        highCutoffHz: Float,
    ): Pair<Float, Float> {
        var size = 1
        while (size < window.size) size = size shl 1

        val re = FloatArray(size)
        val im = FloatArray(size)
        val n = window.size
        for (i in window.indices) {
            val hann = if (n > 1) 0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat() else 1f
            re[i] = window[i] * hann
        }
        Fft.transform(re, im)

        val binHz = sampleRate.toFloat() / size
        var lowPower = 0.0
        var highPower = 0.0
        var totalPower = 0.0
        for (k in 0..size / 2) {
            val power = (re[k] * re[k] + im[k] * im[k]).toDouble()
            totalPower += power
            val freq = k * binHz
            if (freq < lowCutoffHz) lowPower += power
            if (freq >= highCutoffHz) highPower += power
        }
        if (totalPower <= 0.0) return 0f to 0f
        return (lowPower / totalPower).toFloat() to (highPower / totalPower).toFloat()
    }
}
