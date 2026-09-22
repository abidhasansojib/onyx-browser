package com.onyx.browser.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val downloadId: Long = -1L,
    val url: String,
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val fileSize: Long,
    val status: Int = STATUS_COMPLETED,
    val downloadTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_RUNNING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = 3
    }
}
