package com.rm.mp3tomidi.convert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.rm.mp3tomidi.MainActivity
import com.rm.mp3tomidi.R

/** Foreground-notification plumbing shared by [AnalysisWorker] and [WriteWorker] -- split out of
 * what used to be one worker's private helpers since both now need it. */
object ConversionNotifications {
    private const val CHANNEL_ID = "conversion"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.conversion_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, stage: String, progressPercent: Int): ForegroundInfo {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
