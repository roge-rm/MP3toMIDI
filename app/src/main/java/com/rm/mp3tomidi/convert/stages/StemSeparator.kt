package com.rm.mp3tomidi.convert.stages

import android.content.Context
import android.net.Uri

/** Splits a decoded audio source into its constituent instrument stems. */
interface StemSeparator {
    suspend fun separate(context: Context, inputAudio: Uri, durationUs: Long): List<RawStem>
}

/**
 * Placeholder used until a real on-device separation model (Demucs, ONNX Runtime) is wired in.
 * Treats the whole mix as a single stem so the rest of the pipeline is exercisable end to end.
 */
class NoOpStemSeparator : StemSeparator {
    override suspend fun separate(context: Context, inputAudio: Uri, durationUs: Long): List<RawStem> {
        return listOf(RawStem(label = "mix", durationUs = durationUs))
    }
}
