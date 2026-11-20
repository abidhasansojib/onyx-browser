package com.onyx.browser.userscript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.widget.Toast
import com.onyx.browser.download.DownloadEngineManager
import com.onyx.browser.web.BrowserWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * JavaScript interface bridge backing the userscript GM_* APIs.
 * Registered on WebView as `window.onyxGM`.
 */
class UserScriptBridge(
    private val webView: BrowserWebView,
    private val manager: UserScriptManager
) {
    data class ScriptSession(
        val scriptId: Long,
        val targetUrl: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tokens = ConcurrentHashMap<String, ScriptSession>()
    private val activeCalls = ConcurrentHashMap<String, Call>()

    private val valueChangeListener = object : UserScriptManager.ValueChangeListener {
        override fun onValueChanged(scriptId: Long, key: String, oldValue: String?, newValue: String?) {
            val oldValStr = if (oldValue != null) JSONObject.quote(oldValue) else "null"
            val newValStr = if (newValue != null) JSONObject.quote(newValue) else "null"
            val safeKey = JSONObject.quote(key)
            val js = "try { " +
                    "if (window.__usValueChanged) window.__usValueChanged('us_$scriptId', $safeKey, $newValStr); " +
                    "if (window.__onyxGM && window.__onyxGM.notifyValueChanged) window.__onyxGM.notifyValueChanged($scriptId, $safeKey, $oldValStr, $newValStr); " +
                    "} catch (_) {}"
            scope.launch(Dispatchers.Main) {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    init {
        manager.addValueChangeListener(valueChangeListener)
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

    fun updateTargetUrlForAll(newUrl: String) {
        tokens.entries.forEach { (tok, session) ->
            tokens[tok] = session.copy(targetUrl = newUrl)
        }
    }

    fun clearTokens() {
        tokens.clear()
        activeCalls.values.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
    }

    fun destroy() {
        clearTokens()
        manager.removeValueChangeListener(valueChangeListener)
        scope.cancel()
    }

    private fun resolveScriptId(tokenOrKey: String?): Long? {
        if (tokenOrKey == null) return null
        if (tokenOrKey.startsWith("us_")) {
            return tokenOrKey.removePrefix("us_").toLongOrNull()
        }
        tokenOrKey.toLongOrNull()?.let { return it }
        val session = tokens[tokenOrKey]
        if (session != null) return session.scriptId
        return null
    }

    // region Values Storage
    @JavascriptInterface
    fun getValue(scriptKey: String, key: String): String? {
        val scriptId = resolveScriptId(scriptKey) ?: return null
        return manager.gmGetValue(scriptId, key)
    }

    @JavascriptInterface
    fun setValue(scriptKey: String, key: String, value: String) {
        val scriptId = resolveScriptId(scriptKey) ?: return
        manager.gmSetValue(scriptId, key, value)
    }

    @JavascriptInterface
    fun deleteValue(scriptKey: String, key: String) {
        val scriptId = resolveScriptId(scriptKey) ?: return
        manager.gmDeleteValue(scriptId, key)
    }

    @JavascriptInterface
    fun listValues(scriptKey: String): String {
        val scriptId = resolveScriptId(scriptKey) ?: return "[]"
        val keys = manager.gmListValues(scriptId)
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        return arr.toString()
    }

    @JavascriptInterface
    fun gmGetValue(token: String, key: String): String? = getValue(token, key)

    @JavascriptInterface
    fun gmSetValue(token: String, key: String, value: String) = setValue(token, key, value)

    @JavascriptInterface
    fun gmDeleteValue(token: String, key: String) = deleteValue(token, key)

    @JavascriptInterface
    fun gmListValues(token: String): String = listValues(token)
    // endregion

    // region GM_xmlhttpRequest
    @JavascriptInterface
    fun xhr(id: String, detailsJson: String) {
        val safeId = id.replace("'", "")
        try {
            val details = JSONObject(detailsJson)
            val url = details.getString("url")

            // @connect domain enforcement (Tampermonkey spec §connect)
            // Request ID format: x_<scriptId>_<seq>_<timestamp> — parse scriptId to look up metadata
            val parsedScriptId = safeId.split("_").getOrNull(1)?.toLongOrNull()
            if (parsedScriptId != null) {
                val connects = manager.getById(parsedScriptId)?.metadata?.connects ?: emptyList()
                if (connects.isNotEmpty()) {
                    val requestHost = try { URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                    val allowed = connects.any { domain ->
                        val d = domain.trim().lowercase()
                        d == "*" || d == "@nocheck" ||
                                requestHost == d ||
                                requestHost.endsWith(".$d")
                    }
                    if (!allowed) {
                        val err = JSONObject().put("error", "GM_xmlhttpRequest blocked: host '$requestHost' not listed in @connect")
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__usXhrCallback && window.__usXhrCallback('$safeId', null, $err);",
                                null
                            )
                        }
                        return
                    }
                }
            }

            val method = details.optString("method", "GET").ifBlank { "GET" }.uppercase()
            val hasBody = details.has("data") && !details.isNull("data")
            val mediaTypeHeader = details.optJSONObject("headers")?.optString("Content-Type")
                ?: details.optJSONObject("headers")?.optString("content-type")
            val timeoutMs = details.optLong("timeout", 0L)
            val responseType = details.optString("responseType", "").lowercase()

            val requestBody = if (hasBody) {
                details.optString("data", "").toRequestBody(
                    (mediaTypeHeader ?: "text/plain;charset=UTF-8").toMediaTypeOrNull()
                )
            } else if (method != "GET" && method != "HEAD") {
                ByteArray(0).toRequestBody(null)
            } else null

            val builder = Request.Builder().url(url).method(method, requestBody)

            val anonymous = details.optBoolean("anonymous", false)
            if (!anonymous) {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                if (!cookie.isNullOrBlank()) {
                    builder.header("Cookie", cookie)
                }
            }

            var hasUserAgent = false
            if (details.has("headers")) {
                val headers = details.optJSONObject("headers")
                headers?.keys()?.forEach { k ->
                    val v = headers.optString(k)
                    if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    builder.header(k, v)
                }
            }
            if (!hasUserAgent) {
                val ua = runCatching { webView.settings.userAgentString }.getOrNull()
                    ?: WebSettings.getDefaultUserAgent(webView.context)
                if (ua.isNotBlank()) builder.header("User-Agent", ua)
            }

            val client = if (timeoutMs > 0) {
                httpClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            } else {
                httpClient
            }

            val call = client.newCall(builder.build())
            activeCalls[safeId] = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(safeId)
                    val err = JSONObject().put("error", e.message ?: "network error")
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__usXhrCallback && window.__usXhrCallback('$safeId', null, $err);",
                            null
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    activeCalls.remove(safeId)
                    response.use { r ->
                        val isBinary = responseType == "blob" || responseType == "arraybuffer"
                        val headerText = StringBuilder()
                        for (i in 0 until r.headers.size) {
                            headerText.append(r.headers.name(i)).append(": ").append(r.headers.value(i)).append("\r\n")
                        }
                        val result = JSONObject()
                            .put("status", r.code)
                            .put("statusText", r.message)
                            .put("finalUrl", r.request.url.toString())
                            .put("responseHeaders", headerText.toString())

                        if (!anonymous) {
                            val setCookieHeaders = r.headers("Set-Cookie")
                            if (setCookieHeaders.isNotEmpty()) {
                                val cm = CookieManager.getInstance()
                                setCookieHeaders.forEach { sc ->
                                    runCatching { cm.setCookie(url, sc) }
                                }
                            }
                        }

                        if (isBinary) {
                            val bytes = r.body?.bytes() ?: ByteArray(0)
                            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            result.put("responseBase64", b64)
                            result.put("responseText", "")
                        } else {
                            val text = runCatching { r.body?.string() }.getOrDefault("")
                            result.put("responseText", text)
                        }

                        webView.post {
                            webView.evaluateJavascript(
                                "window.__usXhrCallback && window.__usXhrCallback('$safeId', $result, null);",
                                null
                            )
                        }
                    }
                }
            })
        } catch (e: Exception) {
            val err = JSONObject().put("error", e.message ?: "invalid request")
            webView.post {
                webView.evaluateJavascript(
                    "window.__usXhrCallback && window.__usXhrCallback('$safeId', null, $err);",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun abortXhr(id: String) {
        activeCalls.remove(id.replace("'", ""))?.cancel()
    }

    @JavascriptInterface
    fun gmXhr(token: String, reqId: String, detailsJson: String) {
        xhr(reqId, detailsJson)
    }

    @JavascriptInterface
    fun gmAbortXhr(token: String, reqId: String) {
        abortXhr(reqId)
    }
    // endregion

    // region Download & Media
    @JavascriptInterface
    fun download(url: String, filename: String) {
        webView.post {
            try {
                val finalFilename = filename.ifBlank { "download" }
                DownloadEngineManager.enqueueDownload(
                    context = webView.context,
                    url = url,
                    fileName = finalFilename,
                    mimeType = "application/octet-stream",
                    userAgent = webView.settings.userAgentString,
                    cookies = CookieManager.getInstance().getCookie(url) ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "gmDownload failed to enqueue", e)
            }
        }
    }

    @JavascriptInterface
    fun gmDownload(token: String, reqId: String, detailsJson: String) {
        try {
            val d = JSONObject(detailsJson)
            val u = d.getString("url")
            val name = d.optString("name", "download")
            download(u, name)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun gmAbortDownload(token: String, reqId: String) {}
    // endregion

    // region UI & System APIs
    @JavascriptInterface
    fun notify(scriptName: String, title: String, text: String, origin: String) {
        webView.post {
            try {
                UserScriptNotificationHelper.showNotification(
                    context = webView.context,
                    scriptName = scriptName,
                    title = title,
                    text = text,
                    origin = origin
                )
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun gmNotification(token: String, text: String) {
        try {
            val d = JSONObject(text)
            val t = d.optString("title", "")
            val txt = d.optString("text", text)
            notify(t, t, txt, "")
        } catch (_: Exception) {
            notify("", "", text, "")
        }
    }

    @JavascriptInterface
    fun setClipboard(text: String) {
        webView.post {
            try {
                val cm = webView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("userscript", text))
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun gmSetClipboard(token: String, text: String) {
        setClipboard(text)
    }

    @JavascriptInterface
    fun openInTab(url: String, active: Boolean) {
        webView.post {
            try {
                var ctx: Context? = webView.context
                while (ctx is android.content.ContextWrapper) {
                    if (ctx is com.onyx.browser.MainActivity) break
                    ctx = ctx.baseContext
                }
                val activity = ctx as? com.onyx.browser.MainActivity
                activity?.openUrlInNewTab(url = url, isIncognito = webView.isIncognito)
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun gmOpenInTab(token: String, url: String, active: Boolean) {
        openInTab(url, active)
    }

    @JavascriptInterface
    fun registerMenuCommand(caption: String, fnId: String) {
        webView.post {
            webView.registerUserScriptMenuCommand(caption, fnId)
        }
    }

    @JavascriptInterface
    fun unregisterMenuCommand(fnId: String) {
        webView.post {
            webView.unregisterUserScriptMenuCommand(fnId)
        }
    }

    @JavascriptInterface
    fun gmRegisterMenuCommand(token: String, caption: String, fnId: String) {
        registerMenuCommand(caption, fnId)
    }

    @JavascriptInterface
    fun gmUnregisterMenuCommand(token: String, fnId: String) {
        unregisterMenuCommand(fnId)
    }

    @JavascriptInterface
    fun gmLog(token: String, message: String) {
        Log.d(TAG, "UserScript: $message")
    }
    // endregion

    companion object {
        const val JS_INTERFACE_NAME = "onyxGM"
        private const val TAG = "UserScriptBridge"

        fun isWebUrl(url: String): Boolean {
            val scheme = runCatching { URI(url.trim()).scheme }.getOrNull() ?: return false
            return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        }
    }
}

typealias OnyxUserScriptBridge = UserScriptBridge
