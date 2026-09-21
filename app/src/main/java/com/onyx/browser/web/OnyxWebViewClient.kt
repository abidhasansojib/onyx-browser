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

    private val upgradedUrls = mutableSetOf<String>()
    private val preferences = BrowserPreferences.getInstance(context)
    private val database = AppDatabase.getInstance(context)

    @Volatile
    private var currentPageUrl: String = ""

    // Known first-party ad/tracker domains for AGGRESSIVE mode
    private val firstPartyAdDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "googletagservices.com", "googleadservices.com", "google-analytics.com",
        "analytics.google.com", "stats.g.doubleclick.net", "pagead2.googlesyndication.com",
        "adservice.google.com", "facebook.net", "connect.facebook.net",
        "tr.snapchat.com", "analytics.twitter.com", "t.co", "ads.twitter.com",
        "ads-twitter.com", "scorecardresearch.com", "quantserve.com",
        "adsrvr.org", "casalemedia.com", "openx.net", "pubmatic.com",
        "rubiconproject.com", "criteo.com", "criteo.net", "amazon-adsystem.com",
        "ads.linkedin.com", "bing.com/bat", "bat.bing.com",
        "sentry.io", "bugsnag.com", "newrelic.com", "hotjar.com", "clarity.ms",
        "mixpanel.com", "amplitude.com", "appsflyer.com", "branch.io", "segment.com",
        "mc.yandex.ru", "statcounter.com", "outbrain.com", "taboola.com", "adroll.com",
        "bluekai.com", "demdex.net", "optimizely.com", "crazyegg.com", "mouseflow.com",
        "fullstory.com"
    )

    // Social media tracker domains (analytics/pixel only, not content)
    private val socialMediaTrackerDomains = setOf(
        // Facebook/Meta pixels and analytics
        "static.xx.fbcdn.net", "an.facebook.com", "pixel.facebook.com",
        // Instagram trackers
        "i.instagram.com",
        // Twitter/X analytics
        "analytics.twitter.com", "ads-api.twitter.com",
        // LinkedIn analytics
        "snap.licdn.com", "analytics.linkedin.com",
        // Pinterest
        "ct.pinterest.com", "analytics.pinterest.com",
        // TikTok
        "analytics.tiktok.com",
        // Reddit
        "alb.reddit.com"
    )

    // Facebook domains that are content (logins, embeds) not pure tracking
    private val facebookContentDomains = setOf(
        "www.facebook.com", "m.facebook.com", "static.facebook.com",
        "connect.facebook.net", "staticxx.facebook.com",
        "graph.facebook.com"
    )

    // Twitter/X content domains (embeds)
    private val twitterContentDomains = setOf(
        "platform.twitter.com", "cdn.syndication.twimg.com",
        "syndication.twitter.com", "pbs.twimg.com", "abs.twimg.com"
    )

    // LinkedIn content domains (embeds)
    private val linkedinContentDomains = setOf(
        "www.linkedin.com", "platform.linkedin.com", "badges.linkedin.com"
    )

    // Tracking query parameters to strip
    private val trackingParams = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid",
        "mc_eid", "mc_cid", "oly_enc_id", "oly_anon_id",
        "_openstat", "ref_", "vero_id", "mkt_tok",
        "twclid", "msclkid", "ttclid", "li_fat_id",
        "igshid", "s_cid", "srsltid", "epik"
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
            val reqDomain = preferences.cleanDomain(url)
            val isWhitelisted = preferences.isDomainWhitelisted(pageDomain)
            val isIncognitoView = (view as? OnyxWebView)?.isIncognito ?: false
            val resourceType = detectResourceType(request)

            // ── Element blocking in private windows: respect the setting ─────────
            // If element blocking in private windows is disabled and this is incognito,
            // skip all blocking
            if (isIncognitoView && !preferences.isElementBlockingInPrivateEnabled) {
                return null
            }

            // ── Social Media Tracker Blocking ─────────────────────────────────────
            if (preferences.isSocialMediaBlockingEnabled && !isWhitelisted) {
                val isSocialTrackerDomain = socialMediaTrackerDomains.any { trackerDomain ->
                    reqDomain == trackerDomain || reqDomain.endsWith(".$trackerDomain")
                }

                if (isSocialTrackerDomain) {
                    // Check if we should allow Facebook content (logins and embeds)
                    val isFbContent = preferences.allowFacebookLogins &&
                        facebookContentDomains.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
                    // Check if we should allow Twitter embeds
                    val isTwitterContent = preferences.allowTwitterEmbeds &&
                        twitterContentDomains.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
                    // Check if we should allow LinkedIn embeds
                    val isLinkedInContent = preferences.allowLinkedInEmbeds &&
                        linkedinContentDomains.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }

                    if (!isFbContent && !isTwitterContent && !isLinkedInContent) {
                        preferences.incrementBlockedRequests()
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }

            // ── Global Script Blocking ─────────────────────────────────────────
            if (!isWhitelisted && (preferences.isGlobalScriptBlockingEnabled ||
                    preferences.isScriptBlockingEnabledForDomain(pageDomain))) {
                if (resourceType == "script") {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }

            // ── Ad & Tracker Blocking ─────────────────────────────────────────
            if (preferences.isAdBlockEnabled && !isWhitelisted) {
                // Standard mode: use EasyList engine
                val blockedByEngine = AdBlockEngine.shouldBlock(url, pageUrl, resourceType)

                // Aggressive mode: also block requests to known first-party ad domains
                val blockedByAggressive = if (!blockedByEngine &&
                    preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE) {
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
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null) return false
        val uri = request.url ?: return false
        var url = uri.toString()
        val scheme = uri.scheme?.lowercase() ?: ""

        // ── Tracking URL Cleanup (strip tracking query params) ─────────────────
        if (request.isForMainFrame && preferences.isAutoRedirectTrackingUrlsEnabled &&
            (scheme == "http" || scheme == "https")) {
            val cleaned = stripTrackingParams(url)
            if (cleaned != url) {
                view?.loadUrl(cleaned)
                return true
            }
        }

        // ── AMP Redirect ───────────────────────────────────────────────────────
        if (request.isForMainFrame && preferences.isAutoRedirectAmpEnabled &&
            (scheme == "http" || scheme == "https")) {
            val canonical = resolveAmpUrl(url)
            if (canonical != null && canonical != url) {
                view?.loadUrl(canonical)
                return true
            }
        }

        // ── HTTPS Upgrade ─────────────────────────────────────────────────────
        val httpsMode = preferences.httpsUpgradeMode
        if (scheme == "http" && request.isForMainFrame &&
            httpsMode != BrowserPreferences.HTTPS_MODE_DISABLED) {
            if (!upgradedUrls.contains(url)) {
                upgradedUrls.add(url)
                val httpsUrl = url.replaceFirst("http://", "https://")
                view?.loadUrl(httpsUrl)
                return true
            }
        }

        // Standard web schemes — let WebView handle them normally
        if (scheme == "http" || scheme == "https" || scheme == "about" ||
            scheme == "data" || scheme == "blob" || scheme == "javascript" ||
            scheme == "file" || scheme == "content") {
            return false
        }

        // Custom app URL schemes — only dispatch if "Open Links in App" is enabled
        if (!preferences.isOpenLinksInAppEnabled) {
            // Still handle intent:// with fallback URL
            if (scheme == "intent") {
                return try {
                    val intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank() &&
                        (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                        view?.loadUrl(fallbackUrl)
                    }
                    true
                } catch (_: Exception) { true }
            }
            return false  // Let non-http schemes fail silently on WebView
        }

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
                true
            }
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Strip known tracking query parameters from a URL.
     * Returns the cleaned URL, or the original if no params were stripped.
     */
    private fun stripTrackingParams(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val queryParams = uri.queryParameterNames
            val stripped = queryParams.intersect(trackingParams)
            if (stripped.isEmpty()) return url

            val builder = uri.buildUpon().clearQuery()
            for (param in queryParams) {
                if (!trackingParams.contains(param)) {
                    builder.appendQueryParameter(param, uri.getQueryParameter(param))
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            url
        }
    }

    /**
     * Detect Google AMP URLs and return the canonical URL.
     * Returns null if the URL is not AMP.
     * Supports formats:
     *   - amp.example.com -> example.com
     *   - example.com/amp/article -> example.com/article
     *   - google.com/amp/s/example.com/path -> https://example.com/path
     */
    private fun resolveAmpUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            val path = uri.path ?: ""

            // Google AMP cache: https://www.google.com/amp/s/example.com/path
            if ((host == "www.google.com" || host == "google.com") &&
                path.startsWith("/amp/s/")) {
                val canonical = "https://" + path.removePrefix("/amp/s/")
                return canonical
            }

            // AMP subdomain: amp.example.com -> example.com
            if (host.startsWith("amp.")) {
                val canonical = uri.buildUpon()
                    .authority(host.removePrefix("amp."))
                    .build().toString()
                return canonical
            }

            // AMP path segment: example.com/amp/article
            if (path.contains("/amp/") || path.endsWith("/amp")) {
                val newPath = path
                    .replace("/amp/", "/")
                    .replace("/amp", "")
                    .ifEmpty { "/" }
                val canonical = uri.buildUpon().path(newPath).build().toString()
                return canonical
            }

            // ?amp=1 query param
            if (uri.getQueryParameter("amp") == "1") {
                val builder = uri.buildUpon().clearQuery()
                for (param in uri.queryParameterNames) {
                    if (param != "amp") {
                        builder.appendQueryParameter(param, uri.getQueryParameter(param))
                    }
                }
                return builder.build().toString()
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (url.isNullOrBlank()) return
        val isHttp = url.startsWith("http://") || url.startsWith("https://")
        if (!isHttp) return
        val isWhitelisted = preferences.isDomainWhitelisted(url)

        if ((view as? OnyxWebView)?.isDesktopModeEnabledForCurrentPage() == true) {
            val js = """
                (function() {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (meta) {
                        meta.setAttribute('content', 'width=1024, initial-scale=1');
                    } else {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = 'width=1024, initial-scale=1';
                        document.head.appendChild(meta);
                    }
                })();
            """.trimIndent()
            view?.evaluateJavascript(js, null)
        }

        if (preferences.isAdBlockEnabled && preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE && !isWhitelisted) {
            val js = """
                (function() {
                    window.ga = window.ga || function(){};
                    window.ga.q = window.ga.q || [];
                    window.ga.l = +new Date;
                    window.fbq = window.fbq || function(){};
                    window.gtag = window.gtag || function(){};
                    window.google_ad_client = true;
                    window.google_ad_width = window.innerWidth;
                    window.google_ad_height = window.innerHeight;
                })();
            """.trimIndent()
            view?.evaluateJavascript(js, null)
        }

        if (preferences.isDoNotTrackEnabled) {
            val dntJs = """
                (function() {
                    Object.defineProperty(navigator, 'doNotTrack', { get: function() { return '1'; } });
                })();
            """.trimIndent()
            view?.evaluateJavascript(dntJs, null)
        }

        // Language Fingerprint Protection
        if (preferences.isFingerprintLangEnabled && !isWhitelisted) {
            val langJs = """
                (function() {
                    try {
                        Object.defineProperty(navigator, 'language', { get: function() { return 'en-US'; } });
                        Object.defineProperty(navigator, 'languages', { get: function() { return ['en-US', 'en']; } });
                    } catch(e) {}
                })();
            """.trimIndent()
            view?.evaluateJavascript(langJs, null)
        }

        // Block Smart App Banners ("Open in App" notices)
        if (preferences.isBlockAppBannerEnabled) {
            val bannerJs = """
                (function() {
                    try {
                        // Remove meta app-argument and smart-app-banner tags
                        var metas = document.querySelectorAll('meta[name="apple-itunes-app"], meta[name="google-play-app"], link[rel="alternate"][media]');
                        metas.forEach(function(m) { m.parentNode && m.parentNode.removeChild(m); });
                        // Hide common app banner elements
                        var style = document.createElement('style');
                        style.textContent = [
                            '#app-banner, .app-banner, .smartbanner, .smart-banner,',
                            '.smartbanner-show, #smart-app-banner, .open-in-app,',
                            '[id*="app-banner"], [class*="app-banner"], [class*="smart-banner"],',
                            '[class*="app-download-banner"], [id*="appstore"], .branch-banner-content {',
                            '  display: none !important; visibility: hidden !important;',
                            '}'
                        ].join('');
                        document.head.appendChild(style);
                    } catch(e) {}
                })();
            """.trimIndent()
            view?.evaluateJavascript(bannerJs, null)
        }

        // Cosmetic element hiding (CSS injection)
        if (preferences.isCosmeticFilteringEnabled && !isWhitelisted) {
            try {
                val cosmeticCss = AdBlockEngine.getCosmeticCss(url)
                if (cosmeticCss.isNotBlank()) {
                    injectCosmeticCss(view, cosmeticCss)
                }
            } catch (_: Throwable) {}
        }

        // Fingerprint Protection (JS API spoofing)
        if (preferences.isFingerprintProtectionEnabled && !isWhitelisted) {
            injectFingerprintProtection(view)
        }

        // Passkey / WebAuthn support
        if (preferences.isPasskeysEnabled) {
            view?.evaluateJavascript(PasskeyWebAuthnBridge.getWebAuthnPolyfillJs(), null)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
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
        val fetchMode = request.requestHeaders?.get("Sec-Fetch-Mode")?.lowercase() ?: ""
        val fetchDest = request.requestHeaders?.get("Sec-Fetch-Dest")?.lowercase() ?: ""
        val reqWith = request.requestHeaders?.get("X-Requested-With")?.lowercase() ?: ""
        val urlPath = request.url?.path?.lowercase() ?: ""

        if (fetchDest == "iframe" || fetchDest == "frame") return "sub_frame"
        if (fetchDest == "script") return "script"
        if (fetchDest == "image") return "image"
        if (fetchDest == "style") return "stylesheet"
        if (fetchDest == "font") return "font"
        if (fetchDest == "video" || fetchDest == "audio") return "media"
        if (fetchDest == "empty" || fetchMode == "cors" || reqWith == "xmlhttprequest") return "xmlhttprequest"

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
            acceptHeader.contains("video/") || acceptHeader.contains("audio/") ||
                    urlPath.endsWith(".mp4") || urlPath.endsWith(".mp3") || urlPath.endsWith(".webm") -> "media"
            acceptHeader.contains("application/json") || acceptHeader.contains("xmlhttprequest") -> "xmlhttprequest"
            acceptHeader.contains("text/html") -> "sub_frame"
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
            try { view?.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    private fun injectFingerprintProtection(view: WebView?) {
        val js = """
            (function() {
                if (window.__onyxFpInjected) return;
                window.__onyxFpInjected = true;
                try {
                    var _noise = (Math.random() * 0.0001) - 0.00005;

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

                    Object.defineProperty(navigator, 'hardwareConcurrency', {
                        get: function() { return 4; }
                    });

                    if ('deviceMemory' in navigator) {
                        Object.defineProperty(navigator, 'deviceMemory', {
                            get: function() { return 4; }
                        });
                    }

                    var _origGetParam = WebGLRenderingContext.prototype.getParameter;
                    WebGLRenderingContext.prototype.getParameter = function(param) {
                        if (param === 37445) return 'Intel Inc.';
                        if (param === 37446) return 'Intel Iris OpenGL';
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

                    if (window.RTCPeerConnection) {
                        window.RTCPeerConnection = function() { return {}; };
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        view?.post {
            try { view?.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val url = request.url.toString()
            val fallbackUrl = url.replaceFirst("https://", "http://")
            // In STRICT mode, do NOT fall back to HTTP — block the page
            if (preferences.httpsUpgradeMode == BrowserPreferences.HTTPS_MODE_STRICT) {
                return // Leave the error page visible
            }
            if (upgradedUrls.contains(fallbackUrl)) {
                view?.loadUrl(fallbackUrl)
            }
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: android.webkit.SslErrorHandler?,
        error: android.net.http.SslError?
    ) {
        val url = view?.url ?: ""
        val fallbackUrl = url.replaceFirst("https://", "http://")
        if (preferences.httpsUpgradeMode == BrowserPreferences.HTTPS_MODE_STRICT) {
            // Strict: never fall back to HTTP, cancel and show error
            handler?.cancel()
        } else if (upgradedUrls.contains(fallbackUrl)) {
            handler?.cancel()
            view?.loadUrl(fallbackUrl)
        } else {
            super.onReceivedSslError(view, handler, error)
        }
    }
}
