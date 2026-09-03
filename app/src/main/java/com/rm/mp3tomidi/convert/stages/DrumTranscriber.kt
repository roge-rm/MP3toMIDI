package com.rm.mp3tomidi.convert.stages

import android.content.Context
import com.rm.mp3tomidi.midi.MidiConstants
import com.rm.mp3tomidi.util.PcmUtils
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Onset detection + a small heuristic classifier (DrumOnsetDetector / DrumHitClassifier) for the
 * drums stem, since Basic Pitch assumes pitched input and can't be used here. Deliberately
 * modest in scope: 4 GM percussion voices (kick/snare/closed hi-hat/crash), a fixed short note
 * duration, and velocity from each hit's loudness relative to the stem's peak -- not a learned
 * drum transcription model. See CompositeNoteTranscriber.
 */
class DrumTranscriber : NoteTranscriber {

    override suspend fun transcribe(context: Context, stem: RawStem, bpm: Int): List<NoteEvent> {
        val mono = loadMono(stem)
        if (mono.isEmpty()) return emptyList()

        val onsets = DrumOnsetDetector.detect(mono, stem.sampleRate)
        if (onsets.isEmpty()) return emptyList()

        val peakAmplitude = AudioFilters.peak(mono).coerceAtLeast(MIN_AMPLITUDE)
        val durationTicks = samplesToTicks((DURATION_SECONDS * stem.sampleRate).toInt(), stem.sampleRate, bpm)
            .coerceAtLeast(1)

        return onsets.mapIndexed { index, onsetSample ->
            val nextOnsetSample = onsets.getOrElse(index + 1) { mono.size }
            val voice = DrumHitClassifier.classify(mono, onsetSample, stem.sampleRate, nextOnsetSample)
            val startTick = samplesToTicks(onsetSample, stem.sampleRate, bpm)
            val windowEnd = minOf(mono.size, onsetSample + (VELOCITY_WINDOW_SECONDS * stem.sampleRate).toInt())
            val localPeak = AudioFilters.peak(mono, onsetSample, windowEnd)
            val velocity = (127f * (localPeak / peakAmplitude)).roundToInt().coerceIn(MIN_VELOCITY, 127)
            NoteEvent(
                startTick = startTick,
                endTick = startTick + durationTicks,
                pitch = voice.gmPitch,
                velocity = velocity,
            )
        }
    }

    private fun loadMono(stem: RawStem): FloatArray {
        val raw = PcmUtils.readInterleavedPcm(stem.pcmFile)
        return PcmUtils.remixChannels(raw, stem.channelCount, 1)
    }

    private fun samplesToTicks(samples: Int, sampleRate: Int, bpm: Int): Long {
        val ticksPerSecond = MidiConstants.TICKS_PER_QUARTER_NOTE.toDouble() * bpm / 60.0
        return (samples.toDouble() / sampleRate * ticksPerSecond).roundToLong()
    }

    companion object {
        private const val DURATION_SECONDS = 0.06f
        private const val VELOCITY_WINDOW_SECONDS = 0.05f
        private const val MIN_AMPLITUDE = 1e-6f
        private const val MIN_VELOCITY = 40
    }
}
