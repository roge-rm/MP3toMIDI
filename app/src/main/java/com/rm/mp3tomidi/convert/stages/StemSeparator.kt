package com.rm.mp3tomidi.convert.stages

import android.content.Context
import android.net.Uri
import java.io.File

/** Splits a decoded audio source into its constituent instrument stems. */
interface StemSeparator {
    suspend fun separate(
        context: Context,
        inputAudio: Uri,
        durationUs: Long,
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): List<RawStem>
}

/**
 * Cheap fallback that treats the whole mix as a single silent stem, for exercising the rest of
 * the pipeline without paying for a real separation model.
 */
class NoOpStemSeparator : StemSeparator {
    override suspend fun separate(
        context: Context,
        inputAudio: Uri,
        durationUs: Long,
        onProgress: suspend (stage: String, fraction: Float) -> Unit,
    ): List<RawStem> {
        val sampleRate = 44_100
        val silentFile = File.createTempFile("mix", ".pcm", context.cacheDir).apply { deleteOnExit() }
        return listOf(
            RawStem(
                label = "mix",
                durationUs = durationUs,
                pcmFile = silentFile,
                sampleRate = sampleRate,
                channelCount = 2,
            ),
        )
    }
}
