package com.onyx.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.onyx.browser.R
import com.onyx.browser.ui.downloads.DownloadsActivity
import java.io.File

object DownloadNotificationHelper {

    const val CHANNEL_DOWNLOADS_RUNNING = "onyx_downloads_running"
    const val CHANNEL_DOWNLOADS_COMPLETED = "onyx_downloads_completed"

    const val ACTION_PAUSE = "com.onyx.browser.download.ACTION_PAUSE"
    const val ACTION_RESUME = "com.onyx.browser.download.ACTION_RESUME"
    const val ACTION_CANCEL = "com.onyx.browser.download.ACTION_CANCEL"
    const val ACTION_RETRY = "com.onyx.browser.download.ACTION_RETRY"

    const val EXTRA_TASK_ID = "extra_task_id"

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Running downloads: LOW importance (silent, no repetitive pinging)
            val runningChannel = NotificationChannel(
                CHANNEL_DOWNLOADS_RUNNING,
                "Onyx Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress for active downloads"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            // Completed downloads: DEFAULT importance
            val completedChannel = NotificationChannel(
                CHANNEL_DOWNLOADS_COMPLETED,
                "Onyx Completed Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads finish or fail"
                setShowBadge(true)
            }

            nm.createNotificationChannel(runningChannel)
            nm.createNotificationChannel(completedChannel)
        }
    }

    fun buildRunningNotification(context: Context, task: DownloadTask): Notification {
        val downloaded = task.downloadedBytes.get()
        val total = task.totalBytes
        val downloadedStr = Formatter.formatFileSize(context, downloaded)
        val totalStr = if (total > 0L) Formatter.formatFileSize(context, total) else "Unknown"
        val speedStr = formatSpeed(context, task.speedBytesPerSec)
        val etaStr = formatEta(task.etaSeconds)

        val contentText = if (task.isWifiWaiting) {
            "Waiting for Wi-Fi connection…"
        } else if (total > 0L) {
            "$downloadedStr / $totalStr (${task.progressPercent}%) • $speedStr • ETA $etaStr"
        } else {
            "$downloadedStr • $speedStr"
        }

        val openDownloadsIntent = PendingIntent.getActivity(
            context,
            task.notificationId * 4,
            Intent(context, DownloadsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            context,
            task.notificationId * 4 + 1,
            Intent(context, OnyxDownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            context,
            task.notificationId * 4 + 2,
            Intent(context, OnyxDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_RUNNING)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(task.fileName)
            .setContentText(contentText)
            .setContentIntent(openDownloadsIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (task.isIndeterminate) 0 else task.progressPercent, task.isIndeterminate)
            .addAction(R.drawable.ic_pause, "Pause", pauseIntent)
            .addAction(R.drawable.ic_close, "Cancel", cancelIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun buildPausedNotification(context: Context, task: DownloadTask): Notification {
        val downloaded = task.downloadedBytes.get()
        val total = task.totalBytes
        val downloadedStr = Formatter.formatFileSize(context, downloaded)
        val totalStr = if (total > 0L) Formatter.formatFileSize(context, total) else "Unknown"

        val contentText = if (task.isWifiWaiting) {
            "Paused • Waiting for Wi-Fi"
        } else {
            "Paused • $downloadedStr / $totalStr"
        }

        val openDownloadsIntent = PendingIntent.getActivity(
            context,
            task.notificationId * 4,
            Intent(context, DownloadsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = PendingIntent.getService(
            context,
            task.notificationId * 4 + 3,
            Intent(context, OnyxDownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            context,
            task.notificationId * 4 + 2,
            Intent(context, OnyxDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_RUNNING)
            .setSmallIcon(R.drawable.ic_pause)
            .setContentTitle(task.fileName)
            .setContentText(contentText)
            .setContentIntent(openDownloadsIntent)
            .setOngoing(false)
            .setProgress(100, if (task.isIndeterminate) 0 else task.progressPercent, false)
            .addAction(R.drawable.ic_play_arrow, "Resume", resumeIntent)
            .addAction(R.drawable.ic_close, "Cancel", cancelIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun buildCompletedNotification(context: Context, task: DownloadTask): Notification {
        val sizeStr = if (task.totalBytes > 0L) Formatter.formatFileSize(context, task.totalBytes) else ""
        val contentText = if (sizeStr.isNotBlank()) "Download complete • $sizeStr" else "Download complete"

        val isApk = com.onyx.browser.ui.downloads.ApkInstallerHelper.isApkFile(task.fileName, task.mimeType)
        val openIntent = try {
            val finalPath = task.finalFilePath
            if (isApk) {
                Intent(context, DownloadsActivity::class.java).apply {
                    putExtra(DownloadsActivity.EXTRA_INSTALL_APK_PATH, finalPath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            } else {
                val contentUri = if (finalPath.startsWith("content://", ignoreCase = true)) {
                    Uri.parse(finalPath)
                } else {
                    val file = File(finalPath)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, task.mimeType.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } catch (_: Exception) {
            Intent(context, DownloadsActivity::class.java)
        }

        val pendingOpen = PendingIntent.getActivity(
            context,
            task.notificationId * 4,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIconRes = if (isApk) R.drawable.ic_android else R.drawable.ic_download
        val completeDesc = if (isApk && sizeStr.isNotBlank()) {
            "Download complete • $sizeStr • Tap to install"
        } else if (isApk) {
            "Download complete • Tap to install"
        } else {
            contentText
        }

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_COMPLETED)
            .setSmallIcon(smallIconRes)
            .setContentTitle(task.fileName)
            .setContentText(completeDesc)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .build()
    }

    fun buildFailedNotification(context: Context, task: DownloadTask): Notification {
        val err = task.errorMessage ?: "Network error"
        val openDownloadsIntent = PendingIntent.getActivity(
            context,
            task.notificationId * 4,
            Intent(context, DownloadsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val retryIntent = PendingIntent.getService(
            context,
            task.notificationId * 4 + 3,
            Intent(context, OnyxDownloadService::class.java).apply {
                action = ACTION_RETRY
                putExtra(EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_COMPLETED)
            .setSmallIcon(R.drawable.ic_close)
            .setContentTitle("Download failed: ${task.fileName}")
            .setContentText(err)
            .setContentIntent(openDownloadsIntent)
            .addAction(R.drawable.ic_refresh, "Retry", retryIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun formatSpeed(context: Context, speedBytesPerSec: Long): String {
        return if (speedBytesPerSec > 0L) {
            "${Formatter.formatFileSize(context, speedBytesPerSec)}/s"
        } else {
            "0 B/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds < 0L || seconds > 86400L) return "--"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
