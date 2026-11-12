package com.onyx.browser.data.repository

import android.content.Context
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.local.DownloadDao
import com.onyx.browser.data.model.DownloadItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class DownloadRepository(
    private val downloadDao: DownloadDao
) {
    fun getAllDownloadsFlow(): Flow<List<DownloadItem>> = downloadDao.getAllDownloadsFlow()

    suspend fun getAllDownloads(): List<DownloadItem> = downloadDao.getAllDownloadsFlow().first()

    suspend fun getDownloadById(id: Long): DownloadItem? = downloadDao.getDownloadById(id)

    suspend fun insertDownload(item: DownloadItem): Long = downloadDao.insertDownload(item)

    suspend fun updateDownload(item: DownloadItem) = downloadDao.updateDownload(item)

    suspend fun deleteDownload(item: DownloadItem) = downloadDao.deleteDownload(item)

    suspend fun clearAllDownloads() = downloadDao.clearAllDownloads()

    companion object {
        @Volatile
        private var INSTANCE: DownloadRepository? = null

        fun getInstance(context: Context): DownloadRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadRepository(
                    AppDatabase.getInstance(context.applicationContext).downloadDao()
                ).also { INSTANCE = it }
            }
        }
    }
}
