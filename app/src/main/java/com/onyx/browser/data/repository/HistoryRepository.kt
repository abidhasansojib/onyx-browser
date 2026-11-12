package com.onyx.browser.data.repository

import android.content.Context
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.local.HistoryDao
import com.onyx.browser.data.model.HistoryItem
import kotlinx.coroutines.flow.Flow

class HistoryRepository(
    private val historyDao: HistoryDao
) {
    fun getAllHistoryFlow(): Flow<List<HistoryItem>> = historyDao.getAllHistoryFlow()

    fun searchHistoryFlow(query: String): Flow<List<HistoryItem>> = historyDao.searchHistoryFlow(query)

    suspend fun insertHistory(item: HistoryItem): Long = historyDao.insertHistory(item)

    suspend fun deleteHistory(item: HistoryItem) = historyDao.deleteHistory(item)

    suspend fun clearAllHistory() = historyDao.clearAllHistory()

    suspend fun deleteHistorySince(cutoffTime: Long) = historyDao.deleteHistorySince(cutoffTime)

    companion object {
        @Volatile
        private var INSTANCE: HistoryRepository? = null

        fun getInstance(context: Context): HistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HistoryRepository(
                    AppDatabase.getInstance(context.applicationContext).historyDao()
                ).also { INSTANCE = it }
            }
        }
    }
}
