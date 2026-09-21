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

object AdBlockServiceWorkerHelper {
    private const val TAG = "AdBlockServiceWorker"

    private val commonAdTrackerDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "googleadservices.com", "google-analytics.com", "stats.g.doubleclick.net",
        "adservice.google.com", "facebook.net", "connect.facebook.net",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
        "rubiconproject.com", "casalemedia.com", "openx.net", "pubmatic.com",
        "adnxs.com", "amazon-adsystem.com", "adroll.com", "bat.bing.com",
        "hotjar.com", "clarity.ms", "mixpanel.com", "amplitude.com",
        "segment.com", "segment.io", "mc.yandex.ru", "statcounter.com",
        "applovin.com", "vungle.com", "liftoff.io", "inmobi.com",
        "chartboost.com", "unityads.unity3d.com", "mgid.com", "propellerads.com",
        "media.net", "fingerprintjs.com", "fpjs.io", "datadoghq.com"
    )

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

                            val blockedByEngine = AdBlockEngine.shouldBlock(url, "", "other")
                            val blockedByDomain = commonAdTrackerDomains.any { adDomain ->
                                reqDomain == adDomain || reqDomain.endsWith(".$adDomain")
                            }

                            if (blockedByEngine || blockedByDomain) {
                                prefs.incrementBlockedRequests()
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
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
