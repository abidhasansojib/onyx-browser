package com.onyx.browser.data

import com.onyx.browser.data.local.BookmarkDao
import com.onyx.browser.data.local.DownloadDao
import com.onyx.browser.data.local.HistoryDao
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.repository.BookmarkRepository
import com.onyx.browser.data.repository.DownloadRepository
import com.onyx.browser.data.repository.HistoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryLayerTest {

    @Test
    fun `HistoryRepository delegates all operations to HistoryDao`() = runTest {
        val historyDao = mockk<HistoryDao>()
        val repository = HistoryRepository(historyDao)
        val sampleItem = HistoryItem(id = 1L, url = "https://example.com", title = "Example", visitTime = 1000L)

        every { historyDao.getAllHistoryFlow() } returns flowOf(listOf(sampleItem))
        every { historyDao.searchHistoryFlow("example") } returns flowOf(listOf(sampleItem))
        coEvery { historyDao.insertHistory(sampleItem) } returns 1L
        coEvery { historyDao.deleteHistory(sampleItem) } just Runs
        coEvery { historyDao.clearAllHistory() } just Runs
        coEvery { historyDao.deleteHistorySince(500L) } just Runs

        assertEquals(listOf(sampleItem), repository.getAllHistoryFlow().first())
        assertEquals(listOf(sampleItem), repository.searchHistoryFlow("example").first())
        assertEquals(1L, repository.insertHistory(sampleItem))
        repository.deleteHistory(sampleItem)
        repository.clearAllHistory()
        repository.deleteHistorySince(500L)

        verify(exactly = 1) { historyDao.getAllHistoryFlow() }
        verify(exactly = 1) { historyDao.searchHistoryFlow("example") }
        coVerify(exactly = 1) { historyDao.insertHistory(sampleItem) }
        coVerify(exactly = 1) { historyDao.deleteHistory(sampleItem) }
        coVerify(exactly = 1) { historyDao.clearAllHistory() }
        coVerify(exactly = 1) { historyDao.deleteHistorySince(500L) }
    }

    @Test
    fun `BookmarkRepository delegates all operations to BookmarkDao`() = runTest {
        val bookmarkDao = mockk<BookmarkDao>()
        val repository = BookmarkRepository(bookmarkDao)
        val sampleBookmark = BookmarkItem(id = 2L, url = "https://onyx.dev", title = "Onyx", createdAt = 2000L)

        every { bookmarkDao.getAllBookmarksFlow() } returns flowOf(listOf(sampleBookmark))
        every { bookmarkDao.searchAllBookmarksFlow("onyx") } returns flowOf(listOf(sampleBookmark))
        coEvery { bookmarkDao.insertBookmark(sampleBookmark) } returns 2L
        coEvery { bookmarkDao.updateBookmark(2L, "Updated", "https://onyx.dev/docs") } just Runs
        coEvery { bookmarkDao.deleteBookmark(sampleBookmark) } just Runs
        coEvery { bookmarkDao.isBookmarked("https://onyx.dev") } returns true

        assertEquals(listOf(sampleBookmark), repository.getAllBookmarksFlow().first())
        assertEquals(listOf(sampleBookmark), repository.searchBookmarksFlow("onyx").first())
        assertEquals(2L, repository.insertBookmark(sampleBookmark))
        repository.updateBookmark(2L, "Updated", "https://onyx.dev/docs")
        repository.deleteBookmark(sampleBookmark)
        assertTrue(repository.isBookmarked("https://onyx.dev"))

        verify(exactly = 1) { bookmarkDao.getAllBookmarksFlow() }
        verify(exactly = 1) { bookmarkDao.searchAllBookmarksFlow("onyx") }
        coVerify(exactly = 1) { bookmarkDao.insertBookmark(sampleBookmark) }
        coVerify(exactly = 1) { bookmarkDao.updateBookmark(2L, "Updated", "https://onyx.dev/docs") }
        coVerify(exactly = 1) { bookmarkDao.deleteBookmark(sampleBookmark) }
        coVerify(exactly = 1) { bookmarkDao.isBookmarked("https://onyx.dev") }
    }

    @Test
    fun `DownloadRepository delegates all operations to DownloadDao`() = runTest {
        val downloadDao = mockk<DownloadDao>()
        val repository = DownloadRepository(downloadDao)
        val sampleDownload = DownloadItem(
            id = 3L,
            url = "https://onyx.dev/app.apk",
            fileName = "app.apk",
            filePath = "/storage/emulated/0/Download/app.apk",
            mimeType = "application/vnd.android.package-archive",
            fileSize = 4096L
        )

        every { downloadDao.getAllDownloadsFlow() } returns flowOf(listOf(sampleDownload))
        coEvery { downloadDao.getDownloadById(3L) } returns sampleDownload
        coEvery { downloadDao.insertDownload(sampleDownload) } returns 3L
        coEvery { downloadDao.updateDownload(sampleDownload) } just Runs
        coEvery { downloadDao.deleteDownload(sampleDownload) } just Runs
        coEvery { downloadDao.clearAllDownloads() } just Runs

        assertEquals(listOf(sampleDownload), repository.getAllDownloadsFlow().first())
        assertEquals(listOf(sampleDownload), repository.getAllDownloads())
        assertEquals(sampleDownload, repository.getDownloadById(3L))
        assertEquals(3L, repository.insertDownload(sampleDownload))
        repository.updateDownload(sampleDownload)
        repository.deleteDownload(sampleDownload)
        repository.clearAllDownloads()

        verify(exactly = 2) { downloadDao.getAllDownloadsFlow() }
        coVerify(exactly = 1) { downloadDao.getDownloadById(3L) }
        coVerify(exactly = 1) { downloadDao.insertDownload(sampleDownload) }
        coVerify(exactly = 1) { downloadDao.updateDownload(sampleDownload) }
        coVerify(exactly = 1) { downloadDao.deleteDownload(sampleDownload) }
        coVerify(exactly = 1) { downloadDao.clearAllDownloads() }
    }
}
