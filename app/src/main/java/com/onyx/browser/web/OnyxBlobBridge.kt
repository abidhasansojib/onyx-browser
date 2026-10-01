package com.onyx.browser.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope

/**
 * JavaScript interface bridge for receiving converted blob data URLs from
 * WebView FileReader callbacks and passing them to DownloadHandler.
 */
class OnyxBlobBridge(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    @JavascriptInterface
    fun onBlobDownloaded(dataUrl: String, fileName: String, mimeType: String) {
        // Guard against OOM: a malicious page could send an enormous data URL.
        // The size check mirrors the same guard in handleDataUriDownload.
        val MAX_DATA_URL_BYTES = 256 * 1024 * 1024 // 256 MB
        if (dataUrl.length > MAX_DATA_URL_BYTES) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Blob is too large to download this way", Toast.LENGTH_LONG).show()
            }
            return
        }
        // Sanitize the file name coming from JS land before handing it to the download system.
        val safeFileName = DownloadHandler.sanitizeFileName(fileName.take(255))
        DownloadHandler.handleDataUriDownload(
            context = context,
            coroutineScope = coroutineScope,
            dataUri = dataUrl,
            mimeType = mimeType,
            suggestedFileName = safeFileName
        )
    }

    @JavascriptInterface
    fun onBlobFailedWithContext(
        error: String,
        blobUrl: String,
        fileName: String,
        mimeType: String,
        pageUrl: String
    ) {
        // Sanitize fileName and only allow http/https pageUrl to prevent injection
        val safeFileName = DownloadHandler.sanitizeFileName(fileName.take(255))
        val safePageUrl = if (pageUrl.startsWith("http://", ignoreCase = true) ||
            pageUrl.startsWith("https://", ignoreCase = true)) pageUrl else ""
        DownloadHandler.handleBlobFallback(
            context = context,
            coroutineScope = coroutineScope,
            error = error,
            blobUrl = blobUrl,
            fileName = safeFileName,
            mimeType = mimeType,
            pageUrl = safePageUrl
        )
    }

    @JavascriptInterface
    fun onBlobFailed(error: String) {
        onBlobFailedWithContext(
            error = error,
            blobUrl = "",
            fileName = "",
            mimeType = "",
            pageUrl = ""
        )
    }
}
