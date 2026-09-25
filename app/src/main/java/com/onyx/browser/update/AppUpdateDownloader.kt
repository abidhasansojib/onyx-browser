package com.onyx.browser.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppUpdateDownloader(private val context: Context) {

    private val isCancelled = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun cancel() {
        isCancelled.set(true)
    }

    suspend fun downloadApk(
        downloadUrl: String,
        targetFileName: String,
        onProgress: (bytesRead: Long, totalBytes: Long, bytesPerSec: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val updateDir = File(context.cacheDir, "updates").apply {
            if (!exists()) mkdirs()
        }

        // Clean up any stale APKs in updates directory
        updateDir.listFiles()?.forEach { file ->
            try { file.delete() } catch (_: Exception) {}
        }

        val tempFile = File(updateDir, "$targetFileName.part")
        val finalFile = File(updateDir, targetFileName)

        if (tempFile.exists()) tempFile.delete()
        if (finalFile.exists()) finalFile.delete()

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "OnyxBrowser/${context.packageName}")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Server returned HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("Empty response body"))
            val totalBytes = body.contentLength()

            val buffer = ByteArray(8192)
            var bytesRead = 0L
            var lastProgressTime = System.currentTimeMillis()
            var lastBytesRead = 0L
            var currentSpeed = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        if (isCancelled.get()) {
                            tempFile.delete()
                            return@withContext Result.failure(IOException("Download cancelled by user"))
                        }

                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        bytesRead += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastProgressTime
                        if (elapsed >= 300) {
                            val intervalBytes = bytesRead - lastBytesRead
                            currentSpeed = if (elapsed > 0) (intervalBytes * 1000) / elapsed else 0L
                            lastBytesRead = bytesRead
                            lastProgressTime = now
                            onProgress(bytesRead, totalBytes, currentSpeed)
                        }
                    }
                }
            }

            if (isCancelled.get()) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Download cancelled by user"))
            }

            // Final progress update
            onProgress(bytesRead, totalBytes, 0L)

            // Verify size if Content-Length was known
            if (totalBytes > 0 && tempFile.length() != totalBytes) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Downloaded file size mismatch"))
            }

            if (!tempFile.renameTo(finalFile)) {
                // If rename fails, copy and delete
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(finalFile)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }
}
