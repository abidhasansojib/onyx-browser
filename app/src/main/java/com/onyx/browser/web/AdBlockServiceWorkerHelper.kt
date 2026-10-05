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

                            val isAggressive = prefs.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE
                            val uncloakedDomain = AdBlockDomainManager.uncloakDomain(reqDomain)
                            val blockedByDomain = AdBlockDomainManager.shouldBlock(uncloakedDomain, isAggressive)
                            val blockedByEngine = if (!blockedByDomain) {
                                val method = request.method ?: "GET"
                                AdBlockEngine.shouldBlock(url, "", AdBlockEngine.RESOURCE_TYPE_OTHER, method)
                            } else true

                            if (blockedByDomain || blockedByEngine) {
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
