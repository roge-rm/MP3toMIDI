package com.rm.mp3tomidi.convert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.rm.mp3tomidi.convert.stages.CompositeNoteTranscriber
import com.rm.mp3tomidi.convert.stages.DemucsSourceClassifier
import com.rm.mp3tomidi.convert.stages.DemucsStemSeparator
import com.rm.mp3tomidi.convert.stages.InstrumentClassifier
import com.rm.mp3tomidi.convert.stages.NoteTranscriber
import com.rm.mp3tomidi.convert.stages.RawStem
import com.rm.mp3tomidi.convert.stages.Stem
import com.rm.mp3tomidi.convert.stages.StemSeparator
import com.rm.mp3tomidi.convert.stages.TempoDetector
import com.rm.mp3tomidi.convert.stages.TimbreClassifier
import com.rm.mp3tomidi.util.PcmUtils

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
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): ConversionResult {
        onProgress("Decoding audio", 0.05f)
        val durationUs = readDurationUs(context, inputAudio)

        onProgress("Separating stems", 0.2f)
        val rawStems = separator.separate(context, inputAudio, durationUs, onProgress)
        try {
            onProgress("Detecting tempo", 0.5f)
            val bpm = detectBpm(rawStems)

            val notesByStem = rawStems.mapIndexed { index, raw ->
                onProgress(
                    "Transcribing notes (${index + 1}/${rawStems.size}: ${raw.label})",
                    lerp(0.5f, 0.8f, index.toFloat() / rawStems.size),
                )
                raw to transcriber.transcribe(context, raw, bpm)
            }

            val stems = notesByStem.mapIndexed { index, (raw, notes) ->
                onProgress(
                    "Mapping instruments to GM programs (${index + 1}/${notesByStem.size}: ${raw.label})",
                    lerp(0.8f, 0.95f, index.toFloat() / notesByStem.size),
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
}
