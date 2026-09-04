package com.rm.mp3tomidi.convert.stages

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Port of basic_pitch.note_creation's onset/frame-activation-matrix -> note-event decoder
 * (get_infered_onsets + output_to_notes_polyphonic, including the melodia trick + model_frames_to_time),
 * verified against the real Python implementation's output on a fixed synthetic input (see
 * BasicPitchNoteDecoderTest) rather than derived from reading the algorithm description alone.
 *
 * Deliberately not ported: pitch bends -- our MIDI writer has no representation for them, and
 * they don't fit the single-pitch-per-note NoteEvent model anyway.
 *
 * The melodia trick only adds notes from energy the onset-based pass never claimed (a real
 * pitched tone that never had a clean attack, e.g. a bowed or synth-pad entrance); it does not
 * merge or otherwise fix notes the onset pass already fragmented into several short ones for a
 * single sustained tone. A non-upstream merge step was tried for that and reverted -- see
 * [com.rm.mp3tomidi.convert.stages.BasicPitchTranscriber]'s doc for why.
 */
object BasicPitchNoteDecoder {

    const val MIDI_OFFSET = 21
    const val DEFAULT_FRAME_THRESH = 0.3f
    private const val MAX_FREQ_IDX = 87

    data class RawNote(val startFrame: Int, val endFrame: Int, val pitch: Int, val amplitude: Float)

    /** [frames] and [onsets] are [time][freq] activations in [0, 1], both with 88 freq bins. */
    fun decode(
        frames: Array<FloatArray>,
        onsets: Array<FloatArray>,
        onsetThresh: Float = 0.5f,
        frameThresh: Float = DEFAULT_FRAME_THRESH,
        minNoteLen: Int = 11,
        energyTol: Int = 11,
        inferOnsets: Boolean = true,
        melodiaTrick: Boolean = true,
    ): List<RawNote> {
        val nTimes = frames.size
        if (nTimes == 0) return emptyList()
        val nFreqs = frames[0].size

        val effectiveOnsets = if (inferOnsets) inferOnsets(onsets, frames) else onsets

        // scipy.signal.argrelmax(onsets, axis=0), default order=1, mode='clip': a point is a
        // peak only if strictly greater than both neighbors, and with mode='clip' the "missing"
        // neighbor at each edge is the edge value itself -- so edge points (v > v is false) can
        // never be peaks.
        val peakThreshMat = Array(nTimes) { FloatArray(nFreqs) }
        for (f in 0 until nFreqs) {
            for (t in 0 until nTimes) {
                val v = effectiveOnsets[t][f]
                val prev = effectiveOnsets[max(t - 1, 0)][f]
                val next = effectiveOnsets[min(t + 1, nTimes - 1)][f]
                if (v > prev && v > next) peakThreshMat[t][f] = v
            }
        }

        // np.where on a 2D array walks row-major (time ascending, then freq ascending); the
        // reference implementation then reverses that to process later onsets first. Both the
        // order and the reversal are load-bearing below: remainingEnergy is mutated as we go, so
        // a later-time onset can claim energy that an earlier onset at a neighboring frequency
        // would otherwise have consumed.
        val onsetCandidates = mutableListOf<Pair<Int, Int>>()
        for (t in 0 until nTimes) {
            for (f in 0 until nFreqs) {
                if (peakThreshMat[t][f] >= onsetThresh) onsetCandidates += t to f
            }
        }
        onsetCandidates.reverse()

        val remainingEnergy = Array(nTimes) { t -> frames[t].copyOf() }
        val notes = mutableListOf<RawNote>()

        for ((noteStartIdx, freqIdx) in onsetCandidates) {
            if (noteStartIdx >= nTimes - 1) continue

            var i = noteStartIdx + 1
            var k = 0
            while (i < nTimes - 1 && k < energyTol) {
                if (remainingEnergy[i][freqIdx] < frameThresh) k++ else k = 0
                i++
            }
            i -= k

            if (i - noteStartIdx <= minNoteLen) continue

            for (t in noteStartIdx until i) {
                remainingEnergy[t][freqIdx] = 0f
                if (freqIdx < MAX_FREQ_IDX) remainingEnergy[t][freqIdx + 1] = 0f
                if (freqIdx > 0) remainingEnergy[t][freqIdx - 1] = 0f
            }

            var sum = 0f
            for (t in noteStartIdx until i) sum += frames[t][freqIdx]
            val amplitude = sum / (i - noteStartIdx)

            notes += RawNote(noteStartIdx, i, freqIdx + MIDI_OFFSET, amplitude)
        }

        if (melodiaTrick) {
            while (true) {
                // Global argmax over remainingEnergy, row-major (time outer, freq inner) so ties
                // resolve to the earliest-time/lowest-freq occurrence -- matches np.argmax's
                // tie-breaking on a flattened 2D array exactly (first occurrence in C order).
                var maxVal = Float.NEGATIVE_INFINITY
                var iMid = -1
                var freqIdx = -1
                for (t in 0 until nTimes) {
                    for (f in 0 until nFreqs) {
                        if (remainingEnergy[t][f] > maxVal) {
                            maxVal = remainingEnergy[t][f]
                            iMid = t
                            freqIdx = f
                        }
                    }
                }
                if (maxVal <= frameThresh) break

                remainingEnergy[iMid][freqIdx] = 0f

                var i = iMid + 1
                var k = 0
                while (i < nTimes - 1 && k < energyTol) {
                    if (remainingEnergy[i][freqIdx] < frameThresh) k++ else k = 0
                    remainingEnergy[i][freqIdx] = 0f
                    if (freqIdx < MAX_FREQ_IDX) remainingEnergy[i][freqIdx + 1] = 0f
                    if (freqIdx > 0) remainingEnergy[i][freqIdx - 1] = 0f
                    i++
                }
                val iEnd = i - 1 - k

                i = iMid - 1
                k = 0
                while (i > 0 && k < energyTol) {
                    if (remainingEnergy[i][freqIdx] < frameThresh) k++ else k = 0
                    remainingEnergy[i][freqIdx] = 0f
                    if (freqIdx < MAX_FREQ_IDX) remainingEnergy[i][freqIdx + 1] = 0f
                    if (freqIdx > 0) remainingEnergy[i][freqIdx - 1] = 0f
                    i--
                }
                val iStart = i + 1 + k

                if (iEnd - iStart <= minNoteLen) continue

                var sum = 0f
                for (t in iStart until iEnd) sum += frames[t][freqIdx]
                val amplitude = sum / (iEnd - iStart)

                notes += RawNote(iStart, iEnd, freqIdx + MIDI_OFFSET, amplitude)
            }
        }

        return notes
    }

    private fun inferOnsets(onsets: Array<FloatArray>, frames: Array<FloatArray>): Array<FloatArray> {
        val nTimes = frames.size
        val nFreqs = frames[0].size
        val nDiff = 2

        var frameDiff = Array(nTimes) { FloatArray(nFreqs) }
        for (f in 0 until nFreqs) for (t in 0 until nTimes) frameDiff[t][f] = Float.MAX_VALUE

        for (n in 1..nDiff) {
            for (t in 0 until nTimes) {
                for (f in 0 until nFreqs) {
                    val shifted = if (t - n >= 0) frames[t - n][f] else 0f
                    val diff = frames[t][f] - shifted
                    if (diff < frameDiff[t][f]) frameDiff[t][f] = diff
                }
            }
        }
        for (t in 0 until nTimes) for (f in 0 until nFreqs) if (frameDiff[t][f] < 0f) frameDiff[t][f] = 0f
        for (t in 0 until min(nDiff, nTimes)) for (f in 0 until nFreqs) frameDiff[t][f] = 0f

        var maxOnset = 0f
        for (row in onsets) for (v in row) if (v > maxOnset) maxOnset = v
        var maxDiff = 0f
        for (row in frameDiff) for (v in row) if (v > maxDiff) maxDiff = v

        // The reference implementation divides by max(frame_diff) unconditionally (NaN if it's
        // zero, e.g. a silent stem); we guard it instead since propagating NaN into note
        // decisions would be strictly worse than treating "no energy change anywhere" as "no
        // inferred onset anywhere."
        val scale = if (maxDiff > 0f) maxOnset / maxDiff else 0f

        return Array(nTimes) { t ->
            FloatArray(nFreqs) { f -> max(onsets[t][f], frameDiff[t][f] * scale) }
        }
    }

    /** Seconds for each of [nFrames] model output frames, matching basic_pitch's window-edge correction. */
    fun modelFramesToTime(nFrames: Int): DoubleArray {
        val sampleRate = 22050.0
        val hop = 256.0
        val annotNFrames = 172.0
        val audioNSamples = 43844.0
        val windowOffset = (hop / sampleRate) * (annotNFrames - (audioNSamples / hop)) + 0.0018
        return DoubleArray(nFrames) { i ->
            val originalTime = i * hop / sampleRate
            val windowNumber = floor(i / annotNFrames)
            originalTime - windowOffset * windowNumber
        }
    }
}
