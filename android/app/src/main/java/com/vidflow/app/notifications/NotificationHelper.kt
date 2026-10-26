package com.vidflow.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vidflow.app.MainActivity
import com.vidflow.app.R

object NotificationHelper {

    const val CHANNEL_DOWNLOAD  = "vidflow_download"
    const val NOTIF_PROGRESS_ID = 101
    const val NOTIF_COMPLETE_ID = 102

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DOWNLOAD,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "VidFlow download progress"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
    }

    private fun launchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun progressNotification(
        context: Context,
        title: String,
        percent: Int,
        speedKBps: Long,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = -1L,
    ): android.app.Notification {
        val sizeText = when {
            totalBytes > 0 -> "${fmtBytes(bytesDownloaded)} / ${fmtBytes(totalBytes)}"
            bytesDownloaded > 0 -> fmtBytes(bytesDownloaded)
            else -> null
        }
        val speedText = if (speedKBps > 0) "${fmtBytes(speedKBps * 1024)}/s" else null
        val progressLine = buildString {
            append(if (percent in 0..100) "$percent%" else "Preparing")
            if (sizeText != null) {
                append(" • ")
                append(sizeText)
            }
            if (speedText != null) {
                append(" • ")
                append(speedText)
            }
        }
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle(title.take(50))
            .setContentText(progressLine)
            .setSubText(sizeText)
            .setProgress(100, if (percent < 0) 0 else percent, percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(launchIntent(context))
            .build()
    }

    private fun fmtBytes(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        val kb = bytes / 1024
        return when {
            mb >= 1 -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
            kb >= 1 -> "${kb}KB"
            else    -> "${bytes}B"
        }
    }

    fun completeNotification(context: Context, title: String) =
        NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle("✓ Download Complete")
            .setContentText(title.take(60))
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
            .build()

    fun errorNotification(context: Context, message: String) =
        NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle("Download Failed")
            .setContentText(message.take(80))
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
            .build()
}
