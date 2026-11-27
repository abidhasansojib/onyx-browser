package com.onyx.browser.userscript

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.download.DownloadEngineManager
import com.onyx.browser.web.BrowserWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokens = ConcurrentHashMap<String, ScriptSession>()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val inFlightConnectPrompts = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val valueChangeListener = object : UserScriptManager.ValueChangeListener {
        override fun onValueChanged(scriptId: Long, key: String, oldValue: String?, newValue: String?) {
            val oldValStr = if (oldValue != null) JSONObject.quote(oldValue) else "null"
            val newValStr = if (newValue != null) JSONObject.quote(newValue) else "null"
            val safeKey = JSONObject.quote(key)
            val scriptToken = JSONObject.quote(manager.getOrCreateScriptToken(scriptId))
            val js = "try { " +
                    "if (window.__usValueChanged) window.__usValueChanged($scriptToken, $safeKey, $newValStr); " +
                    "if (window.__onyxGM && window.__onyxGM.notifyValueChanged) window.__onyxGM.notifyValueChanged($scriptId, $safeKey, $oldValStr, $newValStr); " +
                    "} catch (_) {}"
            scope.launch(Dispatchers.Main) {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    @Volatile
    private var cachedUserAgent: String = ""

    init {
        manager.addValueChangeListener(valueChangeListener)
        runCatching {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                cachedUserAgent = webView.settings.userAgentString ?: ""
            } else {
                webView.post {
                    runCatching { cachedUserAgent = webView.settings.userAgentString ?: "" }
                }
            }
        }
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
        inFlightConnectPrompts.values.forEach { it.complete(false) }
        inFlightConnectPrompts.clear()
    }

    fun destroy() {
        clearTokens()
        manager.removeValueChangeListener(valueChangeListener)
        scope.cancel()
    }

    private fun resolveScriptId(tokenOrKey: String?): Long? {
        if (tokenOrKey.isNullOrBlank()) return null
        manager.resolveScriptIdFromToken(tokenOrKey)?.let { return it }
        val session = tokens[tokenOrKey]
        if (session != null) return session.scriptId
        return null
    }

    private fun resolveAuthorizedScript(tokenOrKey: String?): ParsedUserScript? {
        val scriptId = resolveScriptId(tokenOrKey) ?: return null
        val parsed = manager.getById(scriptId) ?: return null
        return if (parsed.script.enabled) parsed else null
    }

    // region Values Storage
    @JavascriptInterface
    fun getValue(scriptKey: String, key: String): String? {
        val parsed = resolveAuthorizedScript(scriptKey) ?: return null
        return manager.gmGetValue(parsed.script.id, key)
    }

    @JavascriptInterface
    fun setValue(scriptKey: String, key: String, value: String) {
        val parsed = resolveAuthorizedScript(scriptKey) ?: return
        manager.gmSetValue(parsed.script.id, key, value)
    }

    @JavascriptInterface
    fun deleteValue(scriptKey: String, key: String) {
        val parsed = resolveAuthorizedScript(scriptKey) ?: return
        manager.gmDeleteValue(parsed.script.id, key)
    }

    @JavascriptInterface
    fun listValues(scriptKey: String): String {
        val parsed = resolveAuthorizedScript(scriptKey) ?: return "[]"
        val keys = manager.gmListValues(parsed.script.id)
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
    private fun executeAuthorizedXhr(token: String?, id: String, detailsJson: String) {
        val safeId = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        val parsedScript = resolveAuthorizedScript(token)
        if (parsedScript == null) {
            postXhrError(safeId, "Unauthorized GM_xmlhttpRequest: valid userscript capability token required")
            return
        }
        val parsedScriptId = parsedScript.script.id
        try {
            val details = JSONObject(detailsJson)
            val url = details.getString("url")
            if (!isWebUrl(url)) {
                postXhrError(safeId, "GM_xmlhttpRequest only supports http and https URLs")
                return
            }

            // @connect domain enforcement and first-use permission prompt (Tampermonkey spec §connect)
            val connects = parsedScript.metadata.connects
            val requestHost = try { URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
            if (requestHost.isEmpty()) {
                postXhrError(safeId, "Invalid request host")
                return
            }
            val savedDecision = manager.getConnectDecision(parsedScriptId, requestHost)
            if (savedDecision == false) {
                postXhrError(safeId, "GM_xmlhttpRequest denied by user for host '$requestHost'")
                return
            }
            if (savedDecision != true) {
                val hasWildcard = connects.any {
                    val d = it.trim().lowercase()
                    d == "*" || d == "@nocheck"
                }
                if (!hasWildcard) {
                    val listedInConnect = connects.any { domain ->
                        val d = domain.trim().lowercase()
                        d == "self" || requestHost == d || requestHost.endsWith(".$d")
                    }
                    scope.launch {
                        val approved = promptConnectPermission(
                            scriptId = parsedScriptId,
                            scriptName = parsedScript.metadata.name,
                            host = requestHost,
                            listedInConnect = listedInConnect
                        )
                        if (approved) {
                            scope.launch(Dispatchers.IO) {
                                executeXhrCall(safeId, details, url)
                            }
                        } else {
                            postXhrError(
                                safeId,
                                if (listedInConnect) {
                                    "GM_xmlhttpRequest denied by user for @connect host '$requestHost'"
                                } else {
                                    "GM_xmlhttpRequest blocked: host '$requestHost' not listed in @connect"
                                }
                            )
                        }
                    }
                    return
                }
            }

            executeXhrCall(safeId, details, url)
        } catch (e: Exception) {
            postXhrError(safeId, e.message ?: "invalid request")
        }
    }

    @JavascriptInterface
    fun xhr(id: String, detailsJson: String) {
        postXhrError(id.replace(Regex("[^a-zA-Z0-9_\\-]"), ""), "Unauthorized GM_xmlhttpRequest: token required")
    }

    private fun postXhrError(safeId: String, message: String) {
        val err = JSONObject().put("error", message)
        webView.post {
            webView.evaluateJavascript(
                "window.__usXhrCallback && window.__usXhrCallback('$safeId', null, $err);",
                null
            )
        }
    }

    private fun findHostActivity(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private suspend fun promptConnectPermission(
        scriptId: Long,
        scriptName: String,
        host: String,
        listedInConnect: Boolean
    ): Boolean {
        val normalizedHost = host.trim().lowercase()
        withContext(Dispatchers.IO) {
            manager.getConnectDecision(scriptId, normalizedHost)
        }?.let { return it }

        val key = "$scriptId:$normalizedHost"
        val newDeferred = CompletableDeferred<Boolean>()
        val existing = inFlightConnectPrompts.putIfAbsent(key, newDeferred)
        if (existing != null) {
            return existing.await()
        }

        val result = withContext(Dispatchers.Main) {
            val activity = findHostActivity(webView.context)
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                return@withContext listedInConnect
            }

            val deferred = CompletableDeferred<Boolean>()
            val statusLine = if (listedInConnect) {
                "This domain is declared in the script's @connect metadata."
            } else {
                "Warning: This domain is NOT declared in the script's @connect metadata."
            }
            val message = "\"$scriptName\" is requesting cross-origin network access via GM_xmlhttpRequest to:\n\n" +
                    "• Domain: $normalizedHost\n\n$statusLine"

            try {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Userscript Network Access")
                    .setMessage(message)
                    .setCancelable(true)
                    .setPositiveButton("Always Allow") { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            manager.setConnectDecision(scriptId, normalizedHost, true)
                        }
                        deferred.complete(true)
                    }
                    .setNeutralButton("Allow Once") { _, _ ->
                        deferred.complete(true)
                    }
                    .setNegativeButton("Deny") { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            manager.setConnectDecision(scriptId, normalizedHost, false)
                        }
                        deferred.complete(false)
                    }
                    .setOnCancelListener {
                        deferred.complete(false)
                    }
                    .show()
            } catch (_: Exception) {
                deferred.complete(listedInConnect)
            }
            deferred.await()
        }

        newDeferred.complete(result)
        inFlightConnectPrompts.remove(key, newDeferred)
        return result
    }

    private fun executeXhrCall(safeId: String, details: JSONObject, url: String) {
        try {
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
            val scopedCookieManager = runCatching { webView.cookieManager }.getOrNull() ?: CookieManager.getInstance()
            if (!anonymous) {
                val cookie = runCatching { scopedCookieManager.getCookie(url) }.getOrNull()
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
                val ua = cachedUserAgent.ifBlank {
                    runCatching { WebSettings.getDefaultUserAgent(webView.context) }.getOrDefault("")
                }
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
                    postXhrError(safeId, e.message ?: "network error")
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
                                setCookieHeaders.forEach { sc ->
                                    runCatching { scopedCookieManager.setCookie(url, sc) }
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
            postXhrError(safeId, e.message ?: "invalid request")
        }
    }

    @JavascriptInterface
    fun abortXhr(id: String) {
        // Requires token via gmAbortXhr
    }

    @JavascriptInterface
    fun gmXhr(token: String, reqId: String, detailsJson: String) {
        executeAuthorizedXhr(token, reqId, detailsJson)
    }

    @JavascriptInterface
    fun gmAbortXhr(token: String, reqId: String) {
        if (resolveAuthorizedScript(token) == null) return
        val safeId = reqId.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        activeCalls.remove(safeId)?.cancel()
    }
    // endregion

    // region Download & Media
    private fun executeAuthorizedDownload(token: String, url: String, filename: String) {
        if (resolveAuthorizedScript(token) == null || !isWebUrl(url)) return
        webView.post {
            try {
                val finalFilename = filename.ifBlank { "download" }
                val ua = webView.settings.userAgentString
                cachedUserAgent = ua ?: ""
                DownloadEngineManager.enqueueDownload(
                    context = webView.context,
                    url = url,
                    fileName = finalFilename,
                    mimeType = "application/octet-stream",
                    userAgent = ua,
                    cookies = webView.cookieManager.getCookie(url) ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "gmDownload failed to enqueue", e)
            }
        }
    }

    @JavascriptInterface
    fun download(url: String, filename: String) {
        // Unauthenticated direct download blocked; must use gmDownload with capability token
    }

    @JavascriptInterface
    fun gmDownload(token: String, reqId: String, detailsJson: String) {
        if (resolveAuthorizedScript(token) == null) return
        try {
            val d = JSONObject(detailsJson)
            val u = d.getString("url")
            val name = d.optString("name", "download")
            executeAuthorizedDownload(token, u, name)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun gmAbortDownload(token: String, reqId: String) {}
    // endregion

    // region UI & System APIs
    private fun executeAuthorizedNotify(parsed: ParsedUserScript, title: String, text: String, origin: String) {
        webView.post {
            try {
                val actualOrigin = try { Uri.parse(webView.url ?: "").host ?: origin } catch (_: Exception) { origin }
                UserScriptNotificationHelper.showNotification(
                    context = webView.context,
                    scriptName = parsed.metadata.name,
                    title = title.ifBlank { parsed.metadata.name },
                    text = text,
                    origin = actualOrigin
                )
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun notify(scriptName: String, title: String, text: String, origin: String) {
        // Unauthenticated notify blocked; must use gmNotification with capability token
    }

    @JavascriptInterface
    fun gmNotification(token: String, text: String) {
        val parsed = resolveAuthorizedScript(token) ?: return
        try {
            val d = JSONObject(text)
            val t = d.optString("title", parsed.metadata.name)
            val txt = d.optString("text", text)
            val orig = d.optString("origin", "")
            executeAuthorizedNotify(parsed, t, txt, orig)
        } catch (_: Exception) {
            executeAuthorizedNotify(parsed, parsed.metadata.name, text, "")
        }
    }

    @JavascriptInterface
    fun setClipboard(text: String) {
        // Unauthenticated clipboard write blocked; must use gmSetClipboard with capability token
    }

    @JavascriptInterface
    fun gmSetClipboard(token: String, text: String) {
        if (resolveAuthorizedScript(token) == null) return
        webView.post {
            try {
                val cm = webView.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("userscript", text))
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun openInTab(url: String, active: Boolean) {
        // Unauthenticated tab open blocked; must use gmOpenInTab with capability token
    }

    @JavascriptInterface
    fun gmOpenInTab(token: String, url: String, active: Boolean) {
        if (resolveAuthorizedScript(token) == null || !isWebUrl(url)) return
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
    fun registerMenuCommand(caption: String, fnId: String) {
        // Unauthenticated menu registration blocked; must use gmRegisterMenuCommand with capability token
    }

    @JavascriptInterface
    fun unregisterMenuCommand(fnId: String) {
        // Unauthenticated menu unregistration blocked; must use gmUnregisterMenuCommand with capability token
    }

    @JavascriptInterface
    fun gmRegisterMenuCommand(token: String, caption: String, fnId: String) {
        if (resolveAuthorizedScript(token) == null) return
        webView.post {
            webView.registerUserScriptMenuCommand(caption, fnId)
        }
    }

    @JavascriptInterface
    fun gmUnregisterMenuCommand(token: String, fnId: String) {
        if (resolveAuthorizedScript(token) == null) return
        webView.post {
            webView.unregisterUserScriptMenuCommand(fnId)
        }
    }

    @JavascriptInterface
    fun gmLog(token: String, message: String) {
        if (resolveAuthorizedScript(token) == null) return
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
