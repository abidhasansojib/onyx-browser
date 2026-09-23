package com.onyx.browser.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DownloadEngine(
    private val context: Context,
    private val limiter: TokenBucketLimiter,
    private val onProgress: (task: DownloadTask) -> Unit,
    private val onCompleted: (task: DownloadTask) -> Unit,
    private val onFailed: (task: DownloadTask, error: String) -> Unit,
    private val onPaused: (task: DownloadTask) -> Unit
) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(12, 3, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun executeDownload(task: DownloadTask) = withContext(Dispatchers.IO) {
        try {
            task.status = DownloadTask.STATUS_RUNNING
            task.errorMessage = null
            onProgress(task)

            // Step 1: Probe server capabilities (HEAD / Range GET)
            if (task.chunks.isEmpty()) {
                probeServer(task)
            }

            // Step 2: Ensure destination directory and file
            val tempFile = File(task.tempFilePath)
            tempFile.parentFile?.mkdirs()

            if (!tempFile.exists()) {
                tempFile.createNewFile()
                if (task.totalBytes > 0L) {
                    try {
                        RandomAccessFile(tempFile, "rw").use { it.setLength(task.totalBytes) }
                    } catch (_: Exception) {}
                }
            }

            // Step 3: Allocate chunks if not already allocated
            if (task.chunks.isEmpty()) {
                allocateChunks(task)
            }

            // Step 4: Run download (Parallel or Single stream)
            if (task.isRangeSupported && task.chunks.size > 1) {
                runParallelDownload(task, tempFile)
            } else {
                runSingleStreamDownload(task, tempFile)
            }

            if (!isActive || task.status == DownloadTask.STATUS_PAUSED || task.status == DownloadTask.STATUS_CANCELLED) {
                return@withContext
            }

            // Step 5: Check integrity (calculate SHA-256 and MD5)
            calculateHashes(task, tempFile)

            // Step 6: Publish to public Downloads (MediaStore on Android 10+, direct on legacy)
            val publishedPath = publishFile(task, tempFile)
            task.finalFilePath = publishedPath
            task.status = DownloadTask.STATUS_COMPLETED
            task.speedBytesPerSec = 0L
            task.etaSeconds = 0L

            // Update database
            val database = AppDatabase.getInstance(context)
            database.downloadDao().markCompleted(
                id = task.id,
                status = DownloadItem.STATUS_COMPLETED,
                filePath = publishedPath,
                fileSize = if (task.totalBytes > 0) task.totalBytes else tempFile.length(),
                downloadedBytes = task.downloadedBytes.get(),
                sha256 = task.sha256,
                md5 = task.md5
            )

            onCompleted(task)

        } catch (e: FileChangedException) {
            // Server file changed (HTTP 412) -> reset and restart fresh
            File(task.tempFilePath).delete()
            task.chunks.clear()
            task.downloadedBytes.set(0L)
            task.totalBytes = 0L
            task.etag = ""
            task.lastModified = ""
            executeDownload(task)
        } catch (e: Exception) {
            if (!isActive || task.status == DownloadTask.STATUS_PAUSED) {
                task.status = DownloadTask.STATUS_PAUSED
                onPaused(task)
                return@withContext
            }
            if (task.status == DownloadTask.STATUS_CANCELLED) {
                File(task.tempFilePath).delete()
                return@withContext
            }

            val errorMsg = e.message ?: "Download failed"
            task.status = DownloadTask.STATUS_FAILED
            task.errorMessage = errorMsg
            task.speedBytesPerSec = 0L
            task.etaSeconds = -1L

            val database = AppDatabase.getInstance(context)
            database.downloadDao().updateStatusById(task.id, DownloadItem.STATUS_FAILED)

            onFailed(task, errorMsg)
        }
    }

    private fun probeServer(task: DownloadTask) {
        val headReq = buildBaseRequest(task.url, task)
            .head()
            .build()

        var response: Response? = null
        try {
            response = httpClient.newCall(headReq).execute()
        } catch (_: Exception) {}

        // Fallback to GET with Range: bytes=0-0 if HEAD fails or returned 405 Method Not Allowed
        if (response == null || !response.isSuccessful || response.code == 405) {
            try {
                response?.close()
                val getProbeReq = buildBaseRequest(task.url, task)
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                response = httpClient.newCall(getProbeReq).execute()
            } catch (_: Exception) {}
        }

        response?.use { res ->
            task.etag = res.header("ETag")?.trim('"', ' ') ?: ""
            task.lastModified = res.header("Last-Modified") ?: ""

            // Inspect Content-Disposition for RFC 6266 filename
            val contentDisp = res.header("Content-Disposition")
            if (!contentDisp.isNullOrBlank()) {
                val parsedName = parseContentDispositionFilename(contentDisp)
                if (parsedName.isNotBlank()) {
                    task.fileName = sanitizeFileName(parsedName)
                }
            }

            // Inspect MIME Type
            val detectedMime = res.header("Content-Type")?.substringBefore(';')?.trim()
            if (!detectedMime.isNullOrBlank() && task.mimeType.isBlank()) {
                task.mimeType = detectedMime
            }

            // Range support & Content-Length
            val acceptRanges = res.header("Accept-Ranges")
            val contentRange = res.header("Content-Range")
            val isChunked = "chunked".equals(res.header("Transfer-Encoding"), ignoreCase = true)

            var length = res.header("Content-Length")?.toLongOrNull() ?: -1L
            if (length <= 0L && !contentRange.isNullOrBlank()) {
                val match = Regex(".*/(\\d+)").find(contentRange)
                match?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { length = it }
            }

            if (task.totalBytes <= 0L && length > 0L) {
                task.totalBytes = length
            }

            task.isRangeSupported = !isChunked && (
                "bytes".equals(acceptRanges, ignoreCase = true) ||
                res.code == 206 ||
                !contentRange.isNullOrBlank()
            ) && task.totalBytes > 0L
        }
    }

    private fun allocateChunks(task: DownloadTask) {
        val total = task.totalBytes
        if (!task.isRangeSupported || total < 5L * 1024L * 1024L) {
            // Single chunk
            task.chunks.add(
                DownloadChunk(
                    chunkId = 0,
                    startByte = 0L,
                    currentByte = 0L,
                    endByte = if (total > 0L) total - 1L else Long.MAX_VALUE,
                    status = DownloadChunk.STATUS_PENDING
                )
            )
            return
        }

        // Parallel chunk count scaling: 2 to 6 chunks
        val numChunks = when {
            total < 20L * 1024L * 1024L -> 2
            total < 50L * 1024L * 1024L -> 4
            else -> 6
        }

        val chunkSize = total / numChunks
        for (i in 0 until numChunks) {
            val start = i * chunkSize
            val end = if (i == numChunks - 1) total - 1L else (start + chunkSize - 1L)
            task.chunks.add(
                DownloadChunk(
                    chunkId = i,
                    startByte = start,
                    currentByte = start,
                    endByte = end,
                    status = DownloadChunk.STATUS_PENDING
                )
            )
        }
    }

    private suspend fun runParallelDownload(task: DownloadTask, tempFile: File) = coroutineScope {
        var lastReportTime = System.currentTimeMillis()
        var lastReportBytes = task.downloadedBytes.get()

        val jobs = task.chunks.map { chunk ->
            async(Dispatchers.IO) {
                if (chunk.isCompleted) return@async

                var retryCount = 0
                val maxRetries = 4

                while (isActive && !chunk.isCompleted && task.status == DownloadTask.STATUS_RUNNING) {
                    try {
                        chunk.status = DownloadChunk.STATUS_RUNNING
                        downloadChunkSegment(task, chunk, tempFile)
                        chunk.status = DownloadChunk.STATUS_COMPLETED
                        break
                    } catch (fce: FileChangedException) {
                        throw fce
                    } catch (e: Exception) {
                        if (!isActive || task.status != DownloadTask.STATUS_RUNNING) break
                        retryCount++
                        if (retryCount > maxRetries || isFatalHttpError(e)) {
                            chunk.status = DownloadChunk.STATUS_FAILED
                            throw e
                        }
                        // Exponential backoff: 1s, 2s, 4s, 8s
                        val backoffMs = (1000L * (1L shl (retryCount - 1))).coerceAtMost(8000L)
                        delay(backoffMs)
                    }
                }
            }
        }

        // Monitoring loop to report speed, progress and ETA
        val monitorJob = async(Dispatchers.Default) {
            while (isActive && task.status == DownloadTask.STATUS_RUNNING) {
                delay(500)
                val now = System.currentTimeMillis()
                val elapsedMs = now - lastReportTime
                if (elapsedMs >= 500) {
                    val currentBytes = task.downloadedBytes.get()
                    val deltaBytes = (currentBytes - lastReportBytes).coerceAtLeast(0L)
                    val speed = (deltaBytes * 1000L) / elapsedMs
                    task.speedBytesPerSec = speed

                    if (speed > 0 && task.totalBytes > currentBytes) {
                        task.etaSeconds = (task.totalBytes - currentBytes) / speed
                    } else if (task.totalBytes > 0 && currentBytes >= task.totalBytes) {
                        task.etaSeconds = 0L
                    }

                    lastReportTime = now
                    lastReportBytes = currentBytes
                    onProgress(task)
                }
            }
        }

        jobs.awaitAll()
        monitorJob.cancel()
    }

    private suspend fun downloadChunkSegment(task: DownloadTask, chunk: DownloadChunk, tempFile: File) {
        val rangeHeader = "bytes=${chunk.currentByte}-${chunk.endByte}"
        val reqBuilder = buildBaseRequest(task.url, task)
            .header("Range", rangeHeader)

        if (chunk.currentByte > chunk.startByte) {
            if (task.etag.isNotBlank()) reqBuilder.header("If-Match", task.etag)
            if (task.lastModified.isNotBlank()) reqBuilder.header("If-Unmodified-Since", task.lastModified)
        }

        val request = reqBuilder.build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 412) {
                throw FileChangedException("Remote file changed (HTTP 412 Precondition Failed)")
            }
            if (response.code != 206 && response.code != 200) {
                throw IllegalStateException("Unexpected HTTP code ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val inStream = body.byteStream()
            val raf = RandomAccessFile(tempFile, "rw")

            try {
                raf.seek(chunk.currentByte)
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int

                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    if (task.status != DownloadTask.STATUS_RUNNING) {
                        break
                    }
                    limiter.acquire(bytesRead)
                    raf.write(buffer, 0, bytesRead)
                    chunk.currentByte += bytesRead
                    task.downloadedBytes.addAndGet(bytesRead.toLong())
                }
            } finally {
                try { raf.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun runSingleStreamDownload(task: DownloadTask, tempFile: File) = withContext(Dispatchers.IO) {
        val chunk = task.chunks.firstOrNull() ?: DownloadChunk(0, 0L, 0L, Long.MAX_VALUE)
        var lastReportTime = System.currentTimeMillis()
        var lastReportBytes = task.downloadedBytes.get()

        val reqBuilder = buildBaseRequest(task.url, task)
        if (chunk.currentByte > 0L && task.isRangeSupported) {
            reqBuilder.header("Range", "bytes=${chunk.currentByte}-")
            if (task.etag.isNotBlank()) reqBuilder.header("If-Match", task.etag)
            if (task.lastModified.isNotBlank()) reqBuilder.header("If-Unmodified-Since", task.lastModified)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 412) {
                throw FileChangedException("Remote file changed (HTTP 412)")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} error")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            if (task.totalBytes <= 0L && body.contentLength() > 0L) {
                task.totalBytes = body.contentLength()
            }

            val inStream = body.byteStream()
            val raf = RandomAccessFile(tempFile, "rw")

            try {
                val append = chunk.currentByte > 0L && response.code == 206
                if (append) {
                    raf.seek(chunk.currentByte)
                } else {
                    raf.seek(0L)
                    chunk.currentByte = 0L
                    task.downloadedBytes.set(0L)
                }

                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int

                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    if (task.status != DownloadTask.STATUS_RUNNING) {
                        break
                    }
                    limiter.acquire(bytesRead)
                    raf.write(buffer, 0, bytesRead)
                    chunk.currentByte += bytesRead
                    task.downloadedBytes.addAndGet(bytesRead.toLong())

                    val now = System.currentTimeMillis()
                    val elapsedMs = now - lastReportTime
                    if (elapsedMs >= 500) {
                        val currentBytes = task.downloadedBytes.get()
                        val deltaBytes = (currentBytes - lastReportBytes).coerceAtLeast(0L)
                        val speed = (deltaBytes * 1000L) / elapsedMs
                        task.speedBytesPerSec = speed

                        if (speed > 0 && task.totalBytes > currentBytes) {
                            task.etaSeconds = (task.totalBytes - currentBytes) / speed
                        }
                        lastReportTime = now
                        lastReportBytes = currentBytes
                        onProgress(task)
                    }
                }
            } finally {
                try { raf.close() } catch (_: Exception) {}
            }
        }
    }

    private fun calculateHashes(task: DownloadTask, file: File) {
        try {
            val sha256Digest = MessageDigest.getInstance("SHA-256")
            val md5Digest = MessageDigest.getInstance("MD5")

            FileInputStream(file).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    sha256Digest.update(buffer, 0, read)
                    md5Digest.update(buffer, 0, read)
                }
            }

            task.sha256 = bytesToHex(sha256Digest.digest())
            task.md5 = bytesToHex(md5Digest.digest())
        } catch (_: Exception) {}
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun publishFile(task: DownloadTask, tempFile: File): String {
        val finalFileName = resolveUniqueFileName(task.fileName)
        val mime = if (task.mimeType.isNotBlank()) task.mimeType else "application/octet-stream"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ Scoped Storage (MediaStore.Downloads)
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(tempFile).use { `in` ->
                        `in`.copyTo(out)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                tempFile.delete()
                val publicFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    finalFileName
                )
                return publicFile.absolutePath
            }
        }

        // Android 8-9 legacy storage fallback or MediaStore fallback
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val destFile = File(downloadsDir, finalFileName)

        if (!tempFile.renameTo(destFile)) {
            FileInputStream(tempFile).use { `in` ->
                FileOutputStream(destFile).use { out ->
                    `in`.copyTo(out)
                }
            }
            tempFile.delete()
        }

        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf(mime),
                null
            )
        } catch (_: Exception) {}

        return destFile.absolutePath
    }

    private fun resolveUniqueFileName(baseName: String): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var file = File(downloadsDir, baseName)
        if (!file.exists()) return baseName

        val nameWithoutExt = baseName.substringBeforeLast('.', baseName)
        val ext = if (baseName.contains('.')) ".${baseName.substringAfterLast('.')}" else ""
        var count = 1

        while (file.exists()) {
            val candidate = "$nameWithoutExt ($count)$ext"
            file = File(downloadsDir, candidate)
            count++
        }
        return file.name
    }

    private fun buildBaseRequest(url: String, task: DownloadTask): Request.Builder {
        val builder = Request.Builder().url(url)
        if (task.userAgent.isNotBlank()) {
            builder.header("User-Agent", task.userAgent)
        }
        if (task.cookies.isNotBlank()) {
            builder.header("Cookie", task.cookies)
        }
        if (task.referer.isNotBlank()) {
            builder.header("Referer", task.referer)
        }
        return builder
    }

    private fun parseContentDispositionFilename(contentDisposition: String): String {
        try {
            // RFC 5987 / RFC 6266 filename*=UTF-8''...
            val patternExtended = Pattern.compile("(?i)filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')([^;]+)")
            val matcherExt = patternExtended.matcher(contentDisposition)
            if (matcherExt.find()) {
                val encoded = matcherExt.group(1)?.trim('"', '\'', ' ') ?: ""
                return URLDecoder.decode(encoded, "UTF-8")
            }

            // Standard filename="..."
            val patternStandard = Pattern.compile("(?i)filename\\s*=\\s*\"?([^\";]+)\"?")
            val matcherStd = patternStandard.matcher(contentDisposition)
            if (matcherStd.find()) {
                return matcherStd.group(1)?.trim('"', ' ') ?: ""
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (clean.isBlank()) "download_${System.currentTimeMillis()}" else clean
    }

    private fun isFatalHttpError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("401") || msg.contains("403") || msg.contains("404") || msg.contains("410")
    }

    class FileChangedException(message: String) : Exception(message)
}
