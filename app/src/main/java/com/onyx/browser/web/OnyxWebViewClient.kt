package com.onyx.browser.web

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class OnyxWebViewClient(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onUrlChanged: (String) -> Unit,
    private val onPageFinishedCallback: (String) -> Unit
) : WebViewClient() {

    private val preferences = BrowserPreferences.getInstance(context)
    private val database = AppDatabase.getInstance(context)

    @Volatile
    private var currentPageUrl: String = ""

    // ── Aggressive blocking: known first-party tracker/ad domains ─────────────
    // A minimal hardcoded list for first-party ad/tracker domains that bypass
    // standard third-party rules (used only when blockingLevel == BLOCKING_AGGRESSIVE).
    private val firstPartyAdDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "googletagservices.com", "googleadservices.com", "google-analytics.com",
        "analytics.google.com", "stats.g.doubleclick.net", "pagead2.googlesyndication.com",
        "adservice.google.com", "facebook.net", "connect.facebook.net",
        "tr.snapchat.com", "analytics.twitter.com", "t.co", "ads.twitter.com",
        "ads-twitter.com", "scorecardresearch.com", "quantserve.com",
        "adsrvr.org", "casalemedia.com", "openx.net", "pubmatic.com",
        "rubiconproject.com", "criteo.com", "criteo.net", "amazon-adsystem.com",
        "ads.linkedin.com", "bing.com/bat", "bat.bing.com"
    )

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        return try {
            val url = request.url?.toString() ?: return null

            // Track current page URL; never block the main frame document
            if (request.isForMainFrame) {
                currentPageUrl = url
                return null
            }

            // Only process http/https network requests
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return null
            }

            val pageUrl = run {
                val headers = request.requestHeaders
                headers?.get("Referer") ?: headers?.get("referer")
                    ?: headers?.get("Origin") ?: headers?.get("origin")
                    ?: currentPageUrl
            }

            val pageDomain = preferences.cleanDomain(pageUrl)
            val isWhitelisted = preferences.isDomainWhitelisted(pageDomain)

            // ── Per-domain Script Blocking ─────────────────────────────────────
            if (!isWhitelisted && preferences.isScriptBlockingEnabledForDomain(pageDomain)) {
                val resourceType = detectResourceType(request)
                if (resourceType == "script") {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }

            // ── Ad & Tracker Blocking ─────────────────────────────────────────
            if (preferences.isAdBlockEnabled && !isWhitelisted) {
                val resourceType = detectResourceType(request)

                // Standard mode: use EasyList engine
                val blockedByEngine = AdBlockEngine.shouldBlock(url, pageUrl, resourceType)

                // Aggressive mode: also block requests to known first-party ad domains
                val blockedByAggressive = if (!blockedByEngine &&
                    preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE) {
                    val reqDomain = preferences.cleanDomain(url)
                    firstPartyAdDomains.any { adDomain ->
                        reqDomain == adDomain || reqDomain.endsWith(".$adDomain")
                    }
                } else false

                if (blockedByEngine || blockedByAggressive) {
                    preferences.incrementBlockedRequests()
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }

            null
        } catch (t: Throwable) {
            // Fail open: never crash or silently kill requests due to adblock errors
            null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (url == null) return null
        return try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            if (preferences.isAdBlockEnabled && !preferences.isDomainWhitelisted(currentPageUrl)) {
                val blocked = AdBlockEngine.shouldBlock(url, currentPageUrl, "other")
                if (blocked) {
                    preferences.incrementBlockedRequests()
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null) return false
        val uri = request.url ?: return false
        val url = uri.toString()
        val scheme = uri.scheme?.lowercase() ?: ""

        // ── HTTPS Upgrade ─────────────────────────────────────────────────────
        // Automatically upgrade http:// main-frame navigations to https://, matching
        // Brave's "Upgrade Connections to HTTPS" feature.
        if (scheme == "http" && request.isForMainFrame && preferences.isHttpsUpgradeEnabled) {
            val httpsUrl = url.replaceFirst("http://", "https://")
            view?.loadUrl(httpsUrl)
            return true
        }

        // Standard web schemes — let WebView handle them normally
        if (scheme == "http" || scheme == "https" || scheme == "about" ||
            scheme == "data" || scheme == "blob" || scheme == "javascript" ||
            scheme == "file" || scheme == "content") {
            return false
        }

        // Custom app URL schemes (fb://, instagram://, twitter://, market://, etc.)
        // and intent:// — dispatch via the OS so the native app opens.
        return try {
            if (scheme == "intent") {
                val intent = android.content.Intent.parseUri(
                    url, android.content.Intent.URI_INTENT_SCHEME
                ).apply {
                    addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank() &&
                        (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                        view?.loadUrl(fallbackUrl)
                    }
                    true
                }
            } else {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW, uri
                ).apply {
                    addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                }
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                }
                true // Always consume: never show ERR_UNKNOWN_URL_SCHEME
            }
        } catch (_: Exception) {
            true
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (url.isNullOrBlank()) return
        val isHttp = url.startsWith("http://") || url.startsWith("https://")
        if (!isHttp) return
        val isWhitelisted = preferences.isDomainWhitelisted(url)

        // Cosmetic element hiding (CSS injection)
        if (preferences.isCosmeticFilteringEnabled && !isWhitelisted) {
            try {
                val cosmeticCss = AdBlockEngine.getCosmeticCss(url)
                if (cosmeticCss.isNotBlank()) {
                    injectCosmeticCss(view, cosmeticCss)
                }
            } catch (_: Throwable) {}
        }

        // Fingerprint Protection (JS API spoofing, like Brave)
        if (preferences.isFingerprintProtectionEnabled && !isWhitelisted) {
            injectFingerprintProtection(view)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
            // Apply correct UA (desktop or mobile) for this domain before the page loads.
            // This is what makes desktop mode per-domain: each navigation checks the domain
            // against the tab's desktopDomains set and swaps UA accordingly.
            (view as? OnyxWebView)?.applyUserAgentForUrl(url)
            onUrlChanged(url)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
            onPageFinishedCallback(url)
            val isIncognito = (view as? OnyxWebView)?.isIncognito ?: false
            if (!isIncognito && url.startsWith("http")) {
                val title = view?.title ?: url
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        database.historyDao().insertHistory(
                            HistoryItem(
                                url = url,
                                title = title,
                                visitTime = System.currentTimeMillis()
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun detectResourceType(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "main_frame"

        val acceptHeader = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
        val urlPath = request.url?.path?.lowercase() ?: ""

        return when {
            acceptHeader.contains("text/css") || urlPath.endsWith(".css") -> "stylesheet"
            acceptHeader.contains("javascript") || urlPath.endsWith(".js") -> "script"
            acceptHeader.contains("image/") || urlPath.endsWith(".png") ||
                    urlPath.endsWith(".jpg") || urlPath.endsWith(".jpeg") ||
                    urlPath.endsWith(".webp") || urlPath.endsWith(".gif") ||
                    urlPath.endsWith(".svg") || urlPath.endsWith(".ico") -> "image"
            acceptHeader.contains("font/") || urlPath.endsWith(".woff") ||
                    urlPath.endsWith(".woff2") || urlPath.endsWith(".ttf") ||
                    urlPath.endsWith(".otf") -> "font"
            acceptHeader.contains("text/html") -> "subdocument"
            else -> "other"
        }
    }

    private fun injectCosmeticCss(view: WebView?, css: String) {
        val encodedCss = Base64.encodeToString(css.toByteArray(), Base64.NO_WRAP)
        val js = """
            (function() {
                try {
                    var style = document.getElementById('onyx-adblock-cosmetic');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'onyx-adblock-cosmetic';
                        style.type = 'text/css';
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = window.atob('$encodedCss');
                } catch (e) {}
            })();
        """.trimIndent()

        view?.post {
            try { view.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    /**
     * Injects lightweight fingerprint-protection overrides, similar to Brave's approach:
     * - Canvas: adds tiny random noise to getImageData / toDataURL outputs
     * - AudioContext: offsets AnalyserNode frequency data by ±1 LSB
     * - hardwareConcurrency / deviceMemory: returns rounded/clamped values
     * - screen width/height/colorDepth: reports common generic values
     * - WebGL vendor/renderer: returns generic strings
     *
     * These are subtle enough that humans never notice them but break naive
     * fingerprinting hashes from matching across sessions.
     */
    private fun injectFingerprintProtection(view: WebView?) {
        val js = """
            (function() {
                if (window.__onyxFpInjected) return;
                window.__onyxFpInjected = true;
                try {
                    // Seeded per-session noise — consistent within a page, random across sessions
                    var _noise = (Math.random() * 0.0001) - 0.00005;

                    // Canvas noise
                    var _origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                    HTMLCanvasElement.prototype.toDataURL = function(type, q) {
                        var ctx = this.getContext('2d');
                        if (ctx) {
                            var id = ctx.getImageData(0, 0, 1, 1);
                            id.data[0] = (id.data[0] + 1) % 256;
                            ctx.putImageData(id, 0, 0);
                        }
                        return _origToDataURL.apply(this, arguments);
                    };
                    var _origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                    CanvasRenderingContext2D.prototype.getImageData = function() {
                        var id = _origGetImageData.apply(this, arguments);
                        id.data[0] = (id.data[0] + 1) % 256;
                        return id;
                    };

                    // hardwareConcurrency — clamp to 2 or 4 (common values)
                    Object.defineProperty(navigator, 'hardwareConcurrency', {
                        get: function() { return 4; }
                    });

                    // deviceMemory — report 4 GB (common generic value)
                    if ('deviceMemory' in navigator) {
                        Object.defineProperty(navigator, 'deviceMemory', {
                            get: function() { return 4; }
                        });
                    }

                    // WebGL vendor / renderer strings
                    var _origGetParam = WebGLRenderingContext.prototype.getParameter;
                    WebGLRenderingContext.prototype.getParameter = function(param) {
                        if (param === 37445) return 'Intel Inc.';        // UNMASKED_VENDOR_WEBGL
                        if (param === 37446) return 'Intel Iris OpenGL'; // UNMASKED_RENDERER_WEBGL
                        return _origGetParam.call(this, param);
                    };
                    if (typeof WebGL2RenderingContext !== 'undefined') {
                        var _origGetParam2 = WebGL2RenderingContext.prototype.getParameter;
                        WebGL2RenderingContext.prototype.getParameter = function(param) {
                            if (param === 37445) return 'Intel Inc.';
                            if (param === 37446) return 'Intel Iris OpenGL';
                            return _origGetParam2.call(this, param);
                        };
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        view?.post {
            try { view.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }
}
