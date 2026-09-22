package com.onyx.browser.web

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that listens for system DownloadManager completion events,
 * reconciles the downloaded file path and total size, and updates Room DB records
 * from STATUS_RUNNING to STATUS_COMPLETED or STATUS_FAILED.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = try {
            downloadManager.query(query)
        } catch (_: Exception) {
            null
        } ?: return

        try {
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIndex != -1) cursor.getInt(statusIndex) else -1

                val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val localUriString = if (localUriIndex != -1) cursor.getString(localUriIndex) else null

                val sizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val totalSize = if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L

                val resolvedPath = if (!localUriString.isNullOrBlank()) {
                    try {
                        val parsed = Uri.parse(localUriString)
                        parsed.path ?: localUriString
                    } catch (_: Exception) {
                        localUriString
                    }
                } else ""

                val dbStatus = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadItem.STATUS_COMPLETED
                    DownloadManager.STATUS_FAILED -> DownloadItem.STATUS_FAILED
                    else -> DownloadItem.STATUS_RUNNING
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getInstance(context)
                    if (resolvedPath.isNotBlank()) {
                        db.downloadDao().updateDownloadStatusByDownloadId(downloadId, dbStatus, resolvedPath, totalSize)
                    } else {
                        db.downloadDao().updateStatus(downloadId, dbStatus)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor.close()
        }
    }
}
