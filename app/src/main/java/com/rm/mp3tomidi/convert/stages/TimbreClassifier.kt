package com.rm.mp3tomidi.convert.stages

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.rm.mp3tomidi.util.ModelProvider
import com.rm.mp3tomidi.util.ModelSpec
import com.rm.mp3tomidi.util.PcmUtils
import java.nio.FloatBuffer

/**
 * Runs each stem's actual audio through YAMNet (Google's AudioSet classifier, see
 * tools/yamnet_export/) and maps its highest-confidence instrument class to a GM program via
 * [YamnetGmMapping], instead of [DemucsSourceClassifier]'s fixed one-program-per-Demucs-label
 * lookup. YAMNet's output is heavily diluted by generic "Music"/"Silence" classes on any real
 * stem (see [CONFIDENCE_THRESHOLD]'s derivation below), so a low-confidence result is the common
 * case, not an edge case -- when that happens, or when YAMNet's answer is just the generic
 * "Synthesizer" class, [NoteEnvelopeClassifier] gets a shot at inferring a more specific program
 * from the shape of the stem's already-transcribed notes before finally falling back to
 * [DemucsSourceClassifier]'s fixed lookup. Skipped for the vocals stem specifically -- a voice is
 * never a synthesizer, so its low-confidence fallback should stay the fixed voice-shaped default
 * rather than being pushed toward a generic synth lead.
 *
 * The drums stem skips inference entirely: Demucs's own label is already ground truth there
 * (see [DemucsSourceClassifier]), and running a classifier to confirm what's already known would
 * just spend battery for zero information.
 */
class TimbreClassifier(
    private val fallback: InstrumentClassifier = DemucsSourceClassifier(),
) : InstrumentClassifier {

    override suspend fun classify(context: Context, stem: RawStem, notes: List<NoteEvent>, bpm: Int): Stem {
        if (stem.label == "drums") return fallback.classify(context, stem, notes, bpm)

        // Cancellation isn't threaded through InstrumentClassifier -- this is a one-time ~16MB
        // download (vs. Demucs's ~235MB), over well before a user would reasonably tap cancel
        // expecting it to matter here specifically.
        val modelFile = ModelProvider.ensureAvailable(context, MODEL_SPEC, { false }) {}
        val waveform = loadAsMono16k(stem)
        if (waveform.isEmpty()) return fallback.classify(context, stem, notes, bpm)

        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        val meanScores = try {
            runInference(session, env, waveform)
        } finally {
            session.close()
        }

        val match = YamnetGmMapping.pickBestMatch(meanScores, CONFIDENCE_THRESHOLD)
        if (match != null && !match.isGenericSynth) {
            return Stem(label = stem.label, gmProgram = match.gmProgram, isDrumKit = match.isDrumKit, notes = notes)
        }

        // Envelope-based synth-role inference doesn't make sense for vocals -- a voice is never a
        // synthesizer, so a low-confidence vocals stem should keep DemucsSourceClassifier's
        // voice-shaped default (Lead Voice) rather than being pushed toward a generic synth lead.
        if (stem.label != "vocals") {
            val role = NoteEnvelopeClassifier.classify(notes, bpm)
            if (role != null) {
                return Stem(label = stem.label, gmProgram = role.gmProgram, isDrumKit = false, notes = notes)
            }
        }

        return fallback.classify(context, stem, notes, bpm)
    }

    private fun loadAsMono16k(stem: RawStem): FloatArray {
        val raw = PcmUtils.readInterleavedPcm(stem.pcmFile)
        val mono = PcmUtils.remixChannels(raw, stem.channelCount, 1)
        return PcmUtils.resampleLinear(mono, 1, stem.sampleRate, SAMPLE_RATE)
    }

    /** Runs the whole clip in one pass (YAMNet's graph does its own internal 0.96s-patch
     * windowing) and mean-pools per-class scores across every patch, the standard way to turn a
     * frame-level audio tagger into a single clip-level verdict. */
    private fun runInference(session: OrtSession, env: OrtEnvironment, waveform: FloatArray): FloatArray {
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(waveform), longArrayOf(waveform.size.toLong()))
        return inputTensor.use {
            session.run(mapOf(INPUT_NAME to it), setOf(SCORES_OUTPUT_NAME)).use { result ->
                val tensor = result.get(SCORES_OUTPUT_NAME).get() as OnnxTensor
                val buffer = tensor.floatBuffer
                val numPatches = buffer.remaining() / NUM_CLASSES
                val sums = FloatArray(NUM_CLASSES)
                for (patch in 0 until numPatches) {
                    for (cls in 0 until NUM_CLASSES) sums[cls] += buffer.get(patch * NUM_CLASSES + cls)
                }
                if (numPatches > 0) for (cls in 0 until NUM_CLASSES) sums[cls] /= numPatches
                sums
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val INPUT_NAME = "waveform"
        private const val SCORES_OUTPUT_NAME = "output_0"
        private const val NUM_CLASSES = 521

        // Real AudioSet class scores on a genuine stem are heavily diluted by generic "Music"
        // and "Silence" classes (verified on a real 4-minute song's separated stems -- see
        // tools/yamnet_export/analyze_stems.py): a clearly-present instrument like a bass guitar
        // scored ~0.22 mean confidence, while stems with only a faint/ambiguous specific
        // instrument (a sparse guitar part, a near-silent piano stem) topped out around
        // 0.02-0.045 for their best-matching mapped class. 0.05 sits in the gap between that
        // noise floor and a real signal (confirmed on the same data: the vocals stem's "Singing"
        // class scored 0.0596, just clearing it) -- deliberately conservative, since guessing an
        // instrument from noise is worse than keeping DemucsSourceClassifier's safe default.
        private const val CONFIDENCE_THRESHOLD = 0.05f

        val MODEL_SPEC = ModelSpec(
            fileName = "yamnet.onnx",
            downloadUrl = "https://github.com/roge-rm/MP3toMIDI/releases/download/yamnet-v1/yamnet.onnx",
            sha256 = "1510041dce24a2e9e84ec546807ac408ae496da6d1ed41bc3ccba649623f8e19",
        )
    }
}
