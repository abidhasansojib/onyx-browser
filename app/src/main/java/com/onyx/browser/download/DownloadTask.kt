package com.onyx.browser.download

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime state representation of a download task.
 */
data class DownloadTask(
    val id: Long,
    val url: String,
    var fileName: String,
    var mimeType: String,
    val userAgent: String,
    val cookies: String,
    val referer: String,
    val tempFilePath: String,
    var finalFilePath: String,
    var totalBytes: Long,
    val downloadedBytes: AtomicLong = AtomicLong(0L),
    @Volatile var status: Int = STATUS_PENDING,
    @Volatile var speedBytesPerSec: Long = 0L,
    @Volatile var etaSeconds: Long = -1L,
    @Volatile var errorMessage: String? = null,
    @Volatile var etag: String = "",
    @Volatile var lastModified: String = "",
    @Volatile var isRangeSupported: Boolean = false,
    @Volatile var isWifiWaiting: Boolean = false,
    val chunks: MutableList<DownloadChunk> = mutableListOf(),
    @Volatile var sha256: String = "",
    @Volatile var md5: String = "",
    var job: Job? = null
) {
    val progressPercent: Int
        get() = if (totalBytes > 0L) {
            ((downloadedBytes.get() * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else -1

    val isIndeterminate: Boolean
        get() = totalBytes <= 0L

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_RUNNING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = 3
        const val STATUS_PAUSED = 4
        const val STATUS_CANCELLED = 5
    }
}

/**
 * Lightweight snapshot emitted to observers and UI.
 */
data class DownloadTaskSnapshot(
    val id: Long,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val progressPercent: Int,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val status: Int,
    val errorMessage: String? = null,
    val filePath: String = "",
    val isWifiWaiting: Boolean = false
)
