package com.onyx.browser.userscript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.onyx.browser.download.DownloadHandler
import com.onyx.browser.download.DownloadTask
import com.onyx.browser.download.OnyxDownloadManager
import com.onyx.browser.web.OnyxWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * JavaScript interface bridge backing the userscript GM_* APIs.
 * Registered on WebView as `window.onyxGM`.
 */
class OnyxUserScriptBridge(
    private val webView: OnyxWebView,
    private val manager: UserScriptManager
) {
    data class ScriptSession(
        val scriptId: Long,
        val targetUrl: String
    )

    private data class DownloadSession(
        val token: String,
        val reqId: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tokens = ConcurrentHashMap<String, ScriptSession>()
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val activeDownloads = ConcurrentHashMap<String, Long>()
    private val downloadSessions = ConcurrentHashMap<Long, DownloadSession>()

    private val downloadListener = object : OnyxDownloadManager.DownloadEventListener {
        override fun onProgress(task: DownloadTask) {
            val session = downloadSessions[task.id] ?: return
            val payload = JSONObject().apply {
                put("loaded", task.downloadedBytes.get())
                put("total", task.totalBytes)
                put("progress", task.progressPercent)
            }
            deliverDownload(session.token, session.reqId, "progress", payload.toString())
        }

        override fun onCompleted(task: DownloadTask) {
            val session = downloadSessions.remove(task.id) ?: return
            activeDownloads.remove(session.reqId)
            val payload = JSONObject().apply {
                put("loaded", task.downloadedBytes.get())
                put("total", task.totalBytes)
                put("path", task.finalFilePath)
            }
            deliverDownload(session.token, session.reqId, "load", payload.toString())
        }

        override fun onFailed(task: DownloadTask, error: String) {
            val session = downloadSessions.remove(task.id) ?: return
            activeDownloads.remove(session.reqId)
            deliverDownloadError(session.token, session.reqId, error)
        }

        override fun onCancelled(taskId: Long) {
            val session = downloadSessions.remove(taskId) ?: return
            activeDownloads.remove(session.reqId)
            deliverDownloadError(session.token, session.reqId, "Download cancelled")
        }
    }

    init {
        OnyxDownloadManager.addEventListener(downloadListener)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun registerToken(token: String, scriptId: Long, targetUrl: String = "") {
        tokens[token] = ScriptSession(scriptId, targetUrl)
    }

    fun clearTokens() {
        tokens.clear()
        activeCalls.values.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
        activeDownloads.clear()
        downloadSessions.clear()
    }

    private fun scriptIdFor(token: String?, api: String): Long? {
        val session = token?.let { tokens[it] } ?: return null
        val pageUrl = session.targetUrl.ifEmpty {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                webView.url.orEmpty()
            } else ""
        }
        if (pageUrl.isBlank()) return session.scriptId
        return if (manager.isApiAllowed(session.scriptId, pageUrl, api)) session.scriptId else null
    }

    // region Values Storage
    @JavascriptInterface
    fun gmGetValue(token: String, key: String): String? {
        val scriptId = scriptIdFor(token, "getValue") ?: return null
        return manager.gmGetValue(scriptId, key)
    }

    @JavascriptInterface
    fun gmSetValue(token: String, key: String, value: String) {
        val scriptId = scriptIdFor(token, "setValue") ?: return
        manager.gmSetValue(scriptId, key, value)
    }

    @JavascriptInterface
    fun gmDeleteValue(token: String, key: String) {
        val scriptId = scriptIdFor(token, "deleteValue") ?: return
        manager.gmDeleteValue(scriptId, key)
    }

    @JavascriptInterface
    fun gmListValues(token: String): String {
        val scriptId = scriptIdFor(token, "listValues") ?: return "[]"
        val keys = manager.gmListValues(scriptId)
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        return arr.toString()
    }
    // endregion

    // region GM_xmlhttpRequest
    @JavascriptInterface
    fun gmXhr(token: String, reqId: String, detailsJson: String) {
        val scriptId = scriptIdFor(token, "xmlhttpRequest") ?: run {
            Log.w(TAG, "gmXhr rejected: missing script or API permission")
            return
        }

        scope.launch(Dispatchers.IO) {
            if (scriptIdFor(token, "xmlhttpRequest") == null) return@launch
            try {
                val details = JSONObject(detailsJson)
                val url = details.getString("url")

                if (!isConnectAllowed(scriptId, token, url)) {
                    Log.w(TAG, "gmXhr blocked (not in @connect): $url")
                    deliverXhrError(token, reqId, "URL not in @connect allow-list: $url")
                    return@launch
                }

                val method = details.optString("method", "GET").uppercase()
                val headers = details.optJSONObject("headers")
                val data = if (details.isNull("data")) null else details.optString("data")
                val timeoutMs = details.optLong("timeout", 0L)

                val builder = Request.Builder().url(url)
                var contentType: String? = null
                headers?.keys()?.forEach { name ->
                    val v = headers.getString(name)
                    if (name.equals("Content-Type", ignoreCase = true)) contentType = v
                    builder.header(name, v)
                }

                if (method == "GET" || method == "HEAD") {
                    builder.method(method, null)
                } else {
                    val body = (data ?: "").toRequestBody(contentType?.toMediaTypeOrNull())
                    builder.method(method, body)
                }

                val client = if (timeoutMs > 0) {
                    httpClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                } else {
                    httpClient
                }

                val call = client.newCall(builder.build())
                activeCalls[reqId] = call

                try {
                    call.execute().use { resp ->
                        val bodyText = resp.body?.string().orEmpty()
                        val headerText = resp.headers.joinToString("\r\n") { "${it.first}: ${it.second}" }
                        val payload = JSONObject().apply {
                            put("readyState", 4)
                            put("status", resp.code)
                            put("statusText", resp.message)
                            put("responseText", bodyText)
                            put("response", bodyText)
                            put("responseHeaders", headerText)
                            put("finalUrl", resp.request.url.toString())
                        }
                        deliverXhr(token, reqId, "load", payload.toString())
                    }
                } finally {
                    activeCalls.remove(reqId)
                }
            } catch (e: java.io.InterruptedIOException) {
                Log.w(TAG, "gmXhr timeout: ${e.message}")
                deliverXhr(token, reqId, "timeout", JSONObject().apply {
                    put("readyState", 4)
                    put("status", 0)
                    put("statusText", "timeout")
                }.toString())
            } catch (e: Exception) {
                Log.w(TAG, "gmXhr failed: ${e.message}")
                deliverXhrError(token, reqId, e.message ?: "Request failed")
            }
        }
    }

    @JavascriptInterface
    fun gmAbortXhr(token: String, reqId: String) {
        activeCalls.remove(reqId)?.cancel()
    }

    private fun isConnectAllowed(scriptId: Long, token: String, url: String): Boolean {
        val connects = manager.getById(scriptId)?.metadata?.connects ?: return false
        if (connects.isEmpty() || connects.any { it == "*" }) return true
        val host = try {
            URI(url).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        val targetUrl = tokens[token]?.targetUrl.orEmpty()
        val pageHost = try {
            Uri.parse(targetUrl).host?.lowercase()
        } catch (_: Exception) {
            null
        }
        if (pageHost != null && host == pageHost) return true
        return connects.any { entry ->
            val e = entry.lowercase()
            e == host || host == e.removePrefix("*.") || host.endsWith(".$e") ||
                    (e.startsWith("*.") && host.endsWith(e.substring(1)))
        }
    }

    private fun deliverXhrError(token: String, reqId: String, message: String) {
        val payload = JSONObject().apply {
            put("readyState", 4)
            put("status", 0)
            put("statusText", "error")
            put("error", message)
        }
        deliverXhr(token, reqId, "error", payload.toString())
    }

    private fun deliverXhr(token: String, reqId: String, event: String, payloadJson: String) {
        val js = "window.__onyxGM && window.__onyxGM.handleXhr(" +
                "${JSONObject.quote(reqId)}, ${JSONObject.quote(event)}, ${JSONObject.quote(payloadJson)});"
        scope.launch(Dispatchers.Main) {
            if (scriptIdFor(token, "xmlhttpRequest") != null) {
                webView.evaluateJavascript(js, null)
            }
        }
    }
    // endregion

    // region GM_download
    @JavascriptInterface
    fun gmDownload(token: String, reqId: String, detailsJson: String) {
        val scriptId = scriptIdFor(token, "download") ?: run {
            Log.w(TAG, "gmDownload rejected: missing script or API permission")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val details = JSONObject(detailsJson)
                val url = details.getString("url")

                if (!isWebUrl(url)) {
                    deliverDownloadError(token, reqId, "Invalid or non-web download URL: $url")
                    return@launch
                }

                if (!isConnectAllowed(scriptId, token, url)) {
                    Log.w(TAG, "gmDownload blocked (not in @connect): $url")
                    deliverDownloadError(token, reqId, "URL host not in @connect allow-list: $url")
                    return@launch
                }

                val targetUrl = tokens[token]?.targetUrl.orEmpty()
                val rawName = details.optString("name", "").trim()
                val fileName = if (rawName.isNotBlank()) {
                    DownloadHandler.sanitizeFileName(rawName)
                } else {
                    DownloadHandler.guessResolvedFileName(url, null, null, targetUrl)
                }

                val headers = details.optJSONObject("headers")
                var userAgent: String = ""
                withContext(Dispatchers.Main) {
                    userAgent = webView.settings.userAgentString
                }
                var cookies: String = ""
                var referer: String = targetUrl

                headers?.keys()?.forEach { hName ->
                    val hVal = headers.getString(hName)
                    when {
                        hName.equals("User-Agent", ignoreCase = true) -> userAgent = hVal
                        hName.equals("Cookie", ignoreCase = true) -> cookies = hVal
                        hName.equals("Referer", ignoreCase = true) -> referer = hVal
                    }
                }

                if (cookies.isBlank()) {
                    cookies = try {
                        android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
                    } catch (_: Exception) { "" }
                }

                withContext(Dispatchers.Main) {
                    val downloadId = OnyxDownloadManager.enqueueDownload(
                        context = webView.context,
                        url = url,
                        fileName = fileName,
                        mimeType = "",
                        userAgent = userAgent,
                        cookies = cookies,
                        referer = referer,
                        contentLength = 0L
                    )
                    activeDownloads[reqId] = downloadId
                    downloadSessions[downloadId] = DownloadSession(token, reqId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "gmDownload error: ${e.message}", e)
                deliverDownloadError(token, reqId, e.message ?: "Download failed")
            }
        }
    }

    @JavascriptInterface
    fun gmAbortDownload(token: String, reqId: String) {
        val downloadId = activeDownloads.remove(reqId) ?: return
        downloadSessions.remove(downloadId)
        OnyxDownloadManager.cancelDownload(downloadId)
    }

    private fun deliverDownloadError(token: String, reqId: String, message: String) {
        val payload = JSONObject().apply {
            put("error", "download_error")
            put("details", message)
        }
        deliverDownload(token, reqId, "error", payload.toString())
    }

    private fun deliverDownload(token: String, reqId: String, event: String, payloadJson: String) {
        val js = "window.__onyxGM && window.__onyxGM.handleDownload(" +
                "${JSONObject.quote(reqId)}, ${JSONObject.quote(event)}, ${JSONObject.quote(payloadJson)});"
        scope.launch(Dispatchers.Main) {
            if (scriptIdFor(token, "download") != null) {
                webView.evaluateJavascript(js, null)
            }
        }
    }
    // endregion

    // region Menu Commands
    @JavascriptInterface
    fun gmRegisterMenuCommand(token: String, caption: String, fnId: String) {
        if (scriptIdFor(token, "registerMenuCommand") == null) return
        scope.launch(Dispatchers.Main) {
            webView.registerUserScriptMenuCommand(caption, fnId)
        }
    }

    @JavascriptInterface
    fun gmUnregisterMenuCommand(token: String, fnId: String) {
        if (scriptIdFor(token, "unregisterMenuCommand") == null) return
        scope.launch(Dispatchers.Main) {
            webView.unregisterUserScriptMenuCommand(fnId)
        }
    }
    // endregion

    // region Navigation & System
    @JavascriptInterface
    fun gmOpenInTab(token: String, url: String, active: Boolean) {
        if (scriptIdFor(token, "openInTab") == null) return
        if (!isWebUrl(url)) return
        scope.launch(Dispatchers.Main) {
            val activity = webView.context as? com.onyx.browser.MainActivity
            activity?.openUrlInNewTab(url = url, isIncognito = webView.isIncognito)
        }
    }

    @JavascriptInterface
    fun gmSetClipboard(token: String, text: String) {
        if (scriptIdFor(token, "setClipboard") == null) return
        scope.launch(Dispatchers.Main) {
            val cm = webView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("userscript", text))
        }
    }

    @JavascriptInterface
    fun gmNotification(token: String, text: String) {
        if (scriptIdFor(token, "notification") == null) return
        scope.launch(Dispatchers.Main) {
            Toast.makeText(webView.context, text, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun gmLog(token: String, message: String) {
        if (scriptIdFor(token, "log") == null) return
        Log.d(TAG, "UserScript: $message")
    }
    // endregion

    companion object {
        const val JS_INTERFACE_NAME = "onyxGM"
        private const val TAG = "OnyxUserScriptBridge"

        fun isWebUrl(url: String): Boolean {
            val scheme = runCatching { URI(url.trim()).scheme }.getOrNull() ?: return false
            return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        }
    }
}
