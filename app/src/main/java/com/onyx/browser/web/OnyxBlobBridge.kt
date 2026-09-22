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
    fun onBlobFailed(error: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Blob download failed: $error", Toast.LENGTH_LONG).show()
        }
    }
}
