package com.rm.mp3tomidi.convert.stages

import android.content.Context
import android.net.Uri

/** Splits a decoded audio source into its constituent instrument stems. */
interface StemSeparator {
    suspend fun separate(context: Context, inputAudio: Uri, durationUs: Long): List<RawStem>
}

/**
 * Cheap fallback that treats the whole mix as a single silent stem, for exercising the rest of
 * the pipeline without paying for a real separation model.
 */
class NoOpStemSeparator : StemSeparator {
    override suspend fun separate(context: Context, inputAudio: Uri, durationUs: Long): List<RawStem> {
        val sampleRate = 44_100
        val frameCount = (durationUs * sampleRate / 1_000_000L).toInt()
        return listOf(
            RawStem(
                label = "mix",
                durationUs = durationUs,
                interleavedPcm = FloatArray(frameCount * 2),
                sampleRate = sampleRate,
                channelCount = 2,
            ),
        )
    }
}
