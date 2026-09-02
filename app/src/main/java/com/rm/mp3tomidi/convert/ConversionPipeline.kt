package com.rm.mp3tomidi.convert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.rm.mp3tomidi.convert.stages.InstrumentClassifier
import com.rm.mp3tomidi.convert.stages.NoOpStemSeparator
import com.rm.mp3tomidi.convert.stages.NoteTranscriber
import com.rm.mp3tomidi.convert.stages.PlaceholderInstrumentClassifier
import com.rm.mp3tomidi.convert.stages.PlaceholderNoteTranscriber
import com.rm.mp3tomidi.convert.stages.Stem
import com.rm.mp3tomidi.convert.stages.StemSeparator

/**
 * Orchestrates the separate → transcribe → classify stages. Each stage is swappable so the
 * no-op/placeholder implementations here can be replaced with real on-device models
 * (Demucs via ONNX Runtime for separation, Basic Pitch for transcription, a timbre
 * classifier for GM mapping) independently of this wiring.
 */
class ConversionPipeline(
    private val separator: StemSeparator = NoOpStemSeparator(),
    private val transcriber: NoteTranscriber = PlaceholderNoteTranscriber(),
    private val classifier: InstrumentClassifier = PlaceholderInstrumentClassifier(),
) {
    suspend fun convert(
        context: Context,
        inputAudio: Uri,
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): List<Stem> {
        onProgress("Decoding audio", 0.05f)
        val durationUs = readDurationUs(context, inputAudio)

        onProgress("Separating stems", 0.2f)
        val rawStems = separator.separate(context, inputAudio, durationUs)

        onProgress("Transcribing notes", 0.5f)
        val notesByStem = rawStems.map { raw -> raw to transcriber.transcribe(raw) }

        onProgress("Mapping instruments to GM programs", 0.8f)
        val stems = notesByStem.map { (raw, notes) -> classifier.classify(raw, notes) }

        onProgress("Writing MIDI file", 0.95f)
        return stems
    }

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
