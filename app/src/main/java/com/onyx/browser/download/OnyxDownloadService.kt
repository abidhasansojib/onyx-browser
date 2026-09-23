package com.onyx.browser.download

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.onyx.browser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OnyxDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        DownloadNotificationHelper.initChannels(this)
        ensureForeground()
        observeDownloadTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        intent?.let {
            val taskId = it.getLongExtra(DownloadNotificationHelper.EXTRA_TASK_ID, -1L)
            when (it.action) {
                DownloadNotificationHelper.ACTION_PAUSE -> {
                    if (taskId != -1L) OnyxDownloadManager.pauseDownload(taskId)
                }
                DownloadNotificationHelper.ACTION_RESUME -> {
                    if (taskId != -1L) OnyxDownloadManager.resumeDownload(taskId)
                }
                DownloadNotificationHelper.ACTION_CANCEL -> {
                    if (taskId != -1L) OnyxDownloadManager.cancelDownload(taskId)
                }
                DownloadNotificationHelper.ACTION_RETRY -> {
                    if (taskId != -1L) OnyxDownloadManager.retryDownload(taskId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        if (!isForeground) {
            val initialNotification = NotificationCompat.Builder(this, DownloadNotificationHelper.CHANNEL_DOWNLOADS_RUNNING)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("Onyx Downloader")
                .setContentText("Initializing download…")
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, initialNotification)
            }
            isForeground = true
        }
    }

    private fun observeDownloadTasks() {
        serviceScope.launch {
            OnyxDownloadManager.snapshotsFlow.collectLatest { snapshots ->
                val activeOrPaused = snapshots.values.filter {
                    it.status == DownloadTask.STATUS_RUNNING ||
                    it.status == DownloadTask.STATUS_PAUSED ||
                    it.status == DownloadTask.STATUS_PENDING
                }

                if (activeOrPaused.isEmpty()) {
                    // No active tasks remain -> stop foreground
                    if (isForeground) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                        }
                        isForeground = false
                    }
                    stopSelf()
                } else {
                    ensureForeground()

                    // Update notifications for active / paused downloads
                    for (task in activeOrPaused) {
                        val fullTask = OnyxDownloadManager.getTask(task.id) ?: continue
                        val notification = if (fullTask.status == DownloadTask.STATUS_PAUSED) {
                            DownloadNotificationHelper.buildPausedNotification(this@OnyxDownloadService, fullTask)
                        } else {
                            DownloadNotificationHelper.buildRunningNotification(this@OnyxDownloadService, fullTask)
                        }
                        // Use task notificationId so each download has its own clean entry without integer overflow
                        notificationManager.notify(fullTask.notificationId, notification)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 8801
    }
}
