package com.onyx.browser.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.onyx.browser.MainActivity
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.media.MediaPlaybackBridge
import com.onyx.browser.nativebridge.AdBlockEngine
import com.onyx.browser.web.error.SyntheticNavigationState
import com.onyx.browser.web.error.WebErrorHandler
import com.onyx.browser.web.interceptor.AdBlockRequestInterceptor
import com.onyx.browser.web.interceptor.AuthBypassInterceptor
import com.onyx.browser.web.interceptor.HttpsUpgradeInterceptor
import com.onyx.browser.web.interceptor.InterceptRequestContext
import com.onyx.browser.web.interceptor.PrivacyUrlSanitizer
import com.onyx.browser.web.interceptor.RealInterceptorChain
import com.onyx.browser.web.interceptor.RequestInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class OnyxWebViewClient(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onUrlChanged: (String) -> Unit,
    private val onPageFinishedCallback: (String) -> Unit,
    private val onPageCommitVisibleCallback: ((WebView, String) -> Unit)? = null,
    /** Callback for tab-level actions. Replaces the old findMainActivity() context walk. */
    private val tabActionCallback: TabActionCallback? = null
) : WebViewClient() {

    private val upgradedUrls = mutableSetOf<String>()
    private val preferences = BrowserPreferences.getInstance(context)
    private val database = AppDatabase.getInstance(context)

    var onOpenInAppPrompt: ((intent: Intent, appName: String?, fallback: (() -> Unit)?) -> Unit)? = null


    private fun isExternalAppInstalledForIntent(intent: Intent): Boolean {
        return try {
            val pm = context.packageManager
            val resolveInfoList = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfoList.any { it.activityInfo?.packageName != context.packageName }
        } catch (_: Exception) {
            false
        }
    }

    private fun getAppNameForIntent(intent: Intent, fallbackName: String? = null): String? {
        try {
            val pm = context.packageManager
            val resolveInfo = pm.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                val label = resolveInfo.loadLabel(pm).toString()
                if (label.isNotBlank()) return label
            }
        } catch (_: Exception) {}
        return fallbackName
    }

    private fun promptOrLaunchApp(
        intent: Intent,
        appName: String? = null,
        fallback: (() -> Unit)? = null
    ): Boolean {
        if (!isExternalAppInstalledForIntent(intent)) {
            fallback?.invoke()
            return false
        }
        val resolvedName = getAppNameForIntent(intent, appName)
        val prompt = onOpenInAppPrompt
        if (prompt != null) {
            prompt(intent, resolvedName, fallback)
            return true
        } else {
            return try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                fallback?.invoke()
                false
            } catch (_: Exception) {
                fallback?.invoke()
                false
            }
        }
    }

    @Volatile
    private var currentPageUrl: String = ""
    @Volatile
    private var pendingMainFrameUrl: String? = null
    @Volatile
    private var isMainFrameDocumentLoaded: Boolean = false

    // ── Redirect Loop Breaker ─────────────────────────────────────────────────────
    private val recentNavigations = ArrayDeque<Pair<String, Long>>()
    private val BURST_WINDOW_MS = 4000L

    private fun checkAndBreakRedirectLoop(view: WebView?, url: String): Boolean {
        val now = System.currentTimeMillis()
        while (recentNavigations.isNotEmpty() && (now - recentNavigations.first().second) > BURST_WINDOW_MS) {
            recentNavigations.removeFirst()
        }

        val domain = preferences.cleanDomain(url)
        val baseDomain = preferences.getBaseDomain(domain)
        val normalized = url.substringBefore('?')

        recentNavigations.addLast(normalized to now)

        val exactCount = recentNavigations.count { it.first == normalized }
        if (exactCount >= 4) {
            android.util.Log.w("OnyxWebViewClient", "Redirect loop detected for $url (count: $exactCount) - breaking loop")
            view?.stopLoading()
            recentNavigations.clear()
            return true
        }

        // Detect cyclic alternating ping-pong loop (A -> B -> A -> B -> A -> B)
        if (recentNavigations.size >= 6) {
            val distinctUrls = recentNavigations.map { it.first }.distinct()
            if (distinctUrls.size == 2) {
                val (u1, u2) = distinctUrls
                val c1 = recentNavigations.count { it.first == u1 }
                val c2 = recentNavigations.count { it.first == u2 }
                if (c1 >= 3 && c2 >= 3) {
                    android.util.Log.w("OnyxWebViewClient", "Alternating redirect loop detected between $u1 and $u2 - breaking loop")
                    view?.stopLoading()
                    recentNavigations.clear()
                    return true
                }
            }
        }

        return false
    }

    // Bounded LRU cache of URLs where user explicitly chose "Stay in Onyx", avoiding unbounded memory growth
    private val bypassAppInterceptUrls = object {
        private val set = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

        fun add(url: String): Boolean {
            synchronized(set) {
                if (set.size >= 100) {
                    val iterator = set.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
                return set.add(url)
            }
        }

        fun remove(url: String): Boolean {
            return set.remove(url)
        }
    }

    internal fun isMetaDomain(domain: String): Boolean =
        PrivacyUrlSanitizer.isMetaDomain(domain)

    internal fun isCaptchaOrAuthUrl(url: String, domain: String): Boolean =
        PrivacyUrlSanitizer.isCaptchaOrAuthUrl(url, domain)

    /**
     * Returns true if the given domain is a known OAuth/SSO/identity/payment provider that
     * requires third-party cookies for login flows to work correctly.
     */
    internal fun isOAuthOrLoginProvider(domain: String): Boolean =
        PrivacyUrlSanitizer.isOAuthOrLoginProvider(domain)

    internal fun isOAuthOrLoginUrl(url: String): Boolean =
        PrivacyUrlSanitizer.isOAuthOrLoginUrl(url)

    fun isStreamingOrMediaPage(url: String): Boolean = Companion.isStreamingOrMediaPage(url)

    fun isMediaOrStreamingHost(host: String): Boolean = Companion.isMediaOrStreamingHost(host)

    fun isMediaUrl(url: String): Boolean = PrivacyUrlSanitizer.isMediaUrl(url)

    private fun isPopupAd(url: String, onyxWv: OnyxWebView?): Boolean {
        if (!preferences.isAdBlockEnabled) return false
        if (url.isBlank() || url == "about:blank") return false

        val reqDomain = preferences.cleanDomain(url)
        if (reqDomain.isBlank()) return false

        // Determine parent tab, parent URL, and parent domain
        val parentTab = onyxWv?.tabId?.let { tabActionCallback?.getTabById(it)?.parentId }?.let { tabActionCallback?.getTabById(it) }
        val parentUrl = parentTab?.url ?: currentPageUrl
        val parentDomain = preferences.cleanDomain(parentUrl)

        // 1. Check if it is an approved authentication, SSO, payment, or security flow
        val isAuthOrSecurityFlow = isOAuthOrLoginProvider(reqDomain) ||
                isOAuthOrLoginProvider(parentDomain) ||
                isMetaDomain(reqDomain) ||
                isCaptchaOrAuthUrl(url, reqDomain) ||
                isOAuthOrLoginUrl(url) ||
                (onyxWv?.isWithinOAuthGracePeriod() == true)
        if (isAuthOrSecurityFlow) return false

        // 2. Check if it is the same domain or subdomain as the parent tab
        val isSameSite = parentDomain.isNotBlank() && (
                reqDomain == parentDomain ||
                reqDomain.endsWith(".$parentDomain") ||
                parentDomain.endsWith(".$reqDomain")
        )
        if (isSameSite) return false

        // 3. Popunder / betting / redirect heuristics
        if (isBettingOrAdUrl(url, reqDomain)) {
            return true
        }

        // 4. Query Brave adblock native engine for both $popup and $main_frame rules
        val effectiveSourceUrl = if (parentUrl.isNotBlank()) parentUrl else currentPageUrl
        if (AdBlockEngine.checkUrlCached(url, effectiveSourceUrl, "popup")) {
            return true
        }
        if (AdBlockEngine.shouldBlock(url, effectiveSourceUrl, "main_frame")) {
            return true
        }

        // 6. If parent tab is a streaming/video/anime media player, ALL non-auth cross-domain popups are clickjacks/ads!
        val isParentStreamingOrMedia = isStreamingOrMediaPage(parentUrl) ||
                isMediaOrStreamingHost(parentDomain) ||
                MediaPlaybackBridge.isVideoAvailableForTab(parentTab?.id)
        if (isParentStreamingOrMedia) {
            return true
        }

        // In Aggressive blocking mode, block any unverified cross-domain popup
        val effectiveBlocking = preferences.getEffectiveBlockingLevel(onyxWv?.url ?: "")
        if (effectiveBlocking == BrowserPreferences.BLOCKING_AGGRESSIVE) {
            return true
        }

        return false
    }

    /**
     * Ordered Chain of Responsibility pipeline for subresource network interception:
     * 1. [HttpsUpgradeInterceptor]: Blocks cleartext HTTP subresources in Strict HTTPS mode (preserving media & LAN/localhost).
     * 2. [AuthBypassInterceptor]: Early pass-through for CAPTCHA challenges, OAuth/SSO providers, active OAuth grace periods, and media streams.
     * 3. [AdBlockRequestInterceptor]: Social tracker blocking, script blocking, CNAME uncloaking, and Rust AdBlockEngine evaluation.
     */
    val requestInterceptors: List<RequestInterceptor> = listOf(
        HttpsUpgradeInterceptor(
            preferences = preferences,
            onBlockedSubresource = { ctx ->
                recordBlockedRequest(ctx.view)
                val requestOrigin = getHeaderCaseInsensitive(ctx.request.requestHeaders, "Origin")
                createBlockedResponse(ctx.resourceType, ctx.requestUrl, requestOrigin)
            }
        ),
        AuthBypassInterceptor(
            preferences = preferences,
            currentPageUrlProvider = { currentPageUrl },
            onBlockedRequest = { v -> recordBlockedRequest(v) }
        ),
        AdBlockRequestInterceptor(
            preferences = preferences,
            currentPageUrlProvider = { currentPageUrl },
            detectResourceTypeInt = { req -> detectResourceTypeInt(req) },
            getRequestOrigin = { req -> getHeaderCaseInsensitive(req.requestHeaders, "Origin") },
            createBlockedResponse = { resType, reqUrl, reqOrigin -> createBlockedResponse(resType, reqUrl, reqOrigin) },
            createSurrogateResponse = { redirect, resType, reqOrigin -> createSurrogateResponse(redirect, resType, reqOrigin) },
            onBlockedRequest = { v -> recordBlockedRequest(v) }
        )
    )

    companion object {
        private val TRANSPARENT_1X1_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            Base64.DEFAULT
        )
        private val EMPTY_VAST_BYTES: ByteArray =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"/>".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        private val BLOCKED_SUBFRAME_BYTES: ByteArray =
            "<!-- blocked subframe -->".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        private val BLOCKED_MAIN_FRAME_HTML_BYTES: ByteArray =
            "<!DOCTYPE html><html><head><title>Blocked by Onyx Shields</title></head><body></body></html>".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        private val BLOCKED_STORAGE_ACCESS_BYTES: ByteArray =
            "Access to private app storage is blocked.".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        private val GSI_STUB_BYTES: ByteArray = """
            window.google = window.google || {};
            window.google.accounts = window.google.accounts || {};
            window.google.accounts.id = {
                initialize: function() {},
                prompt: function() {},
                renderButton: function() {},
                disableAutoSelect: function() {},
                storeCredential: function() {},
                cancel: function() {},
                revoke: function() {}
            };
        """.trimIndent().toByteArray(java.nio.charset.StandardCharsets.UTF_8)

        // Pre-allocated static header maps for zero-allocation fast-paths
        private val WILDCARD_CORS_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0"
        )
        private val EMPTY_403_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Length" to "0"
        )
        private val EMPTY_403_NO_HEADERS: Map<String, String> = mapOf(
            "Content-Length" to "0"
        )
        private val EMPTY_JS_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "application/javascript; charset=utf-8",
            "Content-Length" to "0"
        )
        private val EMPTY_CSS_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "text/css; charset=utf-8",
            "Content-Length" to "0"
        )
        private val EMPTY_FONT_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "font/woff2",
            "Content-Length" to "0"
        )
        private val EMPTY_MEDIA_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "video/mp4",
            "Content-Length" to "0"
        )
        private val EMPTY_TEXT_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to "0"
        )
        private val EMPTY_PNG_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "image/png",
            "Content-Length" to TRANSPARENT_1X1_PNG.size.toString()
        )
        private val EMPTY_VAST_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "text/xml; charset=utf-8",
            "Content-Length" to EMPTY_VAST_BYTES.size.toString()
        )
        private val BLOCKED_SUBFRAME_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to BLOCKED_SUBFRAME_BYTES.size.toString()
        )
        private val BLOCKED_MAIN_FRAME_WILDCARD_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to BLOCKED_MAIN_FRAME_HTML_BYTES.size.toString()
        )
        private val BLOCKED_STORAGE_ACCESS_HEADERS: Map<String, String> = mapOf(
            "Content-Length" to BLOCKED_STORAGE_ACCESS_BYTES.size.toString()
        )
        private val GSI_STUB_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Content-Type" to "application/javascript; charset=utf-8",
            "Content-Length" to GSI_STUB_BYTES.size.toString()
        )

        fun isStreamingOrMediaPage(url: String): Boolean {
            if (url.isBlank()) return false
            val lower = url.lowercase()
            val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
            val streamingHostKeywords = listOf(
                "anime", "stream", "movie", "video", "watch", "film", "cinema", "player", "embed",
                "tube", "crunchyroll", "funimation", "hidive", "dailymotion", "vimeo", "twitch",
                "bilibili", "animesalt", "aniwatch", "zoro", "9anime", "gogoanime", "kissonline",
                "kickassanime", "hianime", "kaido", "allanime", "flv", "hls", "sflix", "flixtor",
                "soap2day", "hurawatch", "solarmovie", "fmovies", "lookmovie", "bflix", "cineb",
                "cuevana", "pelisplus", "braflix", "pressplay", "doodstream", "rabbitstream",
                "streamff", "vidcloud", "vidplay", "vidsrc", "streamplay", "vload", "uqload",
                "streamhub", "voe.sx", "vidoza", "filelions", "dropload", "streamrapid"
            )
            if (streamingHostKeywords.any { host.contains(it) }) return true
            val streamingPathKeywords = listOf(
                "/episode/", "/watch/", "/series/", "/movie/", "/video/", "/stream/", "/player/", "/embed/", "/play/"
            )
            return streamingPathKeywords.any { lower.contains(it) }
        }

        fun isMediaOrStreamingHost(host: String): Boolean {
            if (host.isBlank()) return false
            val lower = host.lowercase()
            val mediaHostKeywords = listOf(
                "ravok", "abyss", "fireplayer", "jwpcdn", "jwplayer", "jwplatform", "brightcove",
                "wistia", "kaltura", "mux.com", "cloudflarestream", "mediadelivery.net", "b-cdn.net",
                "vdocipher", "sproutvideo", "streamtape", "megacloud", "vidstream", "dood",
                "mixdrop", "mp4upload", "filemoon", "streamwish", "streamhide", "vidmoly",
                "upstream", "cloudstream", "vidsrc", "2embed", "autoembed", "superembed",
                "multiembed", "storage.fireplayer", "fireplayer.stream", "rabbitstream", "vidcloud",
                "vidplay", "doodstream", "dropload", "filelions", "voe.sx", "uqload", "vidoza",
                "hydrax", "lulustream", "vtube", "streamvid", "rumble.com", "odysee.com",
                "bitchute.com", "ok.ru", "vkvideo", "sibnet.ru"
            )
            if (mediaHostKeywords.any { lower.contains(it) }) return true
            if (lower.endsWith(".stream") || lower.endsWith(".video") || lower.endsWith(".watch")) return true
            return false
        }

        fun isBettingOrAdUrl(url: String, domain: String = ""): Boolean {
            if (url.isBlank()) return false
            val cleanDomain = if (domain.isNotBlank()) domain.lowercase() else try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
            val lowerUrl = url.lowercase()

            val bettingKeywords = listOf(
                "1xbet", "betway", "stake.", "dafabet", "bet365", "mostbet", "parimatch", "melbet",
                "cloudbet", "spinbetter", "888casino", "bcgame", "1win", "vulkan", "pin-up", "betwinner",
                "bet9ja", "betfair", "linebet", "megapari", "sportybet", "bwin", "betano", "betsson",
                "caliente", "betclic", "roobet", "rollbit", "duelbits", "gamdom", "csgoroll", "lootbet",
                "ggbet", "casino", "poker", "jackpot", "slot", "betting", "bookmaker", "roulette"
            )
            if (bettingKeywords.any { cleanDomain.contains(it) }) return true

            val adRedirectKeywords = listOf(
                "onclickalgo", "admaven", "monetag", "hilltopads", "propellerads", "clickadu", "exoclick",
                "trafficjunky", "juicyads", "adcash", "popcash", "popads", "adsterra", "trafficfactory",
                "mgid", "realsrv", "zeroredirect", "adf.ly", "ouo.io", "linkvertise", "adnxs", "adkeeper",
                "bidvertiser", "clickaine", "adxprts", "tsyndicate", "yllix", "clksite", "clkmg", "popmyads",
                "adkernel", "revenuehits", "infolinks", "ad-maven", "deloton", "highperformancegate",
                "effectivecpmgate", "whomeverwhomever", "terraclicks", "tubecorp", "adclick", "clickguard",
                "richads", "galaksion", "syndication", "trafficstars", "trafficforce", "adtng", "ero-advertising"
            )
            if (adRedirectKeywords.any { cleanDomain.contains(it) }) return true

            val adParamKeywords = listOf(
                "/click/", "/redirect/", "?af=", "&s2=", "/popunder/", "utm_medium=pop",
                "ref=bet", "affiliate=bet", "tag=casino", "aff_id=", "offer_id=", "sub_id="
            )
            if (adParamKeywords.any { lowerUrl.contains(it) }) return true

            return false
        }
    }

    private fun buildCorsHeaders(requestOrigin: String?): Map<String, String> {
        val validOrigin = requestOrigin?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        return if (validOrigin != null) {
            mapOf(
                "Access-Control-Allow-Origin" to validOrigin,
                "Access-Control-Allow-Credentials" to "true",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
                "Access-Control-Allow-Headers" to "*",
                "Vary" to "Origin",
                "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0"
            )
        } else {
            WILDCARD_CORS_HEADERS
        }
    }

    private fun createBlockedResponse(
        resourceType: String,
        requestUrl: String = "",
        requestOrigin: String? = null
    ): WebResourceResponse {
        val corsHeaders = buildCorsHeaders(requestOrigin)
        val isWildcard = (corsHeaders === WILDCARD_CORS_HEADERS)

        // Check if request is an XHR/fetch VAST / VMAP XML ad tag (never <script>, <img>, or <iframe>):
        // return valid empty VAST XML so video players (JWPlayer, VideoJS, IMA SDK, FluidPlayer)
        // immediately skip ads without XML parse errors or credentialed CORS failures.
        val lowerUrl = requestUrl.lowercase()
        val cleanPath = lowerUrl.substringBefore('?').substringBefore('#')
        val isNonXmlAsset = cleanPath.endsWith(".js") || cleanPath.endsWith(".mjs") ||
                cleanPath.endsWith(".css") || cleanPath.endsWith(".html") || cleanPath.endsWith(".htm") ||
                cleanPath.endsWith(".png") || cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") ||
                cleanPath.endsWith(".gif") || cleanPath.endsWith(".svg") || cleanPath.endsWith(".webp")
        val isXhrOrOther = resourceType == "xmlhttprequest" || resourceType == "xhr" || resourceType == "other"
        val isVastOrVmapTag = isXhrOrOther && !isNonXmlAsset && (
                lowerUrl.contains("vast") || lowerUrl.contains("vmap") ||
                lowerUrl.contains("/pagead/ads?") || lowerUrl.contains("doubleclick.net/gampad/") ||
                lowerUrl.contains("ad-vast") || lowerUrl.contains("spotxchange") ||
                lowerUrl.contains("fwmrm.net/ad/")
        )

        if (isVastOrVmapTag) {
            val headers = if (isWildcard) {
                EMPTY_VAST_WILDCARD_HEADERS
            } else {
                corsHeaders + mapOf(
                    "Content-Type" to "text/xml; charset=utf-8",
                    "Content-Length" to EMPTY_VAST_BYTES.size.toString()
                )
            }
            return WebResourceResponse(
                "text/xml",
                "UTF-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream(EMPTY_VAST_BYTES)
            )
        }

        // Never spoof blocked Google IMA SDK (ima3.js) with an empty 0-byte 200 OK script:
        // firing script.onload while window.google.ima is undefined crashes player onload handlers,
        // whereas HTTP 403 triggers script.onerror so players invoke their native no-IMA fallback
        // (sites requiring a full IMA shim use Brave's google-ima.js $redirect surrogate).
        val isImaSdkScript = resourceType == "script" && (
                lowerUrl.contains("imasdk.googleapis.com/js/sdkloader/ima3") ||
                lowerUrl.contains("/instream/html5/ima3")
        )

        // Return HTTP 403 Forbidden with permissive CORS headers.
        // In-page fetch() and XMLHttpRequest calls are intercepted by the stealth client proxies
        // in AdBlockDocumentStart (throwing TypeError: net::ERR_BLOCKED_BY_CLIENT / onerror),
        // while DOM subresources (<script>, <img>, <iframe>) receive HTTP 403 and trigger onerror.
        if (!preferences.isAntiAdblockDetectionEnabled || isImaSdkScript) {
            val headers = if (isWildcard) {
                EMPTY_403_WILDCARD_HEADERS
            } else {
                corsHeaders + ("Content-Length" to "0")
            }
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked by Onyx Shields",
                headers,
                EmptyInputStream
            )
        }

        // When Adblocker Spoofing is enabled:
        // Return safe, type-aware HTTP 200 OK stubs with CORS headers to spoof ad scripts as loaded
        // and bypass anti-adblock detection walls without breaking page layout.
        return when (resourceType) {
            "image" -> {
                val headers = if (isWildcard) {
                    EMPTY_PNG_WILDCARD_HEADERS
                } else {
                    corsHeaders + mapOf(
                        "Content-Type" to "image/png",
                        "Content-Length" to TRANSPARENT_1X1_PNG.size.toString()
                    )
                }
                WebResourceResponse(
                    "image/png",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(TRANSPARENT_1X1_PNG)
                )
            }
            "script" -> {
                val headers = if (isWildcard) {
                    EMPTY_JS_WILDCARD_HEADERS
                } else {
                    corsHeaders + mapOf(
                        "Content-Type" to "application/javascript; charset=utf-8",
                        "Content-Length" to "0"
                    )
                }
                WebResourceResponse(
                    "application/javascript",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    EmptyInputStream
                )
            }
            "stylesheet" -> {
                val headers = if (isWildcard) {
                    EMPTY_CSS_WILDCARD_HEADERS
                } else {
                    corsHeaders + mapOf(
                        "Content-Type" to "text/css; charset=utf-8",
                        "Content-Length" to "0"
                    )
                }
                WebResourceResponse(
                    "text/css",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    EmptyInputStream
                )
            }
            "sub_frame" -> {
                val headers = if (isWildcard) {
                    BLOCKED_SUBFRAME_WILDCARD_HEADERS
                } else {
                    corsHeaders + mapOf(
                        "Content-Type" to "text/html; charset=utf-8",
                        "Content-Length" to BLOCKED_SUBFRAME_BYTES.size.toString()
                    )
                }
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(BLOCKED_SUBFRAME_BYTES)
                )
            }
            "font" -> {
                val headers = if (isWildcard) {
                    EMPTY_FONT_WILDCARD_HEADERS
                } else {
                    corsHeaders + mapOf(
                        "Content-Type" to "font/woff2",
                        "Content-Length" to "0"
                    )
                }
                WebResourceResponse(
                    "font/woff2",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    EmptyInputStream
                )
            }
            "media" -> {
                val headers = if (isWildcard) {
                    EMPTY_MEDIA_WILDCARD_HEADERS
                } else {
                    corsHeaders + mapOf(
                        "Content-Type" to "video/mp4",
                        "Content-Length" to "0"
                    )
                }
                WebResourceResponse(
                    "video/mp4",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    EmptyInputStream
                )
            }
            else -> {
                val headers = if (isWildcard) {
                    EMPTY_TEXT_WILDCARD_HEADERS
                } else {
                    corsHeaders + mapOf(
                        "Content-Type" to "text/plain; charset=utf-8",
                        "Content-Length" to "0"
                    )
                }
                WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    EmptyInputStream
                )
            }
        }
    }

    private fun createSurrogateResponse(
        redirectData: String,
        resourceType: String,
        requestOrigin: String? = null
    ): WebResourceResponse? {
        return try {
            val corsHeaders = buildCorsHeaders(requestOrigin)

            if (redirectData.startsWith("data:", ignoreCase = true)) {
                val commaIndex = redirectData.indexOf(',')
                if (commaIndex != -1) {
                    val meta = redirectData.substring(5, commaIndex)
                    val body = redirectData.substring(commaIndex + 1)
                    val isBase64 = meta.contains(";base64", ignoreCase = true)
                    val mimeType = meta.substringBefore(';').ifBlank {
                        when {
                            resourceType == "image" -> "image/png"
                            resourceType == "xml" || redirectData.contains("<VAST") || redirectData.contains("<vmap") -> "text/xml"
                            else -> "application/javascript"
                        }
                    }
                    val bytes = if (isBase64) {
                        Base64.decode(body, Base64.DEFAULT)
                    } else {
                        Uri.decode(body).toByteArray(Charsets.UTF_8)
                    }
                    return WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        200,
                        "OK",
                        corsHeaders + mapOf(
                            "Content-Type" to "$mimeType; charset=utf-8",
                            "Content-Length" to bytes.size.toString()
                        ),
                        ByteArrayInputStream(bytes)
                    )
                }
            }

            val mime = when {
                resourceType == "image" -> "image/png"
                resourceType == "xml" || redirectData.startsWith("<?xml") || redirectData.contains("<VAST") || redirectData.contains("<vmap") -> "text/xml"
                else -> "application/javascript"
            }
            val bytes = redirectData.toByteArray(Charsets.UTF_8)
            WebResourceResponse(
                mime,
                "UTF-8",
                200,
                "OK",
                corsHeaders + mapOf(
                    "Content-Type" to "$mime; charset=utf-8",
                    "Content-Length" to bytes.size.toString()
                ),
                ByteArrayInputStream(bytes)
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun recordBlockedRequest(view: WebView?) {
        preferences.incrementBlockedRequests()
        (view as? OnyxWebView)?.incrementPageBlockedCount()
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        val reqUri = request.url ?: return null

        // 0. Secure Local Asset Isolation via WebViewAssetLoader (HTTPS virtual origins)
        val virtualAssetResponse = LocalFileLoader.interceptVirtualAsset(context, reqUri)
        if (virtualAssetResponse != null) {
            return virtualAssetResponse
        }

        return try {
            val url = reqUri.toString()

            // Track current page URL and check main frame document against ad/domain blocklists
            if (request.isForMainFrame) {
                if (!isSyntheticOrDataUrl(url)) {
                    (view as? OnyxWebView)?.hasActiveMediaSubresource = false
                    currentPageUrl = url
                    pendingMainFrameUrl = url
                    (view as? OnyxWebView)?.let {
                        it.pendingMainFrameUrl = url
                    }
                }
                
                // Intercept local files (HTML, MHTML, MHT, Markdown, Plain Text) for main frame
                if (url.startsWith("content://", ignoreCase = true) || url.startsWith("file://", ignoreCase = true)) {
                    // Never intercept android assets or resources
                    if (url.startsWith("file:///android_asset/", ignoreCase = true) || url.startsWith("file:///android_res/", ignoreCase = true)) {
                        return null
                    }
                    val onyxWv = view as? OnyxWebView
                    if (url.startsWith("file://", ignoreCase = true) && LocalFileLoader.isSensitiveOrRestrictedPath(context, url)) {
                        if (!LocalFileLoader.isAuthorizedPreviewForTab(onyxWv?.tabId, url)) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                403,
                                "Forbidden",
                                BLOCKED_STORAGE_ACCESS_HEADERS,
                                ByteArrayInputStream(BLOCKED_STORAGE_ACCESS_BYTES)
                            )
                        }
                    }
                    val lower = url.lowercase()
                    // CRITICAL: Chromium native Blink MHTML parser handles file:// .mht/.mhtml URLs.
                    // Returning null allows Chromium to parse multipart/related natively without black screens!
                    if (url.startsWith("file://", ignoreCase = true) && (lower.endsWith(".mht") || lower.endsWith(".mhtml"))) {
                        return null
                    }
                    val localResponse = LocalFileLoader.interceptLocalFile(context, url)
                    if (localResponse != null) {
                        return localResponse
                    }
                }

                // Check main frame ad blocking (Domain Blocking / Interstitial Redirection - Brave Parity)
                if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                    val pageDomain = preferences.cleanDomain(currentPageUrl)
                    val reqDomain = preferences.cleanDomain(url)
                    val isWhitelisted = preferences.isDomainWhitelisted(pageDomain) || preferences.isDomainWhitelisted(reqDomain)

                    if (preferences.isAdBlockEnabled && !isWhitelisted) {
                        val isAggressive = preferences.getEffectiveBlockingLevel(pageDomain) == BrowserPreferences.BLOCKING_AGGRESSIVE
                        val onyxWv = view as? OnyxWebView
                        // Only the first navigation of a not-yet-displayed popup is checked as a popup ad.
                        // In Standard mode (Brave parity): Main-frame top-level navigations are never cancelled
                        // or 403-intercepted by adblock rules (DomainBlockingType::kNone in Brave), UNLESS it is a child popup tab.
                        if (onyxWv != null && onyxWv.isPopupPendingDisplay && isPopupAd(url, onyxWv)) {
                            recordBlockedRequest(view)
                            onyxWv.post {
                                val tabId = onyxWv.tabId
                                if (tabId.isNotBlank()) {
                                    tabActionCallback?.closeTab(tabId)
                                }
                            }
                            return WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                403,
                                "Blocked by Onyx Shields",
                                BLOCKED_MAIN_FRAME_WILDCARD_HEADERS,
                                ByteArrayInputStream(BLOCKED_MAIN_FRAME_HTML_BYTES)
                            )
                        }

                        // In Aggressive mode (DomainBlockingType::kAggressive), main-frame ad domains can also be blocked.
                        if (isAggressive) {
                            val method = request.method ?: "GET"
                            val blockedByEngine = AdBlockEngine.shouldBlock(url, currentPageUrl, "main_frame", method)
                            val blockedByAggressive = AdBlockDomainManager.isBlockedInAggressive(reqDomain)

                            if (blockedByEngine || blockedByAggressive) {
                                recordBlockedRequest(view)
                                return WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    403,
                                    "Blocked by Onyx Shields",
                                    BLOCKED_MAIN_FRAME_WILDCARD_HEADERS,
                                    ByteArrayInputStream(BLOCKED_MAIN_FRAME_HTML_BYTES)
                                )
                            }
                        }
                    }
                }
                
                return null
            }

            // Only process http/https network requests for adblocking; intercept local sub-resources
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                if (url.startsWith("file:///android_asset/", ignoreCase = true) || url.startsWith("file:///android_res/", ignoreCase = true)) {
                    return null
                }
                if (url.startsWith("file://", ignoreCase = true) || url.startsWith("content://", ignoreCase = true)) {
                    val onyxWv = view as? OnyxWebView
                    if (url.startsWith("file://", ignoreCase = true) && LocalFileLoader.isSensitiveOrRestrictedPath(context, url)) {
                        if (!LocalFileLoader.isAuthorizedPreviewForTab(onyxWv?.tabId, url)) {
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", EMPTY_403_NO_HEADERS, EmptyInputStream)
                        }
                    }
                    return LocalFileLoader.interceptLocalSubResource(context, url)
                }
                return null
            }

            val requestOrigin = getHeaderCaseInsensitive(request.requestHeaders, "Origin")
            val pageUrl = run {
                val headers = request.requestHeaders
                getHeaderCaseInsensitive(headers, "Referer")
                    ?: requestOrigin
                    ?: currentPageUrl.takeIf { it.isNotBlank() }
                    ?: (view as? OnyxWebView)?.url?.takeIf { it.isNotBlank() }
                    ?: (view as? WebView)?.url?.takeIf { it.isNotBlank() }
                    ?: ""
            }

            val pageDomain = preferences.cleanDomain(pageUrl)
            val reqDomain = preferences.cleanDomain(url)
            val resourceType = detectResourceType(request)
            val isThirdParty = PrivacyUrlSanitizer.isThirdPartyDomain(reqDomain, pageDomain)

            val interceptContext = InterceptRequestContext(
                view = view,
                onyxWebView = view as? OnyxWebView,
                request = request,
                requestUrl = url,
                requestHost = reqDomain,
                pageUrl = pageUrl,
                pageHost = pageDomain,
                isMainFrame = request.isForMainFrame,
                resourceType = resourceType,
                isThirdParty = isThirdParty
            )

            RealInterceptorChain(
                interceptors = requestInterceptors,
                index = 0,
                context = interceptContext
            ).proceed(interceptContext)
        } catch (t: Throwable) {
            null
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        val didCrash = detail?.didCrash() ?: false
        android.util.Log.e("OnyxWebViewClient", "WebView render process exited: didCrash=$didCrash")

        try {
            val onyxWv = view as? OnyxWebView
            val tabId = onyxWv?.tabId
            if (tabId != null && tabId.isNotBlank()) {
                val parent = onyxWv.parent as? android.view.ViewGroup
                parent?.removeView(onyxWv)
                tabActionCallback?.onRendererCrash(tabId)
                onyxWv.destroy()
            }
        } catch (t: Throwable) {
            android.util.Log.e("OnyxWebViewClient", "Error handling onRenderProcessGone", t)
        }

        // Return true to prevent host application termination by the OS
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null) return false
        val uri = request.url ?: return false
        return handleUrlLoading(view, uri, request.isForMainFrame, request.hasGesture(), request.isRedirect)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("handleUrlLoading(view, Uri.parse(url), true)"))
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return handleUrlLoading(view, Uri.parse(url), isForMainFrame = true)
    }

    private fun handleUrlLoading(
        view: WebView?,
        uri: Uri,
        isForMainFrame: Boolean,
        hasGesture: Boolean = true,
        isRedirect: Boolean = false
    ): Boolean {
        return try {
            val url = uri.toString()
            val normalizedUrl = url.trimEnd('/')
            val scheme = uri.scheme?.lowercase() ?: ""

            // ── One-Tap UserScript Web Install Interception ───────────────────────
            if (com.onyx.browser.userscript.UserScriptManager.isUserScriptUrl(url)) {
                findFragmentActivity(view?.context)?.let { act ->
                    view?.post {
                        com.onyx.browser.ui.settings.InstallUserScriptBottomSheet.show(act.supportFragmentManager, url)
                    }
                    return true
                }
            }

            if (isForMainFrame && url.isNotBlank() && !isSyntheticOrDataUrl(url)) {
                if (checkAndBreakRedirectLoop(view, url)) {
                    return true
                }
                pendingMainFrameUrl = url
                isMainFrameDocumentLoaded = false
                (view as? OnyxWebView)?.let {
                    it.pendingMainFrameUrl = url
                    it.isMainFrameDocumentLoaded = false
                    it.applyUserAgentForUrl(url)
                }
            }

            // ── Tracking URL Cleanup (strip tracking query params) ─────────────────
            val host = uri.host?.lowercase() ?: ""
            val isAuthOrMeta = isMetaDomain(host) || isCaptchaOrAuthUrl(url, host) ||
                    isOAuthOrLoginProvider(host) || isOAuthOrLoginUrl(url) ||
                    host.contains("login") || host.contains("auth") || host.contains("checkpoint")

            if (isAuthOrMeta) {
                (view as? OnyxWebView)?.let { wv ->
                    wv.lastOAuthInteractionTimestamp = System.currentTimeMillis()
                    try {
                        wv.cookieManager.setAcceptCookie(true)
                        wv.cookieManager.setAcceptThirdPartyCookies(view, true)
                    } catch (_: Exception) {}
                }
            }

            if (isForMainFrame && preferences.isAutoRedirectTrackingUrlsEnabled &&
                (scheme == "http" || scheme == "https")) {
                if (!isAuthOrMeta) {
                    val cleaned = stripTrackingParams(url)
                    if (cleaned != url) {
                        view?.post { view.loadUrl(cleaned) }
                        return true
                    }
                }
            }

            // ── AMP Redirect ───────────────────────────────────────────────────────
            if (isForMainFrame && preferences.isAutoRedirectAmpEnabled &&
                (scheme == "http" || scheme == "https")) {
                val canonical = resolveAmpUrl(url)
                if (canonical != null && canonical != url) {
                    view?.post { view.loadUrl(canonical) }
                    return true
                }
            }

            // ── HTTPS Upgrade ─────────────────────────────────────────────────────
            val httpsMode = preferences.httpsUpgradeMode
            if (scheme == "http" && isForMainFrame &&
                httpsMode != BrowserPreferences.HTTPS_MODE_DISABLED) {
                if (!upgradedUrls.contains(url) && !upgradedUrls.contains(normalizedUrl)) {
                    upgradedUrls.add(url)
                    upgradedUrls.add(normalizedUrl)
                    val httpsUrl = url.replaceFirst("http://", "https://")
                    view?.post { view.loadUrl(httpsUrl) }
                    return true
                }
            }

            // ── Main-frame Ad & Popup Navigation Interception (Brave Parity) ───────
            if (isForMainFrame && (scheme == "http" || scheme == "https")) {
                val pageDomain = preferences.cleanDomain(currentPageUrl)
                val reqDomain = preferences.cleanDomain(url)
                val isWhitelisted = preferences.isDomainWhitelisted(pageDomain) || preferences.isDomainWhitelisted(reqDomain)

                if (preferences.isAdBlockEnabled && !isWhitelisted) {
                    val isAggressive = preferences.getEffectiveBlockingLevel(pageDomain) == BrowserPreferences.BLOCKING_AGGRESSIVE
                    val onyxWv = view as? OnyxWebView
                    // Popup-ad heuristics apply only to the first navigation of a popup window that has
                    // not been shown yet. Once a tab is displayed it behaves like any normal tab.
                    val isPendingPopup = onyxWv?.isPopupPendingDisplay == true

                    if (isPendingPopup && isPopupAd(url, onyxWv)) {
                        preferences.incrementBlockedRequests()
                        onyxWv?.post {
                            val tabId = onyxWv.tabId
                            if (tabId.isNotBlank()) {
                                tabActionCallback?.closeTab(tabId)
                            }
                        }
                        return true // Cancel the ad navigation!
                    }

                    val isAuthOrSecurityFlow = isOAuthOrLoginProvider(reqDomain) ||
                            isOAuthOrLoginProvider(pageDomain) ||
                            isMetaDomain(reqDomain) ||
                            isCaptchaOrAuthUrl(url, reqDomain) ||
                            isOAuthOrLoginUrl(url) ||
                            (onyxWv?.isWithinOAuthGracePeriod() == true)

                    val isCrossDomain = pageDomain.isNotBlank() && reqDomain.isNotBlank() &&
                            reqDomain != pageDomain &&
                            !reqDomain.endsWith(".$pageDomain") &&
                            !pageDomain.endsWith(".$reqDomain")

                    // Same-site navigations are never treated as ads (Brave parity).
                    if (!isAuthOrSecurityFlow && isCrossDomain) {
                        val isParentStreaming = isStreamingOrMediaPage(currentPageUrl) ||
                                isMediaOrStreamingHost(pageDomain) ||
                                MediaPlaybackBridge.isVideoAvailableForTab(onyxWv?.tabId)

                        // 1. Check if adblock engine blocks this main_frame navigation ($document / $popup rules)
                        val blockedByEngine = AdBlockEngine.shouldBlock(url, currentPageUrl, "main_frame") ||
                                AdBlockEngine.checkUrlCached(url, currentPageUrl, "popup")

                        // 2. Check if navigating to a known betting or ad redirect URL
                        val isBettingOrAd = isBettingOrAdUrl(url, reqDomain)
                        val isAdTarget = blockedByEngine || isBettingOrAd

                        // 3. On streaming/media pages, block clickjack redirects to ad/betting domains even on tap
                        val isStreamingClickjack = isParentStreaming && isAdTarget

                        // 4. Script-driven redirects (no user gesture) to ad/betting domains
                        val isScriptAdRedirect = !hasGesture && isAdTarget

                        // 5. Aggressive mode: also cancel script-driven cross-site navigations.
                        //    Links the user actually taps and server redirects always proceed.
                        val isAggressiveScriptNav = isAggressive && !hasGesture && !isRedirect

                        if (isStreamingClickjack || isScriptAdRedirect || isAggressiveScriptNav) {
                            preferences.incrementBlockedRequests()
                            if (isPendingPopup) {
                                onyxWv?.post {
                                    val tabId = onyxWv.tabId
                                    if (tabId.isNotBlank()) {
                                        tabActionCallback?.closeTab(tabId)
                                    }
                                }
                            }
                            return true // Cancel the ad navigation / clickjack redirect!
                        }
                    }
                }

                // If this is a pending popup window and it's NOT an ad, safely display it!
                if (view is OnyxWebView && view.isPopupPendingDisplay) {
                    val onyxWv = view
                    if (!isPopupAd(url, onyxWv)) {
                        val tabId = onyxWv.tabId
                        val tab = tabActionCallback?.getTabById(tabId)
                        if (tab != null) {
                            tabActionCallback?.displayPopupTab(tab)
                        }
                    }
                }
            }

            // Block navigation to sensitive or restricted private app/system filesystem paths
            if (scheme == "file") {
                val onyxWv = view as? OnyxWebView
                if (LocalFileLoader.isSensitiveOrRestrictedPath(context, url)) {
                    if (!LocalFileLoader.isAuthorizedPreviewForTab(onyxWv?.tabId, url)) {
                        return true // Abort navigation!
                    }
                }
            }

            // Handle javascript: pseudo-URLs (e.g. javascript:document.getElementById('102111').submit();)
            // Modern Chromium restricts top-level navigation to javascript: URLs; evaluating directly ensures
            // all script actions, form submissions, and bookmarklets execute reliably without being swallowed.
            if (scheme == "javascript") {
                val rawScript = url.substringAfter("javascript:", "").trim()
                val isVoid = rawScript.isBlank() || rawScript == ";" ||
                        rawScript.equals("void(0)", ignoreCase = true) ||
                        rawScript.equals("void(0);", ignoreCase = true) ||
                        rawScript.equals("void 0", ignoreCase = true) ||
                        rawScript.equals("void 0;", ignoreCase = true)
                if (!isVoid && view != null) {
                    val script = if (rawScript.contains('%')) {
                        try {
                            java.net.URLDecoder.decode(rawScript.replace("+", "%2B"), "UTF-8")
                        } catch (_: Exception) {
                            rawScript
                        }
                    } else {
                        rawScript
                    }
                    view.post {
                        view.evaluateJavascript(script, null)
                    }
                }
                return true
            }

            // Standard web schemes — let WebView handle them normally
            if (scheme == "http" || scheme == "https" || scheme == "about" ||
                scheme == "data" || scheme == "blob" || scheme == "javascript" ||
                scheme == "file" || scheme == "content") {
                // If "Open links in app" is enabled, check if there's a specialized app for this HTTP link
                if (isForMainFrame && preferences.isOpenLinksInAppEnabled && (scheme == "http" || scheme == "https")) {
                    if (bypassAppInterceptUrls.remove(url) || bypassAppInterceptUrls.remove(normalizedUrl)) {
                        // User chose "Stay in Onyx" for this URL — let WebView proceed directly
                        return false
                    }
                    if (tryOpenAppForHttpLink(view, uri, url)) {
                        return true
                    }
                }
                return false
            }

            // External app URL schemes (tg://, whatsapp://, tel:, mailto:, sms:, geo:, intent:, market:, etc.)
            dispatchExternalScheme(view, uri, url, scheme)
            true
        } catch (_: Throwable) {
            true
        }
    }

    private fun dispatchExternalScheme(
        view: WebView?,
        uri: Uri,
        url: String,
        scheme: String
    ): Boolean {
        if (scheme == "intent") {
            return handleIntentScheme(view, url)
        }

        // Essential communication schemes always open external apps directly
        val isEssentialScheme = scheme == "tel" || scheme == "mailto" ||
                scheme == "sms" || scheme == "smsto" || scheme == "mms" ||
                scheme == "geo"

        // If user explicitly disabled "Open links in app" and this is not essential communication
        if (!preferences.isOpenLinksInAppEnabled && !isEssentialScheme) {
            return true
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (isEssentialScheme) {
            return try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                handleAppNotFoundFallback(view, scheme, uri)
                true
            } catch (_: Exception) {
                true
            }
        }

        val appName = when (scheme) {
            "tg", "telegram" -> "Telegram"
            "whatsapp" -> "WhatsApp"
            "twitter", "x" -> "X (Twitter)"
            "instagram" -> "Instagram"
            "fb" -> "Facebook"
            "fb-messenger" -> "Messenger"
            "discord" -> "Discord"
            "spotify" -> "Spotify"
            "market" -> "Google Play"
            else -> null
        }

        if (!promptOrLaunchApp(intent, appName, fallback = null)) {
            handleAppNotFoundFallback(view, scheme, uri)
        }
        return true
    }

    private fun handleIntentScheme(view: WebView?, url: String): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            // Security check: Block attempts to target internal Onyx Browser components
            val targetPkg = intent.`package`
            if (targetPkg != null && targetPkg.equals(context.packageName, ignoreCase = true)) {
                return false
            }

            fun resolveSafeWebUri(rawUrl: String?): Uri? {
                if (rawUrl.isNullOrBlank()) return null
                val parsed = try { Uri.parse(rawUrl.trim()) } catch (_: Exception) { null } ?: return null
                val scheme = parsed.scheme?.lowercase() ?: return null
                if (scheme != "http" && scheme != "https") return null
                if (parsed.host.isNullOrBlank()) return null
                return parsed
            }

            fun navigateSafeWebUri(uri: Uri) {
                val target = uri.toString()
                bypassAppInterceptUrls.add(target)
                if (!handleUrlLoading(view, uri, isForMainFrame = true)) {
                    view?.post { view.loadUrl(target) }
                }
            }

            val fallbackUri = resolveSafeWebUri(intent.getStringExtra("browser_fallback_url"))
                ?: intent.data?.let { resolveSafeWebUri(it.toString()) }

            val fallbackAction: (() -> Unit)? = if (fallbackUri != null) {
                { navigateSafeWebUri(fallbackUri) }
            } else null

            val isInstalled = isExternalAppInstalledForIntent(intent)

            if (isInstalled && preferences.isOpenLinksInAppEnabled) {
                return promptOrLaunchApp(
                    intent = intent,
                    appName = null,
                    fallback = fallbackAction
                )
            }

            // 1st Fallback: If app is not installed or open-in-app is disabled, navigate to web fallback
            if (fallbackUri != null) {
                navigateSafeWebUri(fallbackUri)
                return true
            }

            // 2nd Fallback: explicit package -> Google Play Store
            val pkg = intent.`package`
            if (!pkg.isNullOrBlank() && preferences.isOpenLinksInAppEnabled) {
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(marketIntent)
                } catch (_: Exception) {
                    view?.post { view.loadUrl("https://play.google.com/store/apps/details?id=$pkg") }
                }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun handleAppNotFoundFallback(view: WebView?, scheme: String, uri: Uri) {
        val appPackage = when (scheme) {
            "tg", "telegram" -> "org.telegram.messenger"
            "whatsapp" -> "com.whatsapp"
            "twitter", "x" -> "com.twitter.android"
            "instagram" -> "com.instagram.android"
            "fb" -> "com.facebook.katana"
            "fb-messenger" -> "com.facebook.orca"
            "discord" -> "com.discord"
            "sgnl", "signal" -> "org.thoughtcrime.securesms"
            "viber" -> "com.viber.voip"
            "skype" -> "com.skype.raider"
            "line" -> "jp.naver.line.android"
            "spotify" -> "com.spotify.music"
            else -> null
        }

        if (appPackage != null) {
            try {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackage")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
            } catch (_: Exception) {
                view?.post { view.loadUrl("https://play.google.com/store/apps/details?id=$appPackage") }
            }
        } else {
            try {
                Toast.makeText(context, "No app found to handle this link", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun tryOpenAppForHttpLink(view: WebView?, uri: Uri, url: String): Boolean {
        if (!preferences.isOpenLinksInAppEnabled) return false
        val host = uri.host?.lowercase() ?: return false

        // Prevent dialog harassment on internal same-site navigations (e.g. browsing inside youtube.com or reddit.com)
        val currentUrl = view?.url
        if (!currentUrl.isNullOrBlank()) {
            val currentHost = try { Uri.parse(currentUrl).host?.lowercase() } catch (_: Exception) { null }
            if (!currentHost.isNullOrBlank()) {
                val isSameSite = currentHost == host ||
                        (host.endsWith(".$currentHost") || currentHost.endsWith(".$host")) ||
                        (host.contains("youtube.com") && currentHost.contains("youtube.com")) ||
                        (host.contains("reddit.com") && currentHost.contains("reddit.com"))
                if (isSameSite) {
                    return false
                }
            }
        }

        val stayInOnyxAction: () -> Unit = {
            bypassAppInterceptUrls.add(url)
            view?.post {
                view.loadUrl(url)
            }
        }

        // Quick dispatch for dedicated app links when clicked from within webpages:
        // E.g. t.me links: https://t.me/username -> tg://resolve?domain=username
        if (host == "t.me" || host == "telegram.me") {
            val path = uri.path?.removePrefix("/") ?: ""
            if (path.isNotBlank() && !path.startsWith("s/") && !path.startsWith("share") && !path.contains("/")) {
                val tgUri = Uri.parse("tg://resolve?domain=$path")
                val intent = Intent(Intent.ACTION_VIEW, tgUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (isExternalAppInstalledForIntent(intent)) {
                    return promptOrLaunchApp(intent, "Telegram", fallback = stayInOnyxAction)
                }
            }
        } else if (host == "wa.me") {
            val phone = uri.path?.removePrefix("/") ?: ""
            if (phone.isNotBlank()) {
                val text = uri.getQueryParameter("text")
                val waUrl = if (!text.isNullOrBlank()) {
                    "whatsapp://send?phone=$phone&text=${Uri.encode(text)}"
                } else {
                    "whatsapp://send?phone=$phone"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (isExternalAppInstalledForIntent(intent)) {
                    return promptOrLaunchApp(intent, "WhatsApp", fallback = stayInOnyxAction)
                }
            }
        } else if (host.contains("youtube.com") || host == "youtu.be" || host.contains("reddit.com")) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // To prevent an infinite loop where the system resolves the link back to Onyx Browser,
            // we query for activities and filter out our own package.
            val pm = context.packageManager
            val resolveInfoList = try {
                pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Exception) {
                emptyList()
            }
            val externalApp = resolveInfoList.firstOrNull { it.activityInfo?.packageName != context.packageName }
            
            if (externalApp != null) {
                intent.setPackage(externalApp.activityInfo.packageName)
                val appLabel = externalApp.loadLabel(pm).toString()
                return promptOrLaunchApp(intent, appLabel, fallback = stayInOnyxAction)
            }
        }
        return false
    }

    /**
     * Strip known tracking query parameters from a URL.
     * Returns the cleaned URL, or the original if no params were stripped.
     */
    internal fun stripTrackingParams(url: String): String =
        PrivacyUrlSanitizer.stripTrackingParams(url)

    /**
     * Detect Google AMP URLs and return the canonical URL.
     * Returns null if the URL is not AMP.
     */
    internal fun resolveAmpUrl(url: String): String? =
        PrivacyUrlSanitizer.resolveAmpUrl(url)

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (view != null && !url.isNullOrBlank()) {
            onPageCommitVisibleCallback?.invoke(view, url)
        }
        if (url.isNullOrBlank()) return
        val isHttp = url.startsWith("http://") || url.startsWith("https://")
        if (!isHttp) return
        val isWhitelisted = preferences.isDomainWhitelisted(url)

        val pageDomain = preferences.cleanDomain(url)
        val isAuthOrMeta = isMetaDomain(pageDomain) || isCaptchaOrAuthUrl(url, pageDomain) ||
                isOAuthOrLoginProvider(pageDomain) || isOAuthOrLoginUrl(url) ||
                pageDomain.contains("login") || pageDomain.contains("auth") || pageDomain.contains("checkpoint")

        if ((view as? OnyxWebView)?.isDesktopModeEnabledForCurrentPage() == true) {
            DesktopModeManager.enforceDesktopViewport(view)
        }

        if (!isAuthOrMeta) {
            val isAdBlockActiveForPage = preferences.isAdBlockEnabled && !isWhitelisted
            view?.evaluateJavascript("window.__onyxAdBlockEnabled = $isAdBlockActiveForPage;", null)
        }

        if (preferences.isAdBlockEnabled && preferences.getEffectiveBlockingLevel(pageDomain) == BrowserPreferences.BLOCKING_AGGRESSIVE && !isWhitelisted && !isAuthOrMeta) {
            val urlLower = url.lowercase()
            val isCloudflareChallengePage = urlLower.contains("__cf_chl") ||
                    urlLower.contains("/cdn-cgi/challenge-platform") ||
                    urlLower.contains("/cdn-cgi/cf-challenge") ||
                    isCaptchaOrAuthUrl(url, pageDomain)
            if (!isCloudflareChallengePage) {
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
        }

        if (preferences.isDoNotTrackEnabled && !isAuthOrMeta) {
            val dntJs = """
                (function() {
                    Object.defineProperty(navigator, 'doNotTrack', { get: function() { return '1'; } });
                })();
            """.trimIndent()
            view?.evaluateJavascript(dntJs, null)
        }

        // Language Fingerprint Protection — never tamper on auth/checkpoint pages
        if (preferences.isFingerprintLangEnabled && !isWhitelisted && !isAuthOrMeta) {
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

        // Block Smart App Banners ("Open in App" notices) — never run on auth/checkpoint pages
        if (preferences.isBlockAppBannerEnabled && !isAuthOrMeta) {
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

        // Cosmetic element hiding (CSS injection) — never hide containers on auth/checkpoint pages
        if (preferences.isCosmeticFilteringEnabled && preferences.isAdBlockEnabled && !isWhitelisted && !isAuthOrMeta) {
            try {
                val cosmeticCss = AdBlockEngine.getCosmeticCss(url)
                if (cosmeticCss.isNotBlank()) {
                    injectCosmeticCss(view, cosmeticCss)
                }
            } catch (_: Throwable) {}
        } else if ((isWhitelisted || !preferences.isAdBlockEnabled) && !isAuthOrMeta) {
            val cleanupJs = """
                (function() {
                    try {
                        var c1 = document.getElementById('onyx-universal-cosmetic');
                        if (c1) c1.remove();
                        var c2 = document.getElementById('onyx-adblock-cosmetic');
                        if (c2) c2.remove();
                    } catch(e) {}
                })();
            """.trimIndent()
            view?.evaluateJavascript(cleanupJs, null)
        }

        // User Custom Blocked Elements (per-domain custom cosmetic rules)
        if (!isAuthOrMeta) {
            try {
                val customBlockedCss = preferences.getCustomBlockedCssForDomain(url)
                if (customBlockedCss.isNotBlank()) {
                    val escapedCss = org.json.JSONObject.quote(customBlockedCss)
                    val customJs = """
                        (function() {
                            try {
                                var s = document.getElementById('onyx-user-custom-blocked');
                                if (!s) {
                                    s = document.createElement('style');
                                    s.id = 'onyx-user-custom-blocked';
                                    s.type = 'text/css';
                                    (document.head || document.documentElement).appendChild(s);
                                }
                                s.textContent = $escapedCss;
                            } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(customJs, null)
                } else {
                    val removeJs = """
                        (function() {
                            try {
                                var s = document.getElementById('onyx-user-custom-blocked');
                                if (s) s.remove();
                            } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(removeJs, null)
                }
            } catch (_: Throwable) {}
        }

        // Fingerprint Protection (JS API spoofing) — completely disabled on auth & security pages
        if (preferences.isFingerprintProtectionEnabled && !isWhitelisted && !isAuthOrMeta) {
            injectFingerprintProtection(view)
        }

        // Passkey / WebAuthn & PasswordCredential support — allow on all auth & login sites (GitHub, Google, Microsoft, etc.), only skip Meta/Facebook internal flows
        val isMetaAuthOnly = isMetaDomain(pageDomain) || isMetaDomain(preferences.cleanDomain(url ?: ""))
        if ((preferences.isPasskeysEnabled || preferences.isAutofillEnabled) && !isMetaAuthOnly) {
            view?.evaluateJavascript(PasskeyWebAuthnBridge.getWebAuthnPolyfillJs(), null)
        }

        // Media Monitor & Background Playback — only for non-auth content pages
        if (!isAuthOrMeta) {
            view?.evaluateJavascript(MediaPlaybackManager.mediaMonitorScript, null)
            if (preferences.isBackgroundPlayEnabled) {
                view?.evaluateJavascript(MediaPlaybackManager.backgroundPlaybackScript, null)
            }
        }

        // Re-adjust Desktop Viewport after full page and sub-resources load
        if ((view as? OnyxWebView)?.isDesktopModeEnabledForCurrentPage() == true) {
            DesktopModeManager.enforceDesktopViewport(view)
        }
    }

    private fun isSyntheticOrDataUrl(url: String?): Boolean {
        return OnyxWebView.isSyntheticOrDataUrl(url)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val onyxWv = view as? OnyxWebView
        if (onyxWv?.isLoadingSyntheticPage == true) {
            // This onPageStarted event was triggered by loading the synthetic error page.
            // Reset the flag but preserve currentSyntheticState and lastFailingUrl.
            // Do not run adblock/DOM scripts or clear error state.
            onyxWv.isLoadingSyntheticPage = false
            return
        }

        if (!url.isNullOrBlank()) {
            if (isSyntheticOrDataUrl(url)) {
                // Do not let data: or asset error pages overwrite the active URL or clear synthetic error state!
                return
            }
            if (checkAndBreakRedirectLoop(view, url)) {
                return
            }
            onyxWv?.pageBlockedCount = 0
            val previousUrl = currentPageUrl
            currentPageUrl = url
            pendingMainFrameUrl = url
            isMainFrameDocumentLoaded = false
            onyxWv?.let {
                it.pendingMainFrameUrl = url
                it.isMainFrameDocumentLoaded = false
            }
            if (previousUrl.isNotBlank() && previousUrl != url &&
                com.onyx.browser.media.MediaPlaybackBridge.currentPlayingTabId == onyxWv?.tabId) {
                com.onyx.browser.media.MediaPlaybackBridge.resetMediaPlayback(context)
            }
            onyxWv?.clearSyntheticState()
            onyxWv?.clearUserScriptState()
            onyxWv?.applyUserAgentForUrl(url)
            onUrlChanged(url)
            injectUserScripts(view, url, com.onyx.browser.userscript.RunAt.DOCUMENT_START)

            val pageDomain = preferences.cleanDomain(url)
            val isAuthOrMeta = isMetaDomain(pageDomain) || isCaptchaOrAuthUrl(url, pageDomain) ||
                    isOAuthOrLoginProvider(pageDomain) || isOAuthOrLoginUrl(url) ||
                    pageDomain.contains("login") || pageDomain.contains("auth") || pageDomain.contains("checkpoint")
            val isPopup = onyxWv?.isPopupTab == true || onyxWv?.isPopupPendingDisplay == true ||
                    (tabActionCallback?.getTabById(onyxWv?.tabId ?: "")?.parentId != null)
            val isWithinGrace = onyxWv?.isWithinOAuthGracePeriod() == true

            if (isAuthOrMeta) {
                onyxWv?.lastOAuthInteractionTimestamp = System.currentTimeMillis()
                // Authentication and CAPTCHA flows (such as Facebook Arkose Labs FunCaptcha inside an iframe)
                // strictly require cross-site cookies and storage access. Enable 3p cookies unconditionally during auth.
                // This applies to BOTH normal and incognito tabs — Facebook's confirmation/checkpoint flows
                // require 3p cookies regardless of tab mode.
                val activeCm = onyxWv?.cookieManager ?: CookieManager.getInstance()
                try {
                    activeCm.setAcceptCookie(true)
                    activeCm.setAcceptThirdPartyCookies(view, true)
                    activeCm.flush()
                } catch (_: Exception) {}
            } else {
                val isStreamingPage = isStreamingOrMediaPage(url) || onyxWv?.hasActiveMediaSubresource == true
                val allowThirdPartyCookies = isPopup || isWithinGrace || isStreamingPage ||
                        preferences.cookieBlockingMode == BrowserPreferences.COOKIE_BLOCK_NONE

                // Apply for both normal and incognito tabs — when outside auth/grace period,
                // incognito should restore to its default state (3p blocked)
                val activeCm = onyxWv?.cookieManager ?: CookieManager.getInstance()
                if (allowThirdPartyCookies) {
                    try {
                        activeCm.setAcceptThirdPartyCookies(view, true)
                    } catch (_: Exception) {}
                } else if (onyxWv?.isIncognito == true) {
                    // Incognito outside grace period: block 3p cookies by default
                    try {
                        activeCm.setAcceptThirdPartyCookies(view, false)
                    } catch (_: Exception) {}
                } else if (preferences.cookieBlockingMode == BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY) {
                    // Normal tab: restore third-party blocking only when completely outside any auth session or popup context
                    try {
                        activeCm.setAcceptThirdPartyCookies(view, false)
                    } catch (_: Exception) {}
                }
            }

            // If this is a pending popup window that successfully started navigating to a valid URL, display it ONLY IF IT IS NOT AN AD
            if (onyxWv?.isPopupPendingDisplay == true && url != "about:blank") {
                if (isPopupAd(url, onyxWv)) {
                    recordBlockedRequest(view)
                    val tabId = onyxWv.tabId
                    if (tabId.isNotBlank()) {
                        onyxWv.post {
                            tabActionCallback?.closeTab(tabId)
                        }
                    }
                } else {
                    val tab = tabActionCallback?.getTabById(onyxWv.tabId)
                    if (tab != null) {
                        tabActionCallback?.displayPopupTab(tab)
                    }
                }
            }

            if (!isAuthOrMeta) {
                val isAdBlockActiveForPage = preferences.isAdBlockEnabled && !preferences.isDomainWhitelisted(url)
                view?.evaluateJavascript("window.__onyxAdBlockEnabled = $isAdBlockActiveForPage;", null)
                if (isAdBlockActiveForPage) {
                    val lvl = preferences.getEffectiveBlockingLevel(url)
                    view?.evaluateJavascript(AdBlockDocumentStart.getScript(lvl), null)
                    view?.evaluateJavascript("if (window.__onyx_set_blocking_level) window.__onyx_set_blocking_level($lvl);", null)

                    // Inject scriptlets resolved from +js() cosmetic filter rules via brave-resources.json.
                    // These must run before any page scripts to intercept ad-related APIs early.
                    if (preferences.isCosmeticFilteringEnabled && !preferences.isDomainWhitelisted(url)) {
                        try {
                            val scriptletJs = AdBlockEngine.getScriptletJs(url)
                            if (scriptletJs.isNotBlank()) {
                                // Base64-encode the scriptlet to safely embed arbitrary JS code
                                // (prevents breaking due to quotes, template literals, newlines in scriptlet content)
                                val b64 = android.util.Base64.encodeToString(
                                    scriptletJs.toByteArray(Charsets.UTF_8),
                                    android.util.Base64.NO_WRAP
                                )
                                // Decode and execute using Trusted Types / DOM injection with fallback to Function() constructor
                                val wrappedScriptlet = """
                                    (function() {
                                        try {
                                            var _code = atob('$b64');
                                            var _tt = null;
                                            if (window.trustedTypes && typeof window.trustedTypes.createPolicy === 'function') {
                                                try {
                                                    if (!window.__onyx_tt_policy) {
                                                        window.__onyx_tt_policy = window.trustedTypes.createPolicy('onyx-shield-policy', {
                                                            createScript: function(s) { return s; }
                                                        });
                                                    }
                                                    _tt = window.__onyx_tt_policy;
                                                } catch(_) {}
                                            }
                                            try {
                                                var _script = document.createElement('script');
                                                _script.textContent = _tt ? _tt.createScript(_code) : _code;
                                                (document.head || document.documentElement).appendChild(_script);
                                                _script.remove();
                                            } catch(_) {
                                                var _f = new Function(_code);
                                                _f();
                                            }
                                        } catch(ex) {
                                            /* scriptlet error suppressed */
                                        }
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(wrappedScriptlet, null)
                            }
                        } catch (_: Throwable) {}
                    }

                } else {
                    val cleanupJs = """
                        (function() {
                            try {
                                window.__onyx_shields_active = false;
                                window.__onyxAdBlockEnabled = false;
                                var c1 = document.getElementById('onyx-universal-cosmetic');
                                if (c1) c1.remove();
                                var c2 = document.getElementById('onyx-adblock-cosmetic');
                                if (c2) c2.remove();
                            } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(cleanupJs, null)
                }
            }
            val isMetaAuthOnly = isMetaDomain(pageDomain) || isMetaDomain(preferences.cleanDomain(url))
            if ((preferences.isPasskeysEnabled || preferences.isAutofillEnabled) && !isMetaAuthOnly) {
                view?.evaluateJavascript(PasskeyWebAuthnBridge.getWebAuthnPolyfillJs(), null)
            }
            if (!isAuthOrMeta) {
                view?.evaluateJavascript(WebGLCompatibilityBridge.SCRIPT, null)
            }
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!url.isNullOrBlank() && !isSyntheticOrDataUrl(url)) {
            currentPageUrl = url
            onUrlChanged(url)
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val onyxWv = view as? OnyxWebView
        if (!url.isNullOrBlank()) {
            val isSyntheticError = onyxWv?.currentSyntheticState != null
            val isSyntheticData = isSyntheticOrDataUrl(url)
            val effectiveUrl = if (isSyntheticData || isSyntheticError) {
                onyxWv?.currentSyntheticState?.failingUrl ?: onyxWv?.lastFailingUrl ?: currentPageUrl
            } else {
                currentPageUrl = url
                url
            }
            if (effectiveUrl.isNotBlank() && !isSyntheticOrDataUrl(effectiveUrl)) {
                onPageFinishedCallback(effectiveUrl)
            }
            if (onyxWv?.isPopupPendingDisplay == true && !isSyntheticData && effectiveUrl != "about:blank") {
                if (isPopupAd(effectiveUrl, onyxWv)) {
                    preferences.incrementBlockedRequests()
                    val tabId = onyxWv.tabId
                    if (tabId.isNotBlank()) {
                        onyxWv.post {
                            tabActionCallback?.closeTab(tabId)
                        }
                    }
                } else {
                    val tab = tabActionCallback?.getTabById(onyxWv.tabId)
                    if (tab != null) {
                        tabActionCallback?.displayPopupTab(tab)
                    }
                }
            }

            val finishedDomain = preferences.cleanDomain(effectiveUrl)
            val isFinishedAuthOrMeta = isMetaDomain(finishedDomain) || isCaptchaOrAuthUrl(effectiveUrl, finishedDomain) ||
                    isOAuthOrLoginProvider(finishedDomain) || isOAuthOrLoginUrl(effectiveUrl) ||
                    finishedDomain.contains("login") || finishedDomain.contains("auth") || finishedDomain.contains("checkpoint")

            if (isFinishedAuthOrMeta) {
                try {
                    val activeCm = (view as? OnyxWebView)?.cookieManager ?: CookieManager.getInstance()
                    activeCm.flush()
                } catch (_: Exception) {}
            }

            // Media Monitor — always inject so OnyxMediaBridge events fire and floating pill works (never on auth/security checkpoints)
            if (!isSyntheticError && !isFinishedAuthOrMeta) {
                view?.evaluateJavascript(MediaPlaybackManager.mediaMonitorScript, null)
            }
            if (preferences.isBackgroundPlayEnabled && !isSyntheticError && !isFinishedAuthOrMeta) {
                view?.evaluateJavascript(MediaPlaybackManager.backgroundPlaybackScript, null)
            }
            if (!isSyntheticError && !isFinishedAuthOrMeta) {
                view?.evaluateJavascript(OnyxTouchBridge.TOUCH_LISTENER_JS, null)
                DsdHydrationFix.applyFix(view)
                injectUserScripts(view, effectiveUrl, com.onyx.browser.userscript.RunAt.DOCUMENT_END)
            }

            // DevTools auto-persistence across page navigations
            if (!isSyntheticError && !isFinishedAuthOrMeta && view != null && (onyxWv?.isDevToolsActive == true || preferences.isDevToolsEnabled)) {
                coroutineScope.launch {
                    val isDark = preferences.isDarkMode()
                    DevToolsManager.autoReinjectDevTools(context, view, isDark)
                }
            }

            val isIncognito = onyxWv?.isIncognito ?: false
            if (!isIncognito && !isSyntheticError && !isSyntheticData && url.startsWith("http")) {
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
            if (!isSyntheticError && !isSyntheticData && (url.startsWith("http://") || url.startsWith("https://"))) {
                onyxWv?.lastFailingUrl = null
                isMainFrameDocumentLoaded = true
                pendingMainFrameUrl = null
                onyxWv?.let {
                    it.isMainFrameDocumentLoaded = true
                    it.pendingMainFrameUrl = null
                }
            }
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

    private fun detectResourceType(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "main_frame"

        val headers = request.requestHeaders
        val acceptHeader = getHeaderCaseInsensitive(headers, "Accept")?.lowercase() ?: ""
        val fetchMode = getHeaderCaseInsensitive(headers, "Sec-Fetch-Mode")?.lowercase() ?: ""
        val fetchDest = getHeaderCaseInsensitive(headers, "Sec-Fetch-Dest")?.lowercase() ?: ""
        val reqWith = getHeaderCaseInsensitive(headers, "X-Requested-With")?.lowercase() ?: ""
        val rangeHeader = getHeaderCaseInsensitive(headers, "Range")
        val urlPath = request.url?.path?.lowercase() ?: ""
        val scheme = request.url?.scheme?.lowercase() ?: ""

        if (scheme == "ws" || scheme == "wss" || fetchMode == "websocket") return "websocket"
        if (fetchDest == "iframe" || fetchDest == "frame") return "sub_frame"
        if (fetchDest == "script") return "script"
        if (fetchDest == "image") return "image"
        if (fetchDest == "style") return "stylesheet"
        if (fetchDest == "font") return "font"
        if (fetchDest == "object" || fetchDest == "embed") return "object"
        if (fetchDest == "video" || fetchDest == "audio") return "media"
        if (urlPath.endsWith(".m3u8") || urlPath.endsWith(".ts") || urlPath.endsWith(".m2ts") ||
            urlPath.endsWith(".mpd") || urlPath.endsWith(".m4s") || urlPath.endsWith(".cmfv") ||
            urlPath.endsWith(".cmfa") || urlPath.endsWith(".fmp4") || urlPath.endsWith(".mp4") ||
            urlPath.endsWith(".m4v") || urlPath.endsWith(".webm") || urlPath.endsWith(".mkv") ||
            urlPath.endsWith(".ogv") || urlPath.endsWith(".ogg") || urlPath.endsWith(".mp3") ||
            urlPath.endsWith(".m4a") || urlPath.endsWith(".aac") || urlPath.endsWith(".opus") ||
            urlPath.endsWith(".flac") || urlPath.endsWith(".wav") || urlPath.endsWith(".flv") ||
            acceptHeader.contains("mpegurl") || acceptHeader.contains("dash+xml") ||
            rangeHeader?.startsWith("bytes=", ignoreCase = true) == true
        ) {
            return "media"
        }
        if (getHeaderCaseInsensitive(headers, "Ping-To") != null ||
            urlPath.contains("/ping") || urlPath.contains("/beacon")) {
            return "ping"
        }
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

    private fun detectResourceTypeInt(request: WebResourceRequest): Int {
        if (request.isForMainFrame) return AdBlockEngine.RESOURCE_TYPE_MAIN_FRAME

        val headers = request.requestHeaders
        val acceptHeader = getHeaderCaseInsensitive(headers, "Accept")?.lowercase() ?: ""
        val fetchMode = getHeaderCaseInsensitive(headers, "Sec-Fetch-Mode")?.lowercase() ?: ""
        val fetchDest = getHeaderCaseInsensitive(headers, "Sec-Fetch-Dest")?.lowercase() ?: ""
        val reqWith = getHeaderCaseInsensitive(headers, "X-Requested-With")?.lowercase() ?: ""
        val rangeHeader = getHeaderCaseInsensitive(headers, "Range")
        val urlPath = request.url?.path?.lowercase() ?: ""
        val scheme = request.url?.scheme?.lowercase() ?: ""

        if (scheme == "ws" || scheme == "wss" || fetchMode == "websocket") return AdBlockEngine.RESOURCE_TYPE_WEBSOCKET
        if (fetchDest == "iframe" || fetchDest == "frame") return AdBlockEngine.RESOURCE_TYPE_SUB_FRAME
        if (fetchDest == "script") return AdBlockEngine.RESOURCE_TYPE_SCRIPT
        if (fetchDest == "image") return AdBlockEngine.RESOURCE_TYPE_IMAGE
        if (fetchDest == "style") return AdBlockEngine.RESOURCE_TYPE_STYLESHEET
        if (fetchDest == "font") return AdBlockEngine.RESOURCE_TYPE_FONT
        if (fetchDest == "object" || fetchDest == "embed") return AdBlockEngine.RESOURCE_TYPE_OBJECT
        if (fetchDest == "video" || fetchDest == "audio") return AdBlockEngine.RESOURCE_TYPE_MEDIA
        if (urlPath.endsWith(".m3u8") || urlPath.endsWith(".ts") || urlPath.endsWith(".m2ts") ||
            urlPath.endsWith(".mpd") || urlPath.endsWith(".m4s") || urlPath.endsWith(".cmfv") ||
            urlPath.endsWith(".cmfa") || urlPath.endsWith(".fmp4") || urlPath.endsWith(".mp4") ||
            urlPath.endsWith(".m4v") || urlPath.endsWith(".webm") || urlPath.endsWith(".mkv") ||
            urlPath.endsWith(".ogv") || urlPath.endsWith(".ogg") || urlPath.endsWith(".mp3") ||
            urlPath.endsWith(".m4a") || urlPath.endsWith(".aac") || urlPath.endsWith(".opus") ||
            urlPath.endsWith(".flac") || urlPath.endsWith(".wav") || urlPath.endsWith(".flv") ||
            acceptHeader.contains("mpegurl") || acceptHeader.contains("dash+xml") ||
            rangeHeader?.startsWith("bytes=", ignoreCase = true) == true
        ) {
            return AdBlockEngine.RESOURCE_TYPE_MEDIA
        }
        if (getHeaderCaseInsensitive(headers, "Ping-To") != null ||
            urlPath.contains("/ping") || urlPath.contains("/beacon")) {
            return AdBlockEngine.RESOURCE_TYPE_PING
        }
        if (fetchDest == "empty" || fetchMode == "cors" || reqWith == "xmlhttprequest") return AdBlockEngine.RESOURCE_TYPE_XHR

        return when {
            acceptHeader.contains("text/css") || urlPath.endsWith(".css") -> AdBlockEngine.RESOURCE_TYPE_STYLESHEET
            acceptHeader.contains("javascript") || urlPath.endsWith(".js") -> AdBlockEngine.RESOURCE_TYPE_SCRIPT
            acceptHeader.contains("image/") || urlPath.endsWith(".png") ||
                    urlPath.endsWith(".jpg") || urlPath.endsWith(".jpeg") ||
                    urlPath.endsWith(".webp") || urlPath.endsWith(".gif") ||
                    urlPath.endsWith(".svg") || urlPath.endsWith(".ico") -> AdBlockEngine.RESOURCE_TYPE_IMAGE
            acceptHeader.contains("font/") || urlPath.endsWith(".woff") ||
                    urlPath.endsWith(".woff2") || urlPath.endsWith(".ttf") ||
                    urlPath.endsWith(".otf") -> AdBlockEngine.RESOURCE_TYPE_FONT
            acceptHeader.contains("video/") || acceptHeader.contains("audio/") ||
                    urlPath.endsWith(".mp4") || urlPath.endsWith(".mp3") || urlPath.endsWith(".webm") -> AdBlockEngine.RESOURCE_TYPE_MEDIA
            acceptHeader.contains("application/json") || acceptHeader.contains("xmlhttprequest") -> AdBlockEngine.RESOURCE_TYPE_XHR
            acceptHeader.contains("text/html") -> AdBlockEngine.RESOURCE_TYPE_SUB_FRAME
            else -> AdBlockEngine.RESOURCE_TYPE_OTHER
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

                var host = (location.hostname || '').toLowerCase();
                var href = (location.href || '').toLowerCase();
                var path = (location.pathname || '').toLowerCase();
                var ref = (document.referrer || '').toLowerCase();

                // Never run fingerprint spoofing on Meta/Facebook, CAPTCHA, Turnstile, or Auth pages.
                // Mutating Canvas, WebGL, or WebRTC APIs flags the client environment as bot/automation
                // during security challenges (e.g. Arkose Labs / FunCaptcha) and causes "Confirmation failed".
                if (/facebook\.com|meta\.com|fb\.com|fb\.me|instagram\.com|messenger\.com|arkose|funcaptcha|turnstile|recaptcha|hcaptcha|datadome|perimeterx|kasada|geetest|challenges\.cloudflare\.com/i.test(host + ' ' + href + ' ' + ref) ||
                    path.indexOf('/checkpoint/') !== -1 || path.indexOf('/login/') !== -1 || path.indexOf('/auth/') !== -1 ||
                    href.indexOf('/checkpoint/') !== -1 || href.indexOf('lsd=') !== -1 || href.indexOf('jazoest=') !== -1) {
                    return;
                }

                window.__onyxFpInjected = true;

                function makeNative(fn, name) {
                    try {
                        fn.toString = function() { return 'function ' + name + '() { [native code] }'; };
                        Object.defineProperty(fn, 'name', { value: name, configurable: true });
                    } catch (_) {}
                    return fn;
                }

                try {
                    // Standard hardware spoofing with proper native descriptors
                    try {
                        Object.defineProperty(navigator, 'hardwareConcurrency', {
                            get: makeNative(function() { return 8; }, 'get hardwareConcurrency'),
                            configurable: true
                        });
                    } catch (_) {}

                    if ('deviceMemory' in navigator) {
                        try {
                            Object.defineProperty(navigator, 'deviceMemory', {
                                get: makeNative(function() { return 8; }, 'get deviceMemory'),
                                configurable: true
                            });
                        } catch (_) {}
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        view?.post {
            try { view?.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    @Volatile
    private var cachedErrorPageTemplate: String? = null

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?
    ) {
        // Phase 1: Disambiguate sub-resource / adblock drops. Only main-frame failures show error page.
        if (request?.isForMainFrame != true) {
            return
        }

        val url = request.url.toString()
        if (url.startsWith("file:///android_asset/", ignoreCase = true) ||
            url.startsWith("file:///android_res/", ignoreCase = true) ||
            url.startsWith("data:", ignoreCase = true)) return

        val fallbackUrl = url.replaceFirst("https://", "http://")
        val normalizedFallback = fallbackUrl.trimEnd('/')
        // In STRICT mode, do NOT fall back to HTTP — block the page
        if (preferences.httpsUpgradeMode != BrowserPreferences.HTTPS_MODE_STRICT &&
            (upgradedUrls.contains(fallbackUrl) || upgradedUrls.contains(normalizedFallback))) {
            view?.post { view.loadUrl(fallbackUrl) }
            return
        }

        // Immediately stop loading to prevent Chromium's default error page from flashing
        try { view?.stopLoading() } catch (_: Throwable) {}

        val errorDesc = error?.description?.toString() ?: ""
        val errCode = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN
        val onyxError = WebErrorHandler.resolveNetworkError(url, errCode, errorDesc, isNetworkConnected())

        loadCustomErrorPage(view, onyxError)
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        // Do NOT override server-returned HTTP error pages (e.g. 403 Forbidden, 404 Not Found, 500).
        // Websites and servers provide their own HTML responses (e.g. biology-school.com 403 Forbidden).
        // Only actual network-level failures (onReceivedError) and SSL errors (onReceivedSslError) show synthetic error pages.
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            val url = failingUrl ?: view?.url ?: return
            if (url.startsWith("file:///android_asset/", ignoreCase = true) ||
                url.startsWith("file:///android_res/", ignoreCase = true) ||
                url.startsWith("data:", ignoreCase = true)) return
            try { view?.stopLoading() } catch (_: Throwable) {}
            val onyxError = WebErrorHandler.resolveNetworkError(url, errorCode, description, isNetworkConnected())
            loadCustomErrorPage(view, onyxError)
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: android.webkit.SslErrorHandler?,
        error: android.net.http.SslError?
    ) {
        val failingUrl = error?.url?.trim()
        if (failingUrl.isNullOrBlank()) {
            handler?.cancel()
            return
        }

        // Subresource SSL Protection:
        // Do NOT abort the main page navigation or display a full-page SSL error screen
        // if this SSL error originated from a subresource (ad probe, tracking script, image, iframe, fetch).
        // Only main-frame document failures should show the custom SSL error page.
        if (!isMainFrameSslError(view, failingUrl)) {
            handler?.cancel()
            return
        }

        val host = try { Uri.parse(failingUrl).host } catch (_: Exception) { null }

        // Phase 5: Check session-scoped SSL bypass
        if (!host.isNullOrBlank() && (view as? OnyxWebView)?.sessionSslBypasses?.contains(host) == true) {
            handler?.proceed()
            return
        }

        // Store handler in OnyxWebView so OnyxErrorBridge.proceedSsl() can bypass if user clicks it
        (view as? OnyxWebView)?.pendingSslHandler = handler

        try { view?.stopLoading() } catch (_: Throwable) {}
        val isHsts = preferences.httpsUpgradeMode == BrowserPreferences.HTTPS_MODE_STRICT
        val onyxError = WebErrorHandler.resolveSslError(failingUrl, error, isHstsEnforced = isHsts)
        loadCustomErrorPage(view, onyxError)
    }

    private fun isMainFrameSslError(view: WebView?, failingUrl: String): Boolean {
        val onyxWv = view as? OnyxWebView
        val isDocLoaded = isMainFrameDocumentLoaded || (onyxWv?.isMainFrameDocumentLoaded == true)

        // If the main document has already completed loading, any incoming SSL error
        // is guaranteed to be for a subresource (e.g. ad probe, test suite fetch, tracking pixel, iframe).
        if (isDocLoaded) {
            return false
        }

        val failingUri = try { Uri.parse(failingUrl) } catch (_: Throwable) { null } ?: return false
        val failingHost = failingUri.host?.lowercase() ?: return false

        // Gather all known candidate main-frame URLs
        val candidateMainUrls = listOfNotNull(
            onyxWv?.pendingMainFrameUrl,
            pendingMainFrameUrl,
            currentPageUrl.takeIf { it.isNotBlank() },
            view?.url?.takeIf { !isSyntheticOrDataUrl(it) }
        )

        // If we have no candidate URLs at all (e.g. initial navigation in blank tab),
        // then this error is indeed for the first main-frame request.
        if (candidateMainUrls.isEmpty()) {
            return true
        }

        for (candidate in candidateMainUrls) {
            val candidateUri = try { Uri.parse(candidate) } catch (_: Throwable) { null } ?: continue
            val candidateHost = candidateUri.host?.lowercase() ?: continue

            // If the host matches, verify that it's actually the main document path
            // (not an asset like /ad.js, /pixel.png, etc.)
            if (failingHost == candidateHost) {
                val failingPath = (failingUri.path ?: "").trimEnd('/')
                val candidatePath = (candidateUri.path ?: "").trimEnd('/')
                if (failingPath.equals(candidatePath, ignoreCase = true)) {
                    return true
                }
            }
        }

        // Host does not match any candidate main-frame navigation URL -> subresource
        return false
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = cm?.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm?.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun loadCustomErrorPage(view: WebView?, error: SyntheticNavigationState) {
        val onyxWv = view as? OnyxWebView
        onyxWv?.isLoadingSyntheticPage = true
        onyxWv?.lastFailingUrl = error.failingUrl
        onyxWv?.currentSyntheticState = error
        if (error.failingUrl.isNotBlank()) {
            currentPageUrl = error.failingUrl
        }
        val renderAction = Runnable {
            try {
                var template = cachedErrorPageTemplate
                if (template == null) {
                    template = context.assets.open("error_page.html").bufferedReader().use { it.readText() }
                    cachedErrorPageTemplate = template
                }
                val errorJsonB64 = error.toBase64Json()
                val accentHex = try { BrowserPreferences.getInstance(context).accentColorHex } catch (_: Exception) { "#DC4B64" }
                val populatedHtml = template.replace("{{ERROR_JSON_B64}}", errorJsonB64)
                    .replace("#DC4B64", accentHex)
                val baseUrl = "https://onyx.browser/"
                view?.loadDataWithBaseURL(
                    baseUrl,
                    populatedHtml,
                    "text/html",
                    "UTF-8",
                    if (error.failingUrl.isNotBlank()) error.failingUrl else null
                )
            } catch (_: Throwable) {
                val encodedUrl = Uri.encode(error.failingUrl)
                val encodedErr = Uri.encode(error.errorCodeString)
                val encodedDesc = Uri.encode(error.description)
                view?.post { view.loadUrl("${LocalFileLoader.ASSET_BASE_URL}error_page.html?url=$encodedUrl&error=$encodedErr&desc=$encodedDesc") }
            }
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            renderAction.run()
        } else {
            view?.post(renderAction)
        }
    }

    private fun injectUserScripts(view: WebView?, url: String, runAt: com.onyx.browser.userscript.RunAt) {
        val onyxWv = view as? OnyxWebView ?: return
        if (isSyntheticOrDataUrl(url) || onyxWv.isLoadingSyntheticPage) return

        val pageDomain = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) { "" }

        if (preferences.isGlobalScriptBlockingEnabled ||
            (pageDomain.isNotEmpty() && preferences.isScriptBlockingEnabledForDomain(pageDomain)) ||
            !preferences.isJavaScriptEnabledGlobal ||
            (pageDomain.isNotEmpty() && !preferences.isJavaScriptEnabledForDomain(pageDomain))
        ) {
            return
        }

        val manager = onyxWv.userScriptManager
        val matching = manager.getMatchingScripts(url, runAt)
        if (matching.isEmpty()) return

        val bridge = onyxWv.userScriptBridge
        matching.forEach { parsed ->
            val token = java.util.UUID.randomUUID().toString()
            if (parsed.metadata.requiresNativeBridge) {
                bridge.registerToken(token, parsed.script.id)
            }
            val js = manager.buildInjectionJs(parsed, token)
            val b64 = android.util.Base64.encodeToString(
                js.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val scriptId = parsed.script.id
            val injector = """
                (function(){
                    try {
                        var reg = window.__onyxInjectedScripts || (window.__onyxInjectedScripts = {});
                        if (reg[$scriptId]) return;
                        reg[$scriptId] = true;
                        var s = document.createElement('script');
                        s.textContent = decodeURIComponent(escape(atob('$b64')));
                        (document.head || document.documentElement).appendChild(s);
                        s.parentNode && s.parentNode.removeChild(s);
                    } catch(e) { console.error('Onyx userscript inject error', e); }
                })();
            """.trimIndent()
            onyxWv.evaluateJavascript(injector, null)
        }
    }

    private fun findFragmentActivity(context: android.content.Context?): androidx.fragment.app.FragmentActivity? {
        var ctx: android.content.Context? = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is androidx.fragment.app.FragmentActivity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
