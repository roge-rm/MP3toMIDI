package com.rm.mp3tomidi.convert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.rm.mp3tomidi.convert.stages.CompositeNoteTranscriber
import com.rm.mp3tomidi.convert.stages.DemucsSourceClassifier
import com.rm.mp3tomidi.convert.stages.DemucsStemSeparator
import com.rm.mp3tomidi.convert.stages.InstrumentClassifier
import com.rm.mp3tomidi.convert.stages.NoteTranscriber
import com.rm.mp3tomidi.convert.stages.Stem
import com.rm.mp3tomidi.convert.stages.StemSeparator

/** Orchestrates the separate → transcribe → classify stages. Each stage is swappable. */
class ConversionPipeline(
    private val separator: StemSeparator = DemucsStemSeparator(),
    private val transcriber: NoteTranscriber = CompositeNoteTranscriber(),
    private val classifier: InstrumentClassifier = DemucsSourceClassifier(),
) {
    suspend fun convert(
        context: Context,
        inputAudio: Uri,
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): List<Stem> {
        onProgress("Decoding audio", 0.05f)
        val durationUs = readDurationUs(context, inputAudio)

        onProgress("Separating stems", 0.2f)
        val rawStems = separator.separate(context, inputAudio, durationUs, onProgress)
        try {
            val notesByStem = rawStems.mapIndexed { index, raw ->
                onProgress(
                    "Transcribing notes (${index + 1}/${rawStems.size}: ${raw.label})",
                    lerp(0.5f, 0.8f, index.toFloat() / rawStems.size),
                )
                raw to transcriber.transcribe(context, raw)
            }

            onProgress("Mapping instruments to GM programs", 0.8f)
            val stems = notesByStem.map { (raw, notes) -> classifier.classify(raw, notes) }

            onProgress("Writing MIDI file", 0.95f)
            return stems
        } finally {
            // Each stem's separated audio is a temp file (see RawStem); nothing downstream of
            // classification needs it once we're here.
            rawStems.forEach { it.pcmFile.delete() }
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
}
