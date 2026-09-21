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

                            val isAggressive = prefs.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE
                            val blockedByEngine = AdBlockEngine.shouldBlock(url, "", "other")
                            val blockedByDomain = AdBlockDomainManager.shouldBlock(reqDomain, isAggressive)

                            if (blockedByEngine || blockedByDomain) {
                                prefs.incrementBlockedRequests()
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
}
