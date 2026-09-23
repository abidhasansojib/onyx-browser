package com.onyx.browser.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.onyx.browser.data.model.DownloadItem
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadTime DESC")
    fun getAllDownloadsFlow(): Flow<List<DownloadItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(item: DownloadItem): Long

    @Update
    suspend fun updateDownload(item: DownloadItem)

    @Delete
    suspend fun deleteDownload(item: DownloadItem)

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getDownloadByDownloadId(downloadId: Long): DownloadItem?

    @Query("UPDATE downloads SET status = :status, filePath = :filePath, fileSize = :fileSize WHERE downloadId = :downloadId")
    suspend fun updateDownloadStatusByDownloadId(downloadId: Long, status: Int, filePath: String, fileSize: Long)

    @Query("UPDATE downloads SET status = :status WHERE downloadId = :downloadId")
    suspend fun updateStatus(downloadId: Long, status: Int)

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: Long): DownloadItem?

    @Query("UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes WHERE id = :id")
    suspend fun updateProgress(id: Long, status: Int, downloadedBytes: Long)

    @Query("UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, fileSize = :fileSize WHERE id = :id")
    suspend fun updateProgressAndSize(id: Long, status: Int, downloadedBytes: Long, fileSize: Long)

    @Query("UPDATE downloads SET status = :status, filePath = :filePath, fileSize = :fileSize, downloadedBytes = :downloadedBytes, sha256 = :sha256, md5 = :md5 WHERE id = :id")
    suspend fun markCompleted(id: Long, status: Int, filePath: String, fileSize: Long, downloadedBytes: Long, sha256: String, md5: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatusById(id: Long, status: Int)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clearAllDownloads()
}
