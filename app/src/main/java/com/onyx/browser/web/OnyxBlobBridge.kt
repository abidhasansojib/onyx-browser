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
        DownloadHandler.handleDataUriDownload(
            context = context,
            coroutineScope = coroutineScope,
            dataUri = dataUrl,
            mimeType = mimeType,
            suggestedFileName = fileName
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
        DownloadHandler.handleBlobFallback(
            context = context,
            coroutineScope = coroutineScope,
            error = error,
            blobUrl = blobUrl,
            fileName = fileName,
            mimeType = mimeType,
            pageUrl = pageUrl
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
