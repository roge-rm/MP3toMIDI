package com.rm.mp3tomidi.convert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rm.mp3tomidi.R
import com.rm.mp3tomidi.midi.MidiFileWriter

/** Runs the full conversion pipeline as foreground work so it survives the app being backgrounded. */
class ConversionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse) ?: return Result.failure()
        val outputUri = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse) ?: return Result.failure()

        ensureNotificationChannel()
        setForeground(foregroundInfo("Starting…", 0))

        return try {
            val pipeline = ConversionPipeline()
            val result = pipeline.convert(applicationContext, inputUri) { stage, fraction ->
                setProgress(workDataOf(KEY_PROGRESS_STAGE to stage, KEY_PROGRESS_FRACTION to fraction))
                setForeground(foregroundInfo(stage, (fraction * 100).toInt()))
            }

            val midiBytes = MidiFileWriter.write(result.stems, bpm = result.bpm)
            val written = applicationContext.contentResolver.openOutputStream(outputUri)?.use {
                it.write(midiBytes)
                true
            } ?: false

            if (written) Result.success() else Result.failure()
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        }
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.conversion_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun foregroundInfo(stage: String, progressPercent: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 1

        fun buildRequest(inputUri: Uri, outputUri: Uri): OneTimeWorkRequest {
            val data = Data.Builder()
                .putString(KEY_INPUT_URI, inputUri.toString())
                .putString(KEY_OUTPUT_URI, outputUri.toString())
                .build()
            return OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(data)
                .build()
        }
    }
}
