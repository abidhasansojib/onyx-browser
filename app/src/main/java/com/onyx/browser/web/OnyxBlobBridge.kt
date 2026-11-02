package com.onyx.browser.web

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/**
 * JavaScript interface bridge for receiving converted blob data URLs from
 * WebView FileReader callbacks and passing them to DownloadHandler.
 */
class OnyxBlobBridge(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    @JavascriptInterface
    fun onBlobDownloadedWithContext(
        dataUrl: String,
        fileName: String,
        mimeType: String,
        pageUrl: String
    ) {
        // Guard against OOM: a malicious page could send an enormous data URL.
        // The size check mirrors the same guard in handleDataUriDownload.
        val MAX_DATA_URL_BYTES = 64 * 1024 * 1024 // 64 MB safe ceiling
        if (dataUrl.length > MAX_DATA_URL_BYTES) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Blob is too large to download this way", Toast.LENGTH_LONG).show()
            }
            return
        }
        // Sanitize the file name coming from JS land before handing it to the download system.
        val safeFileName = DownloadHandler.sanitizeFileName(fileName.take(255))
        val safePageUrl = if (pageUrl.startsWith("http://", ignoreCase = true) ||
            pageUrl.startsWith("https://", ignoreCase = true)) pageUrl else ""
        DownloadHandler.handleDataUriDownload(
            context = context,
            coroutineScope = coroutineScope,
            dataUri = dataUrl,
            mimeType = mimeType,
            suggestedFileName = safeFileName,
            originalPageUrl = safePageUrl
        )
    }

    @JavascriptInterface
    fun onBlobDownloaded(dataUrl: String, fileName: String, mimeType: String) {
        onBlobDownloadedWithContext(dataUrl, fileName, mimeType, "")
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

    /**
     * Creates a modern [WebViewCompat.WebMessageListener] for zero-reflection,
     * UI-thread event delivery without exposing Java reflection objects on window.
     */
    fun createWebMessageListener(): WebViewCompat.WebMessageListener {
        return WebViewCompat.WebMessageListener { _: WebView, message: WebMessageCompat, _: Uri, _: Boolean, _: JavaScriptReplyProxy ->
            val data = message.data ?: return@WebMessageListener
            try {
                val json = JSONObject(data)
                when (json.optString("type")) {
                    "success" -> {
                        val dataUrl = json.optString("dataUrl")
                        val fileName = json.optString("fileName")
                        val mimeType = json.optString("mimeType")
                        val pageUrl = json.optString("pageUrl")
                        onBlobDownloadedWithContext(dataUrl, fileName, mimeType, pageUrl)
                    }
                    "failed" -> {
                        val error = json.optString("error")
                        val blobUrl = json.optString("blobUrl")
                        val fileName = json.optString("fileName")
                        val mimeType = json.optString("mimeType")
                        val pageUrl = json.optString("pageUrl")
                        onBlobFailedWithContext(error, blobUrl, fileName, mimeType, pageUrl)
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}
