package com.onyx.browser.data.repository

import android.content.Context
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.local.BookmarkDao
import com.onyx.browser.data.model.BookmarkItem
import kotlinx.coroutines.flow.Flow

class BookmarkRepository(
    private val bookmarkDao: BookmarkDao
) {
    fun getAllBookmarksFlow(): Flow<List<BookmarkItem>> = bookmarkDao.getAllBookmarksFlow()

    fun searchBookmarksFlow(query: String): Flow<List<BookmarkItem>> = bookmarkDao.searchAllBookmarksFlow(query)

    suspend fun insertBookmark(item: BookmarkItem): Long = bookmarkDao.insertBookmark(item)

    suspend fun updateBookmark(id: Long, title: String, url: String) = bookmarkDao.updateBookmark(id, title, url)

    suspend fun deleteBookmark(item: BookmarkItem) = bookmarkDao.deleteBookmark(item)

    suspend fun isBookmarked(url: String): Boolean = bookmarkDao.isBookmarked(url)

    companion object {
        @Volatile
        private var INSTANCE: BookmarkRepository? = null

        fun getInstance(context: Context): BookmarkRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BookmarkRepository(
                    AppDatabase.getInstance(context.applicationContext).bookmarkDao()
                ).also { INSTANCE = it }
            }
        }
    }
}
