package com.onyx.browser.download

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object OnyxDownloadManager {

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTasks = ConcurrentHashMap<Long, DownloadTask>()
    private val _snapshotsFlow = MutableStateFlow<Map<Long, DownloadTaskSnapshot>>(emptyMap())
    val snapshotsFlow: StateFlow<Map<Long, DownloadTaskSnapshot>> = _snapshotsFlow.asStateFlow()

    private var appContext: Context? = null
    private val speedLimiter = TokenBucketLimiter()
    private var networkMonitor: NetworkMonitor? = null
    private lateinit var downloadEngine: DownloadEngine

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        val prefs = BrowserPreferences.getInstance(app)
        speedLimiter.setLimit(prefs.downloadSpeedLimit)

        downloadEngine = DownloadEngine(
            context = app,
            limiter = speedLimiter,
            onProgress = { task -> onTaskProgress(task) },
            onCompleted = { task -> onTaskCompleted(task) },
            onFailed = { task, err -> onTaskFailed(task, err) },
            onPaused = { task -> onTaskPaused(task) }
        )

        networkMonitor = NetworkMonitor(app) { isAvailable, isWifi ->
            handleNetworkStateChange(isAvailable, isWifi)
        }.apply { start() }
    }

    fun setSpeedLimit(bytesPerSecond: Long) {
        speedLimiter.setLimit(bytesPerSecond)
    }

    fun enqueueDownload(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String,
        cookies: String = "",
        referer: String = "",
        contentLength: Long = 0L
    ): Long {
        init(context)
        val app = context.applicationContext
        val cleanFileName = sanitizeFileName(fileName)

        val tempDir = File(app.cacheDir, "onyx_downloads").apply { mkdirs() }
        val tempFile = File(tempDir, "temp_${System.currentTimeMillis()}_$cleanFileName")
        val finalPath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$cleanFileName"

        // Insert into database initially
        val assignedId = System.currentTimeMillis()

        managerScope.launch {
            try {
                val db = AppDatabase.getInstance(app)
                db.downloadDao().insertDownload(
                    DownloadItem(
                        id = assignedId,
                        downloadId = -1L,
                        url = url,
                        fileName = cleanFileName,
                        filePath = finalPath,
                        mimeType = mimeType,
                        fileSize = contentLength,
                        downloadedBytes = 0L,
                        status = DownloadItem.STATUS_RUNNING
                    )
                )
            } catch (_: Exception) {}
        }

        val task = DownloadTask(
            id = assignedId,
            url = url,
            fileName = cleanFileName,
            mimeType = mimeType,
            userAgent = userAgent,
            cookies = cookies,
            referer = referer,
            tempFilePath = tempFile.absolutePath,
            finalFilePath = finalPath,
            totalBytes = contentLength,
            status = DownloadTask.STATUS_PENDING
        )

        activeTasks[assignedId] = task
        emitSnapshot()

        // Start Foreground Service
        val serviceIntent = Intent(app, OnyxDownloadService::class.java)
        ContextCompat.startForegroundService(app, serviceIntent)

        val prefs = BrowserPreferences.getInstance(app)
        val isWifiOnly = prefs.isDownloadWifiOnly
        val isWifi = networkMonitor?.isWifiConnected() ?: false

        if (isWifiOnly && !isWifi) {
            task.isWifiWaiting = true
            task.status = DownloadTask.STATUS_PAUSED
            emitSnapshot()
            Toast.makeText(app, "Download queued: Waiting for Wi-Fi", Toast.LENGTH_SHORT).show()
        } else {
            startTaskExecution(task)
            Toast.makeText(app, "Download started: $cleanFileName", Toast.LENGTH_SHORT).show()
        }

        return assignedId
    }

    private fun startTaskExecution(task: DownloadTask) {
        task.status = DownloadTask.STATUS_RUNNING
        task.isWifiWaiting = false
        emitSnapshot()

        task.job = managerScope.launch {
            downloadEngine.executeDownload(task)
        }
    }

    fun pauseDownload(taskId: Long) {
        val task = activeTasks[taskId] ?: return
        task.job?.cancel()
        task.status = DownloadTask.STATUS_PAUSED
        task.speedBytesPerSec = 0L
        task.etaSeconds = -1L

        appContext?.let { ctx ->
            managerScope.launch {
                val db = AppDatabase.getInstance(ctx)
                db.downloadDao().updateProgress(
                    id = taskId,
                    status = DownloadItem.STATUS_PAUSED,
                    downloadedBytes = task.downloadedBytes.get()
                )
            }
        }

        emitSnapshot()
    }

    fun resumeDownload(taskId: Long) {
        val task = activeTasks[taskId] ?: return
        val app = appContext ?: return

        val prefs = BrowserPreferences.getInstance(app)
        val isWifiOnly = prefs.isDownloadWifiOnly
        val isWifi = networkMonitor?.isWifiConnected() ?: false

        if (isWifiOnly && !isWifi) {
            task.isWifiWaiting = true
            task.status = DownloadTask.STATUS_PAUSED
            emitSnapshot()
            Toast.makeText(app, "Waiting for Wi-Fi connection…", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(app, OnyxDownloadService::class.java)
        ContextCompat.startForegroundService(app, serviceIntent)

        startTaskExecution(task)
    }

    fun cancelDownload(taskId: Long) {
        val task = activeTasks.remove(taskId)
        task?.job?.cancel()
        task?.status = DownloadTask.STATUS_CANCELLED

        task?.let {
            try {
                File(it.tempFilePath).delete()
            } catch (_: Exception) {}
        }

        appContext?.let { ctx ->
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(taskId.toInt())

            managerScope.launch {
                val db = AppDatabase.getInstance(ctx)
                db.downloadDao().updateStatusById(taskId, DownloadItem.STATUS_CANCELLED)
            }
        }

        emitSnapshot()
    }

    fun retryDownload(taskId: Long) {
        val task = activeTasks[taskId] ?: return
        task.errorMessage = null
        resumeDownload(taskId)
    }

    fun getTask(taskId: Long): DownloadTask? = activeTasks[taskId]

    private fun onTaskProgress(task: DownloadTask) {
        emitSnapshot()
    }

    private fun onTaskPaused(task: DownloadTask) {
        emitSnapshot()
    }

    private fun onTaskCompleted(task: DownloadTask) {
        appContext?.let { ctx ->
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(task.id.toInt())
            val completedNotif = DownloadNotificationHelper.buildCompletedNotification(ctx, task)
            nm?.notify(task.id.toInt(), completedNotif)
        }

        emitSnapshot()
        activeTasks.remove(task.id)
    }

    private fun onTaskFailed(task: DownloadTask, error: String) {
        appContext?.let { ctx ->
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(task.id.toInt())
            val failedNotif = DownloadNotificationHelper.buildFailedNotification(ctx, task)
            nm?.notify(task.id.toInt(), failedNotif)
        }

        emitSnapshot()
    }

    private fun handleNetworkStateChange(isAvailable: Boolean, isWifi: Boolean) {
        val app = appContext ?: return
        val prefs = BrowserPreferences.getInstance(app)
        val isWifiOnly = prefs.isDownloadWifiOnly

        if (!isAvailable) {
            // Auto-pause all running downloads
            activeTasks.values.forEach { task ->
                if (task.status == DownloadTask.STATUS_RUNNING) {
                    task.job?.cancel()
                    task.status = DownloadTask.STATUS_PAUSED
                    task.speedBytesPerSec = 0L
                }
            }
            emitSnapshot()
        } else {
            // Network restored -> check auto-resume
            activeTasks.values.forEach { task ->
                if (task.status == DownloadTask.STATUS_PAUSED) {
                    if (isWifiOnly && !isWifi) {
                        task.isWifiWaiting = true
                    } else {
                        task.isWifiWaiting = false
                        resumeDownload(task.id)
                    }
                }
            }
            emitSnapshot()
        }
    }

    private fun emitSnapshot() {
        val map = activeTasks.mapValues { (_, task) ->
            DownloadTaskSnapshot(
                id = task.id,
                fileName = task.fileName,
                totalBytes = task.totalBytes,
                downloadedBytes = task.downloadedBytes.get(),
                progressPercent = task.progressPercent,
                speedBytesPerSec = task.speedBytesPerSec,
                etaSeconds = task.etaSeconds,
                status = task.status,
                errorMessage = task.errorMessage,
                filePath = task.finalFilePath,
                isWifiWaiting = task.isWifiWaiting
            )
        }
        _snapshotsFlow.value = map
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (clean.isBlank()) "download_${System.currentTimeMillis()}" else clean
    }
}
