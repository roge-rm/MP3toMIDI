package com.rm.mp3tomidi.convert.stages

import android.content.Context

/**
 * Dispatches to [BasicPitchTranscriber] for pitched stems and a placeholder for drums --
 * Basic Pitch assumes pitched input, so running it on percussion would produce spurious
 * "pitches" that mean nothing on the GM percussion key map.
 */
class CompositeNoteTranscriber(
    private val drumLabel: String = "drums",
    private val pitchedTranscriber: NoteTranscriber = BasicPitchTranscriber(),
    private val drumTranscriber: NoteTranscriber = PlaceholderNoteTranscriber(),
) : NoteTranscriber {
    override suspend fun transcribe(context: Context, stem: RawStem): List<NoteEvent> {
        val transcriber = if (stem.label == drumLabel) drumTranscriber else pitchedTranscriber
        return transcriber.transcribe(context, stem)
    }
}
