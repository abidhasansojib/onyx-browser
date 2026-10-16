package com.onyx.browser.web

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import java.io.ByteArrayInputStream

/**
 * Intercepts Service Worker background fetch and network requests.
 * Applies both EasyList adblock rules and AdBlockDomainManager (Standard vs Aggressive).
 */
object AdBlockServiceWorkerHelper {
    private const val TAG = "AdBlockServiceWorker"

    fun initialize(context: Context) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
                val swController = ServiceWorkerControllerCompat.getInstance()
                swController.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return try {
                            val url = request.url?.toString() ?: return null
                            if (!url.startsWith("http://") && !url.startsWith("https://")) return null

                            val prefs = BrowserPreferences.getInstance(context)
                            if (!prefs.isAdBlockEnabled) return null

                            val reqDomain = prefs.cleanDomain(url)
                            if (prefs.isDomainWhitelisted(reqDomain)) return null

                            // Universal CAPTCHA, Auth, OAuth, and First-Party Meta exemption
                            val lowerUrl = url.lowercase()
                            val isAuthOrSecurity = reqDomain.contains("facebook.com") || reqDomain.contains("meta.com") ||
                                    reqDomain.contains("fb.com") || reqDomain.contains("fbcdn.net") ||
                                    reqDomain.contains("facebook.net") || reqDomain.contains("fbsbx.com") ||
                                    reqDomain.contains("instagram.com") || reqDomain.contains("messenger.com") ||
                                    reqDomain.contains("arkose") || reqDomain.contains("funcaptcha") ||
                                    reqDomain.contains("turnstile") || reqDomain.contains("recaptcha") ||
                                    reqDomain.contains("hcaptcha") || reqDomain.contains("challenges.cloudflare.com") ||
                                    reqDomain.contains("datadome") || reqDomain.contains("perimeterx") ||
                                    reqDomain.contains("kasada") || reqDomain.contains("geetest") ||
                                    lowerUrl.contains("/checkpoint/") || lowerUrl.contains("/login") ||
                                    lowerUrl.contains("/auth") || lowerUrl.contains("lsd=") || lowerUrl.contains("jazoest=")

                            val isMetaAdPixel = reqDomain == "pixel.facebook.com" || reqDomain == "an.facebook.com" || reqDomain == "tr.facebook.com"
                            if (isAuthOrSecurity && !isMetaAdPixel) {
                                return null
                            }

                            val headers = request.requestHeaders
                            val sourceUrl = getHeaderCaseInsensitive(headers, "Referer")
                                ?: getHeaderCaseInsensitive(headers, "Origin")
                                ?: ""

                            val fetchDest = getHeaderCaseInsensitive(headers, "Sec-Fetch-Dest")?.lowercase() ?: ""
                            val fetchMode = getHeaderCaseInsensitive(headers, "Sec-Fetch-Mode")?.lowercase() ?: ""
                            val acceptHeader = getHeaderCaseInsensitive(headers, "Accept")?.lowercase() ?: ""
                            val reqWith = getHeaderCaseInsensitive(headers, "X-Requested-With")?.lowercase() ?: ""
                            val urlPath = request.url?.path?.lowercase() ?: ""
                            val scheme = request.url?.scheme?.lowercase() ?: ""

                            val resourceType = when {
                                scheme == "ws" || scheme == "wss" || fetchMode == "websocket" -> AdBlockEngine.RESOURCE_TYPE_WEBSOCKET
                                fetchDest == "iframe" || fetchDest == "frame" -> AdBlockEngine.RESOURCE_TYPE_SUB_FRAME
                                fetchDest == "script" -> AdBlockEngine.RESOURCE_TYPE_SCRIPT
                                fetchDest == "image" -> AdBlockEngine.RESOURCE_TYPE_IMAGE
                                fetchDest == "style" -> AdBlockEngine.RESOURCE_TYPE_STYLESHEET
                                fetchDest == "font" -> AdBlockEngine.RESOURCE_TYPE_FONT
                                fetchDest == "object" || fetchDest == "embed" -> AdBlockEngine.RESOURCE_TYPE_OBJECT
                                fetchDest == "video" || fetchDest == "audio" -> AdBlockEngine.RESOURCE_TYPE_MEDIA
                                urlPath.endsWith(".m3u8") || urlPath.endsWith(".ts") || urlPath.endsWith(".mpd") ||
                                    urlPath.endsWith(".m4s") || urlPath.endsWith(".mp4") || urlPath.endsWith(".webm") ||
                                    urlPath.endsWith(".ogg") || urlPath.endsWith(".mp3") || urlPath.endsWith(".m4a") ||
                                    urlPath.endsWith(".aac") || urlPath.endsWith(".flv") -> AdBlockEngine.RESOURCE_TYPE_MEDIA
                                getHeaderCaseInsensitive(headers, "Ping-To") != null ||
                                    urlPath.contains("/ping") || urlPath.contains("/beacon") -> AdBlockEngine.RESOURCE_TYPE_PING
                                fetchDest == "empty" || fetchMode == "cors" || reqWith == "xmlhttprequest" -> AdBlockEngine.RESOURCE_TYPE_XHR
                                acceptHeader.contains("text/css") || urlPath.endsWith(".css") -> AdBlockEngine.RESOURCE_TYPE_STYLESHEET
                                acceptHeader.contains("javascript") || urlPath.endsWith(".js") -> AdBlockEngine.RESOURCE_TYPE_SCRIPT
                                acceptHeader.contains("image/") || urlPath.endsWith(".png") ||
                                    urlPath.endsWith(".jpg") || urlPath.endsWith(".jpeg") ||
                                    urlPath.endsWith(".webp") || urlPath.endsWith(".gif") ||
                                    urlPath.endsWith(".svg") || urlPath.endsWith(".ico") -> AdBlockEngine.RESOURCE_TYPE_IMAGE
                                acceptHeader.contains("font/") || urlPath.endsWith(".woff") ||
                                    urlPath.endsWith(".woff2") || urlPath.endsWith(".ttf") ||
                                    urlPath.endsWith(".otf") -> AdBlockEngine.RESOURCE_TYPE_FONT
                                acceptHeader.contains("video/") || acceptHeader.contains("audio/") -> AdBlockEngine.RESOURCE_TYPE_MEDIA
                                acceptHeader.contains("application/json") || acceptHeader.contains("xmlhttprequest") -> AdBlockEngine.RESOURCE_TYPE_XHR
                                acceptHeader.contains("text/html") -> AdBlockEngine.RESOURCE_TYPE_SUB_FRAME
                                else -> AdBlockEngine.RESOURCE_TYPE_OTHER
                            }

                            val uncloakedUrl = AdBlockDomainManager.uncloakUrl(url)
                            val method = request.method ?: "GET"
                            var engineResult = AdBlockEngine.checkRequest(uncloakedUrl, sourceUrl, resourceType, method)

                            // Dual-pass: if uncloaked check did not block and original URL differs, check original
                            if (!engineResult.shouldBlock && uncloakedUrl != url) {
                                val originalResult = AdBlockEngine.checkRequest(url, sourceUrl, resourceType, method)
                                if (originalResult.shouldBlock || !originalResult.redirectData.isNullOrBlank()) {
                                    engineResult = originalResult
                                }
                            }

                            if (engineResult.shouldBlock) {
                                prefs.incrementBlockedRequests()

                                if (!engineResult.redirectData.isNullOrBlank()) {
                                    val redirect = engineResult.redirectData
                                    if (redirect.startsWith("data:")) {
                                        try {
                                            val commaIndex = redirect.indexOf(',')
                                            if (commaIndex != -1) {
                                                val meta = redirect.substring(5, commaIndex)
                                                val data = redirect.substring(commaIndex + 1)
                                                val isBase64 = meta.contains(";base64")
                                                val mime = meta.substringBefore(';').ifBlank { "text/plain" }
                                                val bytes = if (isBase64) android.util.Base64.decode(data, android.util.Base64.DEFAULT) else data.toByteArray()
                                                return WebResourceResponse(
                                                    mime,
                                                    "UTF-8",
                                                    200,
                                                    "OK",
                                                    mapOf(
                                                        "Access-Control-Allow-Origin" to "*",
                                                        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                                                        "Access-Control-Allow-Headers" to "*"
                                                    ),
                                                    ByteArrayInputStream(bytes)
                                                )
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }

                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Blocked by Onyx Shields",
                                    mapOf(
                                        "Access-Control-Allow-Origin" to "*",
                                        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                                        "Access-Control-Allow-Headers" to "*"
                                    ),
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            null
                        } catch (t: Throwable) {
                            null
                        }
                    }
                })
                Log.i(TAG, "Successfully initialized ServiceWorkerClient for ad & tracker interception")
            } else {
                Log.w(TAG, "SERVICE_WORKER_BASIC_USAGE is not supported on this WebView version")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize ServiceWorkerClient", t)
        }
    }

    private fun getHeaderCaseInsensitive(headers: Map<String, String>?, name: String): String? {
        if (headers == null || headers.isEmpty()) return null
        val direct = headers[name]
        if (direct != null) return direct
        for ((key, value) in headers) {
            if (key.equals(name, ignoreCase = true)) {
                return value
            }
        }
        return null
    }
}
