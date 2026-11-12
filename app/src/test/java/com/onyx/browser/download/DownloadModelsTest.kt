package com.onyx.browser.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DownloadModelsTest {

    @Test
    fun `TokenBucketLimiter configures rate limit and handles unlimited or zero byte acquisitions`() = runTest {
        val limiter = TokenBucketLimiter()
        assertEquals(0L, limiter.getLimit())

        // Unlimited rate returns immediately
        limiter.acquire(64 * 1024)

        limiter.setLimit(100_000L)
        assertEquals(100_000L, limiter.getLimit())

        // Non-positive bytes or within initial burst capacity returns without error
        limiter.acquire(0)
        limiter.acquire(-10)
        limiter.acquire(50_000)

        limiter.setLimit(0L)
        assertEquals(0L, limiter.getLimit())
    }

    @Test
    fun `DownloadChunk calculates byte ranges, progress, and completion state`() {
        val chunk = DownloadChunk(
            chunkId = 0,
            startByte = 100L,
            currentByte = 100L,
            endByte = 199L,
            status = DownloadChunk.STATUS_PENDING
        )

        assertEquals(100L, chunk.totalBytes)
        assertEquals(0L, chunk.downloadedBytes)
        assertFalse(chunk.isCompleted)

        chunk.currentByte = 150L
        chunk.status = DownloadChunk.STATUS_RUNNING
        assertEquals(50L, chunk.downloadedBytes)
        assertFalse(chunk.isCompleted)

        chunk.currentByte = 200L
        chunk.status = DownloadChunk.STATUS_COMPLETED
        assertEquals(100L, chunk.downloadedBytes)
        assertTrue(chunk.isCompleted)
    }

    @Test
    fun `DownloadChunk handles invalid or inverted byte ranges safely`() {
        val invertedChunk = DownloadChunk(
            chunkId = 1,
            startByte = 500L,
            currentByte = 400L,
            endByte = 300L
        )
        assertEquals(0L, invertedChunk.totalBytes)
        assertEquals(0L, invertedChunk.downloadedBytes)
        assertTrue(invertedChunk.isCompleted)
    }

    @Test
    fun `DownloadTask computes progressPercent, indeterminate state, and POST metadata`() {
        val indeterminateTask = DownloadTask(
            id = 42L,
            url = "https://example.com/file.bin",
            fileName = "file.bin",
            mimeType = "application/octet-stream",
            totalBytes = 0L
        )
        assertTrue(indeterminateTask.isIndeterminate)
        assertEquals(-1, indeterminateTask.progressPercent)
        assertFalse(indeterminateTask.isPost)
        assertTrue(indeterminateTask.notificationId >= 0)

        val knownSizeTask = DownloadTask(
            id = 99L,
            url = "https://example.com/archive.zip",
            fileName = "archive.zip",
            mimeType = "application/zip",
            totalBytes = 1000L,
            downloadedBytes = AtomicLong(250L),
            postBody = "token=abc",
            postContentType = "application/x-www-form-urlencoded"
        )
        assertFalse(knownSizeTask.isIndeterminate)
        assertEquals(25, knownSizeTask.progressPercent)
        assertTrue(knownSizeTask.isPost)

        knownSizeTask.downloadedBytes.set(1500L)
        assertEquals(100, knownSizeTask.progressPercent)
    }
}
