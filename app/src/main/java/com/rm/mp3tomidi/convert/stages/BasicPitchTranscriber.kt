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

        return rawNotes.map { note ->
            val startSeconds = times.getOrElse(note.startFrame) { times.lastOrNull() ?: 0.0 }
            val endSeconds = times.getOrElse(min(note.endFrame, times.size - 1)) { times.lastOrNull() ?: 0.0 }
            NoteEvent(
                startTick = secondsToTicks(startSeconds, bpm),
                endTick = secondsToTicks(endSeconds, bpm),
                pitch = note.pitch,
                velocity = (note.amplitude * 127f).roundToInt().coerceIn(1, 127),
            )
        }
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

        private const val SAMPLE_RATE = 22050
        private const val FFT_HOP = 256
        private const val ANNOT_N_FRAMES = 172
        private const val N_FREQ_BINS = 88
        private const val AUDIO_N_SAMPLES = 43844
        private const val N_OVERLAPPING_FRAMES = 30
        private const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP
    }
}
