package com.rm.mp3tomidi.convert.stages

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.rm.mp3tomidi.midi.MidiConstants
import com.rm.mp3tomidi.util.PcmUtils
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Polyphonic note transcription via Spotify's Basic Pitch (ICASSP 2022) model. The exported
 * ONNX file is only ~230KB (unlike Demucs' 235MB), so it's bundled directly in assets rather
 * than downloaded on first use -- see tools/basic_pitch_export/.
 *
 * Windowing follows basic_pitch.inference.run_inference: fixed-length windows with a 30-frame
 * overlap, the middle 142 frames of each window's output kept and concatenated. Note decoding
 * is BasicPitchNoteDecoder (see its doc for what's intentionally not ported).
 *
 * Not suitable for drums -- Basic Pitch assumes pitched input. See CompositeNoteTranscriber.
 *
 * **No note-merging step, after trying one and reverting it.** Basic Pitch's onset loop starts a
 * brand-new note at *every* qualifying local peak in the onset-activation matrix, and a single
 * sustained real-world tone can produce more than one such peak (vibrato, a dynamic swell,
 * harmonic beating) even though there was only ever one attack -- this really does happen and
 * really does sound "busy." A same-pitch-small-gap merge was added to fix it, then capped at 2
 * originals per merge after it was found to also glue genuine fast repeated-pitch passages
 * (tremolo, arpeggio, a plucked synth line) into implausible long notes, then reverted entirely
 * after the cap turned out not to actually solve the underlying problem: checked across 4 real
 * songs and both vocals *and* bass -- not just guitar/synth stems -- 11-22% of real notes (per the
 * real upstream reference decoder run directly on isolated stems) sat in small-gap same-pitch runs
 * that the merge would combine, and ~85-90% of those had *exactly zero* measured gap regardless of
 * stem type. There's no per-instrument split to hang a policy on: real melodic content of every
 * kind routinely produces the identical near-zero-gap signature this would need to target, so
 * *any* gap-based merge -- capped, uncapped, or instrument-conditional -- ends up eating real
 * notes about as often as it fixes fragmented ones. Left as a known, unresolved tradeoff rather
 * than a heuristic that doesn't actually pay for itself.
 *
 * **Velocity is derived from real audio loudness, not `RawNote.amplitude`** (fixed after a user
 * reported inconsistent track volumes): `amplitude` is Basic Pitch's own note-activation
 * confidence score, not a loudness measurement, and it doesn't behave like one -- measured on 5
 * real songs, its correlation with the note's actual audio RMS ranged from -0.24 to 0.82 (mostly
 * weak or nonexistent), and every stem's velocity distribution looked similar (~55-71 average)
 * regardless of the stem's real loudness, which spanned a >400x range (a near-silent noise-floor
 * "piano" stem got velocities in the same range as the loudest stem in the mix). Fixed the same
 * way [DrumTranscriber] already computes drum velocity: each note's peak amplitude over its own span,
 * relative to its stem's peak amplitude (see [velocityFor] -- an *earlier* version of this fix
 * used RMS-of-note-span instead, and that was itself a real, verified-on-device bug: RMS averages
 * energy over time while peak is a single instantaneous extreme, and a whole track's peak sample
 * is typically 5-10x even a genuinely loud one-second span's RMS -- comparing the two crushed
 * nearly every note down near the velocity floor regardless of real loudness, the exact same
 * *shape* of bug this change was supposed to fix, just introduced fresh). See
 * [com.rm.mp3tomidi.convert.ConversionPipeline] for the further cross-stem balancing this alone
 * doesn't address (a stem-relative velocity is still only correct *within* that one stem).
 */
class BasicPitchTranscriber : NoteTranscriber {

    override suspend fun transcribe(context: Context, stem: RawStem, bpm: Int): List<NoteEvent> {
        val monoAudio = loadAsMono22050(stem)
        if (monoAudio.isEmpty()) return emptyList()

        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
        val session = env.createSession(modelBytes, OrtSession.SessionOptions())

        val onsetWindows = mutableListOf<Array<FloatArray>>()
        val noteWindows = mutableListOf<Array<FloatArray>>()
        try {
            for (window in windows(monoAudio)) {
                val (onset, note) = runWindow(session, env, window)
                onsetWindows += trimWindowEdges(onset)
                noteWindows += trimWindowEdges(note)
            }
        } finally {
            session.close()
        }

        val totalFrames = floor(monoAudio.size.toDouble() * ANNOTATIONS_FPS / SAMPLE_RATE).toInt()
        val onsets = concatAndTrim(onsetWindows, totalFrames)
        val frames = concatAndTrim(noteWindows, totalFrames)

        val rawNotes = BasicPitchNoteDecoder.decode(frames, onsets)
        val times = BasicPitchNoteDecoder.modelFramesToTime(totalFrames)
        val peakAmplitude = AudioFilters.peak(monoAudio).coerceAtLeast(MIN_AMPLITUDE)

        return rawNotes.map { note ->
            val startSeconds = times.getOrElse(note.startFrame) { times.lastOrNull() ?: 0.0 }
            val endSeconds = times.getOrElse(min(note.endFrame, times.size - 1)) { times.lastOrNull() ?: 0.0 }
            val startSample = (startSeconds * SAMPLE_RATE).roundToInt().coerceIn(0, monoAudio.size)
            val endSample = (endSeconds * SAMPLE_RATE).roundToInt().coerceIn(startSample, monoAudio.size)
            NoteEvent(
                startTick = secondsToTicks(startSeconds, bpm),
                endTick = secondsToTicks(endSeconds, bpm),
                pitch = note.pitch,
                velocity = velocityFor(monoAudio, startSample, endSample, peakAmplitude),
            )
        }
    }

    /**
     * Peak amplitude of the real audio over one note's span, relative to its stem's peak -- same
     * peak-to-peak comparison [DrumTranscriber] uses, and deliberately not RMS-of-note-vs-peak-
     * of-stem: those are different statistics (RMS averages energy over time, peak is a single
     * instantaneous extreme), and mixing them was a real bug found on real audio -- a whole
     * track's peak sample is typically 5-10x even a genuinely loud one-second span's RMS, which
     * crushed nearly every note's velocity down near the floor regardless of how loud it actually
     * was.
     */
    internal fun velocityFor(monoAudio: FloatArray, startSample: Int, endSample: Int, peakAmplitude: Float): Int {
        val noteLoudness = AudioFilters.peak(monoAudio, startSample, endSample)
        return (127f * (noteLoudness / peakAmplitude)).roundToInt().coerceIn(MIN_VELOCITY, 127)
    }

    private fun loadAsMono22050(stem: RawStem): FloatArray {
        val raw = PcmUtils.readInterleavedPcm(stem.pcmFile)
        val mono = PcmUtils.remixChannels(raw, stem.channelCount, 1)
        return PcmUtils.resampleLinear(mono, 1, stem.sampleRate, SAMPLE_RATE)
    }

    /** Pads by half the overlap at the start, then slices into fixed AUDIO_N_SAMPLES windows. */
    private fun windows(audio: FloatArray): List<FloatArray> {
        val overlapLen = N_OVERLAPPING_FRAMES * FFT_HOP
        val padded = FloatArray(overlapLen / 2 + audio.size)
        System.arraycopy(audio, 0, padded, overlapLen / 2, audio.size)

        val hopSize = AUDIO_N_SAMPLES - overlapLen
        val result = mutableListOf<FloatArray>()
        var i = 0
        while (i < padded.size) {
            val window = FloatArray(AUDIO_N_SAMPLES)
            val available = min(AUDIO_N_SAMPLES, padded.size - i)
            System.arraycopy(padded, i, window, 0, available)
            result += window
            i += hopSize
        }
        return result
    }

    private fun runWindow(session: OrtSession, env: OrtEnvironment, window: FloatArray): Pair<Array<FloatArray>, Array<FloatArray>> {
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, AUDIO_N_SAMPLES.toLong(), 1))
        return inputTensor.use {
            session.run(mapOf(INPUT_NAME to it), setOf(ONSET_OUTPUT_NAME, NOTE_OUTPUT_NAME)).use { result ->
                val onset = extract2d(result.get(ONSET_OUTPUT_NAME).get() as OnnxTensor)
                val note = extract2d(result.get(NOTE_OUTPUT_NAME).get() as OnnxTensor)
                onset to note
            }
        }
    }

    private fun extract2d(tensor: OnnxTensor): Array<FloatArray> {
        val buffer = tensor.floatBuffer
        return Array(ANNOT_N_FRAMES) { r -> FloatArray(N_FREQ_BINS) { c -> buffer.get(r * N_FREQ_BINS + c) } }
    }

    /** Drops the first/last 15 (of 172) frames -- the unreliable edges of each window's output. */
    private fun trimWindowEdges(window: Array<FloatArray>): Array<FloatArray> {
        val nOlap = N_OVERLAPPING_FRAMES / 2
        return window.copyOfRange(nOlap, window.size - nOlap)
    }

    private fun concatAndTrim(windows: List<Array<FloatArray>>, totalFrames: Int): Array<FloatArray> {
        val flat = windows.flatMap { it.asList() }
        return Array(min(totalFrames, flat.size)) { flat[it] }
    }

    private fun secondsToTicks(seconds: Double, bpm: Int): Long {
        val ticksPerSecond = MidiConstants.TICKS_PER_QUARTER_NOTE.toDouble() * bpm / 60.0
        return (seconds * ticksPerSecond).roundToLong()
    }

    companion object {
        private const val ASSET_PATH = "models/basic_pitch_icassp_2022.onnx"
        private const val INPUT_NAME = "serving_default_input_2:0"
        private const val ONSET_OUTPUT_NAME = "StatefulPartitionedCall:2"
        private const val NOTE_OUTPUT_NAME = "StatefulPartitionedCall:1"

        // Same floor DrumTranscriber uses, for the same reason: a note near the bottom of its
        // stem's dynamic range should still be audible, not effectively silent.
        private const val MIN_VELOCITY = 40
        private const val MIN_AMPLITUDE = 1e-6f

        private const val SAMPLE_RATE = 22050
        private const val FFT_HOP = 256
        private const val ANNOT_N_FRAMES = 172
        private const val N_FREQ_BINS = 88
        private const val AUDIO_N_SAMPLES = 43844
        private const val N_OVERLAPPING_FRAMES = 30
        private const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP
    }
}
