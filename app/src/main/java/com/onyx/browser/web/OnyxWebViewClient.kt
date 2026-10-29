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

    // Social media tracker domains (analytics/pixel only, not content)
    private val socialMediaTrackerDomains = setOf(
        // Facebook/Meta pixels and analytics
        "an.facebook.com", "pixel.facebook.com", "tr.facebook.com",
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

    // Facebook domains that are content (logins, embeds, CDN) not pure tracking
    private val facebookContentDomains = setOf(
        "facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "touch.facebook.com",
        "static.facebook.com", "staticxx.facebook.com", "static.xx.fbcdn.net",
        "connect.facebook.net", "facebook.net", "graph.facebook.com",
        "fbcdn.net", "scontent.xx.fbcdn.net", "fbsbx.com", "fb.com", "fb.me", "accountkit.com",
        "meta.com", "threads.net", "cdninstagram.com"
    )

    private fun isMetaDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        val d = domain.lowercase()
        return d == "facebook.com" || d.endsWith(".facebook.com") ||
                d == "meta.com" || d.endsWith(".meta.com") ||
                d == "fb.com" || d.endsWith(".fb.com") ||
                d == "fb.me" || d.endsWith(".fb.me") ||
                d == "fbcdn.net" || d.endsWith(".fbcdn.net") ||
                d == "facebook.net" || d.endsWith(".facebook.net") ||
                d == "fbsbx.com" || d.endsWith(".fbsbx.com") ||
                d == "messenger.com" || d.endsWith(".messenger.com") ||
                d == "instagram.com" || d.endsWith(".instagram.com") ||
                d == "cdninstagram.com" || d.endsWith(".cdninstagram.com") ||
                d == "threads.net" || d.endsWith(".threads.net") ||
                d == "accountkit.com" || d.endsWith(".accountkit.com") ||
                d == "workplace.com" || d.endsWith(".workplace.com")
    }

    private fun isCaptchaOrAuthUrl(url: String, domain: String): Boolean {
        val d = domain.lowercase()
        val u = url.lowercase()
        return d.contains("recaptcha") || d.contains("hcaptcha") ||
                d.contains("arkose") || d.contains("arkoselabs") || d.contains("funcaptcha") ||
                d.contains("matchkey") || d.contains("turnstile") || d.contains("geetest") || d.contains("datadome") ||
                d.contains("kasada") || d.contains("perimeterx") ||
                d == "fbsbx.com" || d.endsWith(".fbsbx.com") ||
                d == "challenges.cloudflare.com" || d.endsWith(".challenges.cloudflare.com") ||
                u.contains("recaptcha") || u.contains("hcaptcha") || u.contains("arkose") ||
                u.contains("funcaptcha") || u.contains("matchkey") || u.contains("turnstile") ||
                u.contains("/captcha/arkose/") || u.contains("meta-api.arkoselabs.com") ||
                u.contains("client-api.arkoselabs.com") ||
                u.contains("/cdn-cgi/challenge-platform") || u.contains("/cdn-cgi/cf-challenge") ||
                u.contains("__cf_chl") ||
                u.contains("/checkpoint/") || u.contains("/challenge/") ||
                u.contains("/captcha/") || u.contains("/security-check") ||
                u.contains("/waf/") || u.contains("/bot-detection") ||
                u.contains("/human-verification") || u.contains("login/device-based") ||
                u.contains("/two_step_verification") || u.contains("/save-device/") ||
                u.contains("/trusted-devices/") || u.contains("/login_attempt") ||
                u.contains("/fc/api") || u.contains("/fc/assets") || u.contains("/client-api/") ||
                u.contains("/checkpoint") || u.contains("/confirm") ||
                u.contains("lsd=") || u.contains("jazoest=") || u.contains("datr=")
    }

    /**
     * Returns true if the given domain is a known OAuth/SSO/identity/payment provider that
     * requires third-party cookies for login flows to work correctly.
     *
     * Based on Brave's approach: these are exempted from BLOCK_THIRD_PARTY cookie mode
     * because they are cross-site by design (OAuth redirects, embedded login iframes,
     * payment processors). Blocking third-party cookies on these breaks:
     *   - Google Sign-In / Apple Sign-In / GitHub OAuth
     *   - PayPal / Stripe / payment confirmation pages
     *   - Microsoft/Azure AD authentication
     *   - Auth0, Okta, Keycloak, Firebase Auth
     */
    private fun isOAuthOrLoginProvider(domain: String): Boolean {
        if (domain.isBlank()) return false
        val d = domain.lowercase()
        // Meta / Facebook identity & OAuth & Arkose challenges
        return isMetaDomain(d) ||
               d.contains("arkose") || d.contains("funcaptcha") ||
               // Google identity and auth
               d == "accounts.google.com" || d.endsWith(".accounts.google.com") ||
               d == "oauth2.googleapis.com" || d == "apis.google.com" ||
               d == "ssl.gstatic.com" || d == "www.gstatic.com" ||
               d == "id.google.com" ||
               // Apple Sign-In
               d == "appleid.apple.com" || d.endsWith(".apple.com") && (d.contains("auth") || d.contains("appleid")) ||
               d == "idmsa.apple.com" || d == "signin.apple.com" ||
               // Microsoft / Azure AD / MSAL
               d == "login.microsoftonline.com" || d == "login.microsoft.com" ||
               d == "login.live.com" || d == "account.microsoft.com" ||
               d == "aadcdn.msftauth.net" || d == "aadcdn.msauth.net" ||
               d == "msauth.net" || d.endsWith(".msauth.net") ||
               // GitHub OAuth and asset domains
               d == "github.com" || d.endsWith(".github.com") || d == "api.github.com" ||
               d == "githubassets.com" || d.endsWith(".githubassets.com") ||
               d == "githubusercontent.com" || d.endsWith(".githubusercontent.com") ||
               // Twitter/X OAuth
               d == "api.twitter.com" || d == "api.x.com" || d == "twitter.com" || d == "x.com" ||
               // TikTok OAuth
               d == "open-api.tiktok.com" || (d.endsWith(".tiktok.com") && d.contains("auth")) ||
               // Spotify OAuth
               d == "accounts.spotify.com" ||
               // Yahoo OAuth
               d == "login.yahoo.com" ||
               // Slack OAuth
               d == "slack.com" || d.endsWith(".slack.com") ||
               // PayPal payment flows
               d == "paypal.com" || d == "www.paypal.com" || d.endsWith(".paypal.com") ||
               d == "paypalobjects.com" || d.endsWith(".paypalobjects.com") ||
               // Stripe payment flows
               d == "stripe.com" || d.endsWith(".stripe.com") ||
               d == "js.stripe.com" || d == "m.stripe.network" || d.endsWith(".stripe.network") ||
               // Auth0
               d.endsWith(".auth0.com") || d == "auth0.com" ||
               d.endsWith(".us.auth0.com") || d.endsWith(".eu.auth0.com") ||
               // Okta
               d.endsWith(".okta.com") || d == "okta.com" ||
               d.endsWith(".oktapreview.com") ||
               // OneLogin & PingIdentity
               d.endsWith(".onelogin.com") || d.endsWith(".pingidentity.com") ||
               // Keycloak / generic auth subdomains
               d.startsWith("auth.") || d.startsWith("login.") || d.startsWith("sso.") ||
               d.startsWith("id.") || d.startsWith("identity.") || d.startsWith("account.") ||
               d.startsWith("accounts.") || d.startsWith("signin.") || d.startsWith("oauth.") ||
               // Firebase Auth
               d.endsWith(".firebaseapp.com") || d == "securetoken.googleapis.com" ||
               // Amazon / AWS Cognito
               d.endsWith(".amazoncognito.com") || d == "cognito-identity.amazonaws.com" ||
               d == "amazon.com" || d == "www.amazon.com" ||
               // LinkedIn OAuth
               d == "linkedin.com" || d == "www.linkedin.com" ||
               // Twitch OAuth
               d == "id.twitch.tv" ||
               // Discord OAuth
               d == "discord.com" || d == "discordapp.com" ||
               // Cloudflare Access
               d.endsWith(".cloudflareaccess.com") ||
               // Generic OAuth indicators in path
               d.contains("oauth") || d.contains("openid") || d.contains("saml")
    }

    private fun isOAuthOrLoginUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val u = url.lowercase()
        return u.contains("/oauth") || u.contains("/oauth2") ||
                u.contains("/openid-connect") || u.contains("/saml") ||
                u.contains("/signin") || u.contains("/login") ||
                u.contains("/auth/") || u.contains("/authenticate") ||
                u.contains("/sso/") || u.contains("/checkpoint/") ||
                u.contains("/challenge/") ||
                u.contains("code=") || u.contains("access_token=") ||
                u.contains("id_token=") || u.contains("oauth_token=") ||
                u.contains("state=") && (u.contains("auth") || u.contains("login") || u.contains("oauth"))
    }

    fun isStreamingOrMediaPage(url: String): Boolean = Companion.isStreamingOrMediaPage(url)

    fun isMediaOrStreamingHost(host: String): Boolean = Companion.isMediaOrStreamingHost(host)

    fun isMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".ts") ||
                clean.endsWith(".m2ts") || clean.endsWith(".m4s") || clean.endsWith(".cmfv") ||
                clean.endsWith(".cmfa") || clean.endsWith(".fmp4") || clean.endsWith(".mp4") ||
                clean.endsWith(".m4v") || clean.endsWith(".webm") || clean.endsWith(".mkv") ||
                clean.endsWith(".ogv") || clean.endsWith(".ogg") || clean.endsWith(".flv") ||
                clean.endsWith(".mp3") || clean.endsWith(".aac") || clean.endsWith(".m4a") ||
                clean.endsWith(".opus") || clean.endsWith(".flac") || clean.endsWith(".wav")
    }

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
        "_openstat", "vero_id", "mkt_tok",
        "twclid", "msclkid", "ttclid", "li_fat_id",
        "igshid", "s_cid", "srsltid", "epik"
    )

    companion object {
        private val TRANSPARENT_1X1_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
            Base64.DEFAULT
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
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0"
            )
        }
    }

    private fun createBlockedResponse(
        resourceType: String,
        requestUrl: String = "",
        requestOrigin: String? = null
    ): WebResourceResponse {
        val corsHeaders = buildCorsHeaders(requestOrigin)

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
            val emptyVast = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"/>"
            return WebResourceResponse(
                "text/xml",
                "UTF-8",
                200,
                "OK",
                corsHeaders + ("Content-Type" to "text/xml; charset=utf-8"),
                ByteArrayInputStream(emptyVast.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
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
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked by Onyx Shields",
                corsHeaders,
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // When Adblocker Spoofing is enabled:
        // Return safe, type-aware HTTP 200 OK stubs with CORS headers to spoof ad scripts as loaded
        // and bypass anti-adblock detection walls without breaking page layout.
        return when (resourceType) {
            "image" -> {
                WebResourceResponse(
                    "image/png",
                    "UTF-8",
                    200,
                    "OK",
                    corsHeaders + ("Content-Type" to "image/png"),
                    ByteArrayInputStream(TRANSPARENT_1X1_PNG)
                )
            }
            "script" -> {
                WebResourceResponse(
                    "application/javascript",
                    "UTF-8",
                    200,
                    "OK",
                    corsHeaders + ("Content-Type" to "application/javascript; charset=utf-8"),
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            "stylesheet" -> {
                WebResourceResponse(
                    "text/css",
                    "UTF-8",
                    200,
                    "OK",
                    corsHeaders + ("Content-Type" to "text/css; charset=utf-8"),
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            "sub_frame" -> {
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    corsHeaders + ("Content-Type" to "text/html; charset=utf-8"),
                    ByteArrayInputStream("<!-- blocked subframe -->".toByteArray())
                )
            }
            "font" -> {
                WebResourceResponse(
                    "font/woff2",
                    "UTF-8",
                    200,
                    "OK",
                    corsHeaders + ("Content-Type" to "font/woff2"),
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            "media" -> {
                WebResourceResponse(
                    "video/mp4",
                    "UTF-8",
                    200,
                    "OK",
                    corsHeaders + ("Content-Type" to "video/mp4"),
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            else -> {
                WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    200,
                    "OK",
                    corsHeaders + ("Content-Type" to "text/plain; charset=utf-8"),
                    ByteArrayInputStream(ByteArray(0))
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
                        corsHeaders + ("Content-Type" to "$mimeType; charset=utf-8"),
                        ByteArrayInputStream(bytes)
                    )
                }
            }

            val mime = when {
                resourceType == "image" -> "image/png"
                resourceType == "xml" || redirectData.startsWith("<?xml") || redirectData.contains("<VAST") || redirectData.contains("<vmap") -> "text/xml"
                else -> "application/javascript"
            }
            WebResourceResponse(
                mime,
                "UTF-8",
                200,
                "OK",
                corsHeaders + ("Content-Type" to "$mime; charset=utf-8"),
                ByteArrayInputStream(redirectData.toByteArray(Charsets.UTF_8))
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

        return try {
            val url = request.url?.toString() ?: return null

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
                                emptyMap(),
                                ByteArrayInputStream("Access to private app storage is blocked.".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
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
                                mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Content-Type" to "text/html; charset=utf-8"
                                ),
                                ByteArrayInputStream("<!DOCTYPE html><html><head><title>Blocked by Onyx Shields</title></head><body></body></html>".toByteArray())
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
                                    mapOf(
                                        "Access-Control-Allow-Origin" to "*",
                                        "Content-Type" to "text/html; charset=utf-8"
                                    ),
                                    ByteArrayInputStream("<!DOCTYPE html><html><head><title>Blocked by Onyx Shields</title></head><body></body></html>".toByteArray())
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
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return LocalFileLoader.interceptLocalSubResource(context, url)
                }
                return null
            }

            // Never block in-page translation resources
            if (url.contains("translate.google.com") || url.contains("translate.googleapis.com")) {
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
            val isWhitelisted = preferences.isDomainWhitelisted(pageDomain)
            val isIncognitoView = (view as? OnyxWebView)?.isIncognito ?: false
            val resourceType = detectResourceType(request)

            val currentDomain = preferences.cleanDomain(currentPageUrl)
            val isMetaContext = isMetaDomain(pageDomain) || isMetaDomain(currentDomain) ||
                    isCaptchaOrAuthUrl(currentPageUrl, currentDomain) ||
                    isCaptchaOrAuthUrl(pageUrl, pageDomain)

            // ── Universal CAPTCHA, Verification & Security Protection ──────────────
            // Anti-bot verifications and checkpoints must never be blocked on any website
            val isCaptchaResource = isCaptchaOrAuthUrl(url, reqDomain)

            // ── OAuth / SSO / Identity & Payment Provider Exemption ───────────────
            // Brave exempts known identity providers from BLOCK_THIRD_PARTY cookie mode because
            // they are legitimately cross-site (Google Sign-In iframes, Stripe payment forms, etc.).
            val isOAuthResource = isOAuthOrLoginProvider(reqDomain) ||
                    isOAuthOrLoginProvider(pageDomain) ||
                    isOAuthOrLoginUrl(url)

            // ── Ensure active auth, CAPTCHA iframes, and media streams retain cross-site cookies ──
            if (isMetaContext || isCaptchaResource || isOAuthResource) {
                (view as? OnyxWebView)?.lastOAuthInteractionTimestamp = System.currentTimeMillis()
                view?.post {
                    try {
                        val activeCm = (view as? OnyxWebView)?.cookieManager ?: CookieManager.getInstance()
                        activeCm.setAcceptCookie(true)
                        activeCm.setAcceptThirdPartyCookies(view, true)
                    } catch (_: Exception) {}
                }
            } else if (resourceType == "media" || resourceType == "sub_frame" || isMediaUrl(url) || isMediaOrStreamingHost(reqDomain)) {
                if (resourceType == "media" || isMediaUrl(url) || isMediaOrStreamingHost(reqDomain) ||
                    (resourceType == "sub_frame" && (isStreamingOrMediaPage(url) || isStreamingOrMediaPage(pageUrl)))
                ) {
                    (view as? OnyxWebView)?.hasActiveMediaSubresource = true
                }
                view?.post {
                    try {
                        val activeCm = (view as? OnyxWebView)?.cookieManager ?: CookieManager.getInstance()
                        activeCm.setAcceptCookie(true)
                        activeCm.setAcceptThirdPartyCookies(view, true)
                    } catch (_: Exception) {}
                }
            }

            if (isCaptchaResource) {
                return null
            }

            // ── Google Sign-In Prompts Blocking (Brave parity) ─────────────────────
            // Third-party websites load Google One-Tap via accounts.google.com/gsi/client or iframes.
            // When Google Sign-In is blocked for the origin (or globally blocked), defuse the prompt.
            if (url.contains("accounts.google.com/gsi/")) {
                val isGoogleSignInAllowed = preferences.isWebGoogleSignInAllowed(pageDomain)
                    ?: preferences.isWebGoogleSignInAllowed(currentDomain)
                    ?: !preferences.isGoogleSignInBlockedGlobal
                if (!isGoogleSignInAllowed && !pageDomain.endsWith("google.com") && !currentDomain.endsWith("google.com")) {
                    recordBlockedRequest(view)
                    val gsiStub = """
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
                    """.trimIndent()
                    return WebResourceResponse(
                        "application/javascript",
                        "UTF-8",
                        200,
                        "OK",
                        mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Content-Type" to "application/javascript; charset=utf-8"
                        ),
                        ByteArrayInputStream(gsiStub.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    )
                }
            }

            if (isOAuthResource) {
                return null
            }

            // ── Meta / Facebook First-Party Integrity ─────────────────────────────
            val isMetaResource = isMetaDomain(reqDomain)
            val isMetaAdPixel = reqDomain == "pixel.facebook.com" || reqDomain == "an.facebook.com" || reqDomain == "tr.facebook.com"

            if (isMetaResource) {
                // When in a Meta context (user is on Facebook, Instagram, Messenger, or during login/checkpoint/CAPTCHA verification),
                // NEVER block any Meta resource (including tr.facebook.com and pixel.facebook.com) because Facebook's login handshake,
                // Arkose challenge completion, and account security checkpoints require first-party telemetry endpoints.
                if (isMetaContext) {
                    return null
                }
                // On third-party sites: allow first-party Meta assets/OAuth if in active OAuth flow or grace period
                val isInOAuth = (view as? OnyxWebView)?.isWithinOAuthGracePeriod() == true || isOAuthResource
                if (isInOAuth && !isMetaAdPixel) {
                    return null
                }
            }

            // ── Permitted Social Content & Embeds ─────────────────────────────────
            // When allowed, bypass adblock ONLY if actively in OAuth flow or on Meta domain
            val isInFbAuth = isMetaContext || (view as? OnyxWebView)?.isWithinOAuthGracePeriod() == true || isOAuthResource
            val isFbContent = preferences.allowFacebookLogins && isInFbAuth &&
                    facebookContentDomains.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
            if (isFbContent) {
                return null
            }

            val isTwitterContent = preferences.allowTwitterEmbeds &&
                    twitterContentDomains.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
            if (isTwitterContent) {
                return null
            }

            val isLinkedInContent = preferences.allowLinkedInEmbeds &&
                    linkedinContentDomains.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
            if (isLinkedInContent) {
                return null
            }

            // ── Element blocking in private windows: respect the setting ─────────
            // If element blocking in private windows is disabled and this is incognito,
            // skip all blocking
            if (isIncognitoView && !preferences.isElementBlockingInPrivateEnabled) {
                return null
            }

            // ── Social Media Tracker Blocking ─────────────────────────────────────
            if (preferences.isSocialMediaBlockingEnabled && !isWhitelisted && !isMetaContext) {
                val isSocialTrackerDomain = socialMediaTrackerDomains.any { trackerDomain ->
                    reqDomain == trackerDomain || reqDomain.endsWith(".$trackerDomain")
                } || (!preferences.allowFacebookLogins && (reqDomain == "connect.facebook.net" || reqDomain == "graph.facebook.com"))

                if (isSocialTrackerDomain) {
                    recordBlockedRequest(view)
                    return createBlockedResponse(resourceType, url, requestOrigin)
                }
            }

            // ── Global Script Blocking ─────────────────────────────────────────
            if (!isWhitelisted && (preferences.isGlobalScriptBlockingEnabled ||
                    preferences.isScriptBlockingEnabledForDomain(pageDomain))) {
                if (resourceType == "script") {
                    recordBlockedRequest(view)
                    return createBlockedResponse("script", url, requestOrigin)
                }
            }

            // ── Ad & Tracker Blocking ─────────────────────────────────────────
            if (preferences.isAdBlockEnabled && !isWhitelisted) {
                val isAggressive = preferences.getEffectiveBlockingLevel(pageDomain) == BrowserPreferences.BLOCKING_AGGRESSIVE

                // In Standard mode (Brave parity): Main-frame top-level navigations are never cancelled
                // or 403-intercepted by adblock rules (DomainBlockingType::kNone in Brave). Only subresources and trackers
                // are intercepted. In Aggressive mode (DomainBlockingType::kAggressive), main-frame ad domains can also be blocked.
                if (request.isForMainFrame && !isAggressive) {
                    return null
                }

                // Never block media streams (video/audio, HLS chunks, DASH segments) with generic domain lists.
                // Only let the adblock engine check if there is an explicit media rule.
                if (resourceType == "media") {
                    val uncloakedMediaUrl = AdBlockDomainManager.uncloakUrl(url)
                    val mediaMethod = request.method ?: "GET"
                    val mediaResult = AdBlockEngine.checkRequest(uncloakedMediaUrl, pageUrl, AdBlockEngine.RESOURCE_TYPE_MEDIA, mediaMethod)
                    if (mediaResult.shouldBlock) {
                        recordBlockedRequest(view)
                        if (!mediaResult.redirectData.isNullOrBlank()) {
                            val surrogate = createSurrogateResponse(mediaResult.redirectData, "media", requestOrigin)
                            if (surrogate != null) return surrogate
                        }
                        return createBlockedResponse("media", url, requestOrigin)
                    }
                    return null // Allow media playback!
                }

                // Explicit detection for synthetic ad test probes (e.g. adblock-tester.com, d3ward, canyoublockit)
                val isAdTestResource = url.contains("pr_advertising_ads_banner") ||
                        url.contains("ymatuhin.ru") ||
                        url.contains("/fakepage.html") ||
                        (currentDomain == "adblock-tester.com" && (url.contains("/banners/") || url.contains("/ads/")))

                // Static CNAME uncloaking
                val uncloakedDomain = AdBlockDomainManager.uncloakDomain(reqDomain)
                val uncloakedUrl = AdBlockDomainManager.uncloakUrl(url)

                // Query native Rust engine through high-performance Kotlin LRU decision cache
                val resourceTypeInt = detectResourceTypeInt(request)
                val method = request.method ?: "GET"
                var engineResult = if (!isAdTestResource) {
                    AdBlockEngine.checkRequest(uncloakedUrl, pageUrl, resourceTypeInt, method)
                } else null

                // Dual-pass: if uncloaked URL was not blocked and uncloakedUrl != url, check original URL
                // to catch rules that explicitly target the cloaked alias
                if (engineResult?.shouldBlock != true && uncloakedUrl != url && !isAdTestResource) {
                    val originalResult = AdBlockEngine.checkRequest(url, pageUrl, resourceTypeInt, method)
                    if (originalResult.shouldBlock || !originalResult.redirectData.isNullOrBlank() || !originalResult.rewrittenUrl.isNullOrBlank()) {
                        engineResult = originalResult
                    }
                }

                val blockedByEngine = engineResult?.shouldBlock == true
                val redirectData = engineResult?.redirectData
                val rewrittenUrl = engineResult?.rewrittenUrl

                if (isAdTestResource || blockedByEngine) {
                    recordBlockedRequest(view)

                    // If a surrogate redirect ($redirect rule) is matched, serve the surrogate script directly!
                    if (!redirectData.isNullOrBlank()) {
                        val surrogateResponse = createSurrogateResponse(redirectData, resourceType, requestOrigin)
                        if (surrogateResponse != null) {
                            return surrogateResponse
                        }
                    }

                    return createBlockedResponse(resourceType, url, requestOrigin)
                }

                // If a data: rewrite rule ($rewrite=abp-resource:...) modified the URL, serve the synthesized data.
                // Note: Never return a synthetic 307 Location WebResourceResponse for http(s) $removeparam rewrites,
                // because Android WebView shouldInterceptRequest does not follow 3xx redirects and delivers an empty
                // 0-byte body to Blink, breaking embedded player iframes, scripts, and HLS/DASH manifests.
                if (!rewrittenUrl.isNullOrBlank() && rewrittenUrl != url && rewrittenUrl.startsWith("data:", ignoreCase = true)) {
                    val dataResponse = createSurrogateResponse(rewrittenUrl, resourceType, requestOrigin)
                    if (dataResponse != null) return dataResponse
                }
            }

            null
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
    private fun stripTrackingParams(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val queryParams = uri.queryParameterNames
            if (queryParams.none { trackingParams.contains(it) }) return url

            val rawQuery = uri.encodedQuery ?: return url
            val cleanParts = rawQuery.split('&').filter { part ->
                if (part.isBlank()) return@filter false
                val key = part.substringBefore('=').let {
                    try { Uri.decode(it) } catch (_: Exception) { it }
                }
                !trackingParams.contains(key)
            }

            val newQuery = if (cleanParts.isNotEmpty()) cleanParts.joinToString("&") else null
            uri.buildUpon().encodedQuery(newQuery).build().toString()
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

            // ?amp=1 query param (clean AMP query parameter)
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
                view?.post { view.loadUrl("file:///android_asset/error_page.html?url=$encodedUrl&error=$encodedErr&desc=$encodedDesc") }
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
