package com.rm.mp3tomidi.convert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.rm.mp3tomidi.convert.stages.CompositeNoteTranscriber
import com.rm.mp3tomidi.convert.stages.DemucsSourceClassifier
import com.rm.mp3tomidi.convert.stages.DemucsStemSeparator
import com.rm.mp3tomidi.convert.stages.InstrumentClassifier
import com.rm.mp3tomidi.convert.stages.NoteEvent
import com.rm.mp3tomidi.convert.stages.NoteTranscriber
import com.rm.mp3tomidi.convert.stages.RawStem
import com.rm.mp3tomidi.convert.stages.Stem
import com.rm.mp3tomidi.convert.stages.StemSeparator
import com.rm.mp3tomidi.convert.stages.TempoDetector
import com.rm.mp3tomidi.convert.stages.TimbreClassifier
import com.rm.mp3tomidi.util.PcmUtils
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Result of a full conversion: the transcribed/classified stems plus the tempo detected for them. */
data class ConversionResult(val stems: List<Stem>, val bpm: Int)

/** Orchestrates the separate → transcribe → classify stages. Each stage is swappable. */
class ConversionPipeline(
    private val separator: StemSeparator = DemucsStemSeparator(),
    private val transcriber: NoteTranscriber = CompositeNoteTranscriber(),
    private val classifier: InstrumentClassifier = TimbreClassifier(),
) {
    suspend fun convert(
        context: Context,
        inputAudio: Uri,
        isCancelled: () -> Boolean,
        options: ConversionOptions = ConversionOptions(),
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): ConversionResult {
        onProgress("Decoding audio", 0.05f)
        val durationUs = readDurationUs(context, inputAudio)

        onProgress("Separating stems", 0.2f)
        // Demucs itself can't skip a stem mid-model-run -- all 6 come out of one pass regardless
        // of options.includedStemLabels. Excluding a stem only skips the transcription/
        // classification work below, not separation time. Excluded stems' temp PCM files are
        // deleted immediately rather than carried through to the shared cleanup below, since
        // rawStems (used there) no longer references them past this point.
        val allRawStems = separator.separate(context, inputAudio, durationUs, isCancelled, onProgress)
        val rawStems = allRawStems.filter { it.label in options.includedStemLabels }
        allRawStems.filter { it.label !in options.includedStemLabels }.forEach { it.pcmFile.delete() }
        try {
            if (isCancelled()) throw CancellationException("Conversion cancelled")
            onProgress("Detecting tempo", 0.5f)
            val bpm = detectBpm(rawStems)

            val pitchedStems = analyzePitchedStems(rawStems, options.silentStemRmsRatio)
            val activeStems = pitchedStems.activeStems

            val notesByStem = activeStems.mapIndexed { index, raw ->
                if (isCancelled()) throw CancellationException("Conversion cancelled")
                onProgress(
                    "Transcribing notes (${index + 1}/${activeStems.size}: ${raw.label})",
                    lerp(0.5f, 0.8f, index.toFloat() / activeStems.size),
                )
                raw to transcriber.transcribe(context, raw, bpm, options.noteFrameThreshold)
            }

            val balancedNotesByStem = balancePitchedVelocities(notesByStem, pitchedStems.rmsByLabel)

            val stems = balancedNotesByStem.mapIndexed { index, (raw, notes) ->
                if (isCancelled()) throw CancellationException("Conversion cancelled")
                onProgress(
                    "Mapping instruments to GM programs (${index + 1}/${balancedNotesByStem.size}: ${raw.label})",
                    lerp(0.8f, 0.95f, index.toFloat() / balancedNotesByStem.size),
                )
                classifier.classify(context, raw, notes, bpm)
            }

            onProgress("Writing MIDI file", 0.95f)
            return ConversionResult(stems, bpm)
        } finally {
            // Each stem's separated audio is a temp file (see RawStem); nothing downstream of
            // classification needs it once we're here.
            rawStems.forEach { it.pcmFile.delete() }
        }
    }

    /**
     * Estimates one global tempo from the drums stem -- the clearest rhythmic signal available --
     * rather than per pitched stem, since a Standard MIDI File only has a single tempo track
     * anyway. Falls back to [TempoDetector.DEFAULT_BPM] if separation didn't produce a drums stem.
     */
    private fun detectBpm(rawStems: List<RawStem>): Int {
        val drums = rawStems.find { it.label == "drums" } ?: return TempoDetector.DEFAULT_BPM
        val raw = PcmUtils.readInterleavedPcm(drums.pcmFile)
        val mono = PcmUtils.remixChannels(raw, drums.channelCount, 1)
        return TempoDetector.detectBpm(mono, drums.sampleRate)
    }

    private data class PitchedStemAnalysis(val activeStems: List<RawStem>, val rmsByLabel: Map<String, Float>)

    /**
     * Drops a pitched stem whose separated audio is essentially Demucs' residual noise floor
     * rather than a real instrument -- most consequential for the 6-stem model's much weaker
     * "piano" source in songs that don't actually have piano. Confirmed on 8 real songs: a
     * genuinely-absent instrument's stem sits at roughly 0.3-2.5% of the loudest pitched stem's
     * RMS, while every stem confirmed to carry a real instrument sits at 5.8% or higher --
     * [MIN_STEM_RMS_RATIO] targets that real gap rather than an arbitrary guess. Without this,
     * Basic Pitch is sensitive enough to still carve a few hundred spurious short notes out of
     * that noise floor, which sounds like an instrument that was never really in the song cutting
     * in and out throughout it. Drums is exempt -- its GM program comes from Demucs' own label,
     * not Basic Pitch transcription, so it can't exhibit this failure mode.
     *
     * Also returns each surviving stem's RMS, reused by [balancePitchedVelocities] rather than
     * re-reading every stem's (tens-of-MB) PCM file a second time just to recompute the same
     * numbers.
     */
    private fun analyzePitchedStems(rawStems: List<RawStem>, minRatio: Float): PitchedStemAnalysis {
        val pitched = rawStems.filter { it.label != "drums" }
        val rmsByLabel = pitched.associate { it.label to rmsOf(PcmUtils.readInterleavedPcm(it.pcmFile)) }
        val silentLabels = silentPitchedLabels(rmsByLabel, minRatio)
        val activeStems = rawStems.filter { it.label !in silentLabels }
        return PitchedStemAnalysis(activeStems, rmsByLabel.filterKeys { it !in silentLabels })
    }

    /**
     * Scales each pitched stem's note velocities relative to how loud that stem actually is
     * compared to the loudest pitched stem in the song. [BasicPitchTranscriber]'s velocities are
     * already real-loudness-based *within* one stem (see its doc), but that alone still leaves a
     * naturally-quiet stem (e.g. a background pad) sounding as loud as a naturally-prominent one
     * (e.g. the bass line), since each was only ever scaled against its own peak. Measured
     * directly on real songs (see [BasicPitchTranscriber]'s doc): stem RMS varies by hundreds of
     * percent between stems in the same song, none of which showed up in velocity at all before
     * this. Drums is exempt -- it already has its own real dynamics via
     * [DrumTranscriber] and isn't being compared against a set of wildly different instrument
     * roles the way the five pitched stems are. [MIN_BALANCE_SCALE] floors how far down a
     * genuinely-quieter-but-real stem (one that already passed [analyzePitchedStems]'s
     * noise-floor check) can be pushed, so it's de-emphasized rather than made inaudible.
     */
    private fun balancePitchedVelocities(
        notesByStem: List<Pair<RawStem, List<NoteEvent>>>,
        pitchedRmsByLabel: Map<String, Float>,
    ): List<Pair<RawStem, List<NoteEvent>>> {
        val scales = velocityScales(pitchedRmsByLabel)
        if (scales.isEmpty()) return notesByStem
        return notesByStem.map { (raw, notes) ->
            val scale = scales[raw.label] ?: return@map raw to notes
            raw to notes.map { it.copy(velocity = (it.velocity * scale).roundToInt().coerceIn(1, 127)) }
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

    private fun readDurationUs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            durationMs * 1000L
        } finally {
            retriever.release()
        }
    }

    companion object {
        // See analyzePitchedStems's doc for how this was derived from real songs.
        private const val MIN_STEM_RMS_RATIO = 0.04f

        // See balancePitchedVelocities's doc. 0.35 was chosen to noticeably de-emphasize a
        // quieter-but-real stem without pushing it near-silent -- no real-song calibration behind
        // this specific number the way MIN_STEM_RMS_RATIO has, since "how much quieter should a
        // quieter stem sound" is a judgment call rather than a bogus/real cutoff to detect.
        private const val MIN_BALANCE_SCALE = 0.35f

        internal fun rmsOf(pcm: FloatArray): Float {
            if (pcm.isEmpty()) return 0f
            var sumSq = 0.0
            for (sample in pcm) sumSq += sample.toDouble() * sample.toDouble()
            return sqrt(sumSq / pcm.size).toFloat()
        }

        internal fun silentPitchedLabels(
            rmsByLabel: Map<String, Float>,
            minRatio: Float = MIN_STEM_RMS_RATIO,
        ): Set<String> {
            val loudest = rmsByLabel.values.maxOrNull() ?: return emptySet()
            if (loudest <= 0f) return emptySet()
            return rmsByLabel.filterValues { it < loudest * minRatio }.keys
        }

        /** Per-label velocity scale factor, relative to the loudest stem in [rmsByLabel]. */
        internal fun velocityScales(
            rmsByLabel: Map<String, Float>,
            minScale: Float = MIN_BALANCE_SCALE,
        ): Map<String, Float> {
            val loudest = rmsByLabel.values.maxOrNull() ?: return emptyMap()
            if (loudest <= 0f) return emptyMap()
            return rmsByLabel.mapValues { (_, rms) -> (rms / loudest).coerceIn(minScale, 1f) }
        }
    }
}
