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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.onyx.browser.MainActivity
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.preferences.BrowserPreferences
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

    // Set of URLs where user explicitly chose "Stay in Onyx", bypassing external app interception
    private val bypassAppInterceptUrls = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

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
                d == "challenges.cloudflare.com" || d.endsWith(".challenges.cloudflare.com") ||
                u.contains("recaptcha") || u.contains("hcaptcha") || u.contains("arkose") ||
                u.contains("funcaptcha") || u.contains("matchkey") || u.contains("turnstile") ||
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
               // GitHub OAuth
               d == "github.com" || d == "api.github.com" ||
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
    }

    private fun createBlockedResponse(resourceType: String): WebResourceResponse {
        val corsHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma" to "no-cache"
        )

        // When Adblocker Spoofing is disabled (default):
        // Return HTTP 403 Forbidden with permissive CORS headers.
        // In-page fetch() and XMLHttpRequest calls are intercepted by the stealth client proxies
        // in AdBlockDocumentStart (throwing TypeError: net::ERR_BLOCKED_BY_CLIENT / onerror),
        // while DOM subresources (<script>, <img>, <iframe>) receive HTTP 403 and trigger onerror.
        if (!preferences.isAntiAdblockDetectionEnabled) {
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

    private fun createSurrogateResponse(redirectData: String, resourceType: String): WebResourceResponse? {
        return try {
            val corsHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0"
            )

            if (redirectData.startsWith("data:", ignoreCase = true)) {
                val commaIndex = redirectData.indexOf(',')
                if (commaIndex != -1) {
                    val meta = redirectData.substring(5, commaIndex)
                    val body = redirectData.substring(commaIndex + 1)
                    val isBase64 = meta.contains(";base64", ignoreCase = true)
                    val mimeType = meta.substringBefore(';').ifBlank {
                        if (resourceType == "image") "image/png" else "application/javascript"
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
                        corsHeaders + ("Content-Type" to mimeType),
                        ByteArrayInputStream(bytes)
                    )
                }
            }

            val mime = if (resourceType == "image") "image/png" else "application/javascript"
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
                        val isAggressive = preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE
                        val onyxWv = view as? OnyxWebView
                        val isPopupTab = onyxWv != null && (onyxWv.isPopupPendingDisplay ||
                                (tabActionCallback?.getTabById(onyxWv.tabId)?.parentId != null))

                        // In Standard mode (Brave parity): Main-frame top-level navigations are never cancelled
                        // or 403-intercepted by adblock rules (DomainBlockingType::kNone in Brave), UNLESS it is a child popup tab.
                        // In Aggressive mode (DomainBlockingType::kAggressive), main-frame ad domains can also be blocked.
                        if (isAggressive || isPopupTab) {
                            val blockedByEngine = AdBlockEngine.shouldBlock(url, currentPageUrl, "main_frame")
                            val blockedByStandard = isPopupTab && AdBlockDomainManager.isBlockedInStandard(reqDomain)
                            val blockedByAggressive = isAggressive && AdBlockDomainManager.isBlockedInAggressive(reqDomain)

                            if (blockedByEngine || blockedByStandard || blockedByAggressive) {
                                preferences.incrementBlockedRequests()
                                if (isPopupTab && onyxWv != null) {
                                    onyxWv.post {
                                        val tabId = onyxWv.tabId
                                        if (tabId.isNotBlank()) {
                                            tabActionCallback?.closeTab(tabId)
                                        }
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

            val currentDomain = preferences.cleanDomain(currentPageUrl)
            val isMetaContext = isMetaDomain(pageDomain) || isMetaDomain(currentDomain) ||
                    isCaptchaOrAuthUrl(currentPageUrl, currentDomain) ||
                    isCaptchaOrAuthUrl(pageUrl, pageDomain)

            // ── Universal CAPTCHA, Verification & Security Protection ──────────────
            // Anti-bot verifications and checkpoints must never be blocked on any website
            val isCaptchaResource = isCaptchaOrAuthUrl(url, reqDomain)
            if (isCaptchaResource) {
                return null
            }

            // ── OAuth / SSO / Identity & Payment Provider Exemption ───────────────
            // Brave exempts known identity providers from BLOCK_THIRD_PARTY cookie mode because
            // they are legitimately cross-site (Google Sign-In iframes, Stripe payment forms, etc.).
            val isOAuthResource = isOAuthOrLoginProvider(reqDomain) ||
                    isOAuthOrLoginProvider(pageDomain) ||
                    isOAuthOrLoginUrl(url)
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
                // On third-party sites: allow first-party Meta assets/OAuth unless it's a pure tracking pixel
                if (!isMetaAdPixel) {
                    return null
                }
            }

            // ── Permitted Social Content & Embeds ─────────────────────────────────
            // When allowed, completely bypass adblock and social tracking filters
            val isFbContent = preferences.allowFacebookLogins &&
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
                    preferences.incrementBlockedRequests()
                    return createBlockedResponse(resourceType)
                }
            }

            // ── Global Script Blocking ─────────────────────────────────────────
            if (!isWhitelisted && (preferences.isGlobalScriptBlockingEnabled ||
                    preferences.isScriptBlockingEnabledForDomain(pageDomain))) {
                if (resourceType == "script") {
                    return createBlockedResponse("script")
                }
            }

            // ── Ad & Tracker Blocking ─────────────────────────────────────────
            if (preferences.isAdBlockEnabled && !isWhitelisted) {
                val isAggressive = preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE

                // In Standard mode (Brave parity): Main-frame top-level navigations are never cancelled
                // or 403-intercepted by adblock rules (DomainBlockingType::kNone in Brave). Only subresources and trackers
                // are intercepted. In Aggressive mode (DomainBlockingType::kAggressive), main-frame ad domains can also be blocked.
                if (request.isForMainFrame && !isAggressive) {
                    return null
                }

                // Never block media streams (video/audio, HLS chunks, DASH segments) with generic domain lists.
                // Only let the adblock engine check if there is an explicit media rule.
                if (resourceType == "media") {
                    val blockedByEngine = AdBlockEngine.shouldBlock(url, pageUrl, "media")
                    if (blockedByEngine) {
                        preferences.incrementBlockedRequests()
                        return createBlockedResponse("media")
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

                // 1. Fast-path in-memory Kotlin check: evaluate known domain blocklists FIRST
                val blockedByStandard = AdBlockDomainManager.isBlockedInStandard(uncloakedDomain)
                val blockedByAggressive = isAggressive && AdBlockDomainManager.isBlockedInAggressive(uncloakedDomain)
                val blockedByDomain = blockedByStandard || blockedByAggressive

                // 2. Query native Rust engine only if domain is not already known to be blocked
                val resourceTypeInt = detectResourceTypeInt(request)
                val engineResult = if (!blockedByDomain && !isAdTestResource) {
                    AdBlockEngine.checkRequest(url, pageUrl, resourceTypeInt)
                } else null

                val blockedByEngine = engineResult?.shouldBlock == true
                val redirectData = engineResult?.redirectData

                if (isAdTestResource || blockedByDomain || blockedByEngine) {
                    preferences.incrementBlockedRequests()

                    // If a surrogate redirect ($redirect rule) is matched, serve the surrogate script directly!
                    if (!redirectData.isNullOrBlank()) {
                        val surrogateResponse = createSurrogateResponse(redirectData, resourceType)
                        if (surrogateResponse != null) {
                            return surrogateResponse
                        }
                    }

                    return createBlockedResponse(resourceType)
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
        return handleUrlLoading(view, uri, request.isForMainFrame)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("handleUrlLoading(view, Uri.parse(url), true)"))
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return handleUrlLoading(view, Uri.parse(url), isForMainFrame = true)
    }

    private fun handleUrlLoading(view: WebView?, uri: Uri, isForMainFrame: Boolean): Boolean {
        return try {
            val url = uri.toString()
            val normalizedUrl = url.trimEnd('/')
            val scheme = uri.scheme?.lowercase() ?: ""

            if (isForMainFrame && url.isNotBlank() && !isSyntheticOrDataUrl(url)) {
                pendingMainFrameUrl = url
                isMainFrameDocumentLoaded = false
                (view as? OnyxWebView)?.let {
                    it.pendingMainFrameUrl = url
                    it.isMainFrameDocumentLoaded = false
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
                    val isAggressive = preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE
                    val onyxWv = view as? OnyxWebView
                    val isPopupTab = onyxWv != null && (onyxWv.isPopupTab || onyxWv.isPopupPendingDisplay ||
                            (tabActionCallback?.getTabById(onyxWv.tabId)?.parentId != null))

                    val isAuthOrSecurityFlow = isOAuthOrLoginProvider(reqDomain) ||
                            isOAuthOrLoginProvider(pageDomain) ||
                            isMetaDomain(reqDomain) ||
                            isCaptchaOrAuthUrl(url, reqDomain) ||
                            isOAuthOrLoginUrl(url) ||
                            (onyxWv?.isWithinOAuthGracePeriod() == true)

                    if (!isAuthOrSecurityFlow && (isAggressive || isPopupTab)) {
                        val blockedByEngine = AdBlockEngine.shouldBlock(url, currentPageUrl, "main_frame")
                        val blockedByStandard = isPopupTab && AdBlockDomainManager.isBlockedInStandard(reqDomain)
                        val blockedByAggressive = isAggressive && AdBlockDomainManager.isBlockedInAggressive(reqDomain)

                        if (blockedByEngine || blockedByStandard || blockedByAggressive) {
                            preferences.incrementBlockedRequests()
                            // If this WebView is a newly opened popup tab, close it!
                            if (isPopupTab && onyxWv != null) {
                                onyxWv.post {
                                    val tabId = onyxWv.tabId
                                    if (tabId.isNotBlank()) {
                                        tabActionCallback?.closeTab(tabId)
                                    }
                                }
                            }
                            return true // Cancel the ad navigation!
                        }
                    }
                }

                // If this is a pending popup window and it's NOT an ad, safely display it!
                if (view is OnyxWebView && view.isPopupPendingDisplay) {
                    val onyxWv = view
                    val tabId = onyxWv.tabId
                    val tab = tabActionCallback?.getTabById(tabId)
                    if (tab != null) {
                        tabActionCallback?.displayPopupTab(tab)
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

        return promptOrLaunchApp(intent, appName, fallback = null)
    }

    private fun handleIntentScheme(view: WebView?, url: String): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                component = null
                selector = null
            }

            if (preferences.isOpenLinksInAppEnabled) {
                val fallbackAction: () -> Unit = {
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank() &&
                        (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                        bypassAppInterceptUrls.add(fallbackUrl)
                        view?.post { view.loadUrl(fallbackUrl) }
                    } else {
                        val dataUri = intent.data
                        if (dataUri != null) {
                            val dataScheme = dataUri.scheme?.lowercase()
                            if (dataScheme == "http" || dataScheme == "https") {
                                val target = dataUri.toString()
                                bypassAppInterceptUrls.add(target)
                                view?.post { view.loadUrl(target) }
                            }
                        }
                    }
                }

                return promptOrLaunchApp(
                    intent = intent,
                    appName = intent.getPackage(),
                    fallback = fallbackAction
                )
            }

            // 1st Fallback: browser_fallback_url extra
            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
            if (!fallbackUrl.isNullOrBlank() &&
                (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                view?.post { view.loadUrl(fallbackUrl) }
                return true
            }

            // 2nd Fallback: intent's data if it is http/https
            val dataUri = intent.data
            if (dataUri != null) {
                val dataScheme = dataUri.scheme?.lowercase()
                if (dataScheme == "http" || dataScheme == "https") {
                    view?.post { view.loadUrl(dataUri.toString()) }
                    return true
                }
            }

            // 3rd Fallback: explicit package -> Google Play Store
            val pkg = intent.getPackage()
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
                return promptOrLaunchApp(intent, "Telegram", fallback = stayInOnyxAction)
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
                return promptOrLaunchApp(intent, "WhatsApp", fallback = stayInOnyxAction)
            }
        } else if (host.contains("youtube.com") || host == "youtu.be" || host.contains("reddit.com")) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // To prevent an infinite loop where the system resolves the link back to Onyx Browser,
            // we query for activities and filter out our own package.
            val pm = context.packageManager
            val resolveInfoList = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            val externalApp = resolveInfoList.firstOrNull { it.activityInfo.packageName != context.packageName }
            
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

        if (preferences.isAdBlockEnabled && preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE && !isWhitelisted && !isAuthOrMeta) {
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
        if (preferences.isCosmeticFilteringEnabled && !isWhitelisted && !isAuthOrMeta) {
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

        // Passkey / WebAuthn support — only when not in an auth checkpoint that uses native navigator.credentials
        if (preferences.isPasskeysEnabled && !isAuthOrMeta) {
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
            onyxWv?.applyUserAgentForUrl(url)
            onUrlChanged(url)

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
                val allowThirdPartyCookies = isPopup || isWithinGrace ||
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

            // If this is a pending popup window that successfully started navigating to a valid URL, display it
            if (onyxWv?.isPopupPendingDisplay == true && url != "about:blank") {
                val tab = tabActionCallback?.getTabById(onyxWv.tabId)
                if (tab != null) {
                    tabActionCallback?.displayPopupTab(tab)
                }
            }

            if (!isAuthOrMeta) {
                val isAdBlockActiveForPage = preferences.isAdBlockEnabled && !preferences.isDomainWhitelisted(url)
                view?.evaluateJavascript("window.__onyxAdBlockEnabled = $isAdBlockActiveForPage;", null)
                if (isAdBlockActiveForPage) {
                    val lvl = preferences.blockingLevel
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
                                // Decode and execute using Function() constructor approach for isolation
                                // matching Brave's kScriptletInitScript self-removing <script> pattern
                                val wrappedScriptlet = """
                                    (function() {
                                        try {
                                            var _b = atob('$b64');
                                            var _f = new Function(_b);
                                            _f();
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
            if (preferences.isPasskeysEnabled && !isAuthOrMeta) {
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
                val tab = tabActionCallback?.getTabById(onyxWv.tabId)
                if (tab != null) {
                    tabActionCallback?.displayPopupTab(tab)
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
        if (urlPath.endsWith(".m3u8") || urlPath.endsWith(".ts") || urlPath.endsWith(".mpd") ||
            urlPath.endsWith(".m4s") || urlPath.endsWith(".mp4") || urlPath.endsWith(".webm") ||
            urlPath.endsWith(".ogg") || urlPath.endsWith(".mp3") || urlPath.endsWith(".m4a") ||
            urlPath.endsWith(".aac") || urlPath.endsWith(".flv")) {
            return "media"
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

        val acceptHeader = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
        val fetchMode = request.requestHeaders?.get("Sec-Fetch-Mode")?.lowercase() ?: ""
        val fetchDest = request.requestHeaders?.get("Sec-Fetch-Dest")?.lowercase() ?: ""
        val reqWith = request.requestHeaders?.get("X-Requested-With")?.lowercase() ?: ""
        val urlPath = request.url?.path?.lowercase() ?: ""

        if (fetchDest == "iframe" || fetchDest == "frame") return AdBlockEngine.RESOURCE_TYPE_SUB_FRAME
        if (fetchDest == "script") return AdBlockEngine.RESOURCE_TYPE_SCRIPT
        if (fetchDest == "image") return AdBlockEngine.RESOURCE_TYPE_IMAGE
        if (fetchDest == "style") return AdBlockEngine.RESOURCE_TYPE_STYLESHEET
        if (fetchDest == "video" || fetchDest == "audio") return AdBlockEngine.RESOURCE_TYPE_MEDIA
        if (urlPath.endsWith(".m3u8") || urlPath.endsWith(".ts") || urlPath.endsWith(".mpd") ||
            urlPath.endsWith(".m4s") || urlPath.endsWith(".mp4") || urlPath.endsWith(".webm") ||
            urlPath.endsWith(".ogg") || urlPath.endsWith(".mp3") || urlPath.endsWith(".m4a") ||
            urlPath.endsWith(".aac") || urlPath.endsWith(".flv")) {
            return AdBlockEngine.RESOURCE_TYPE_MEDIA
        }
        if (fetchDest == "empty" || fetchMode == "cors" || reqWith == "xmlhttprequest") return AdBlockEngine.RESOURCE_TYPE_XHR

        return when {
            acceptHeader.contains("text/css") || urlPath.endsWith(".css") -> AdBlockEngine.RESOURCE_TYPE_STYLESHEET
            acceptHeader.contains("javascript") || urlPath.endsWith(".js") -> AdBlockEngine.RESOURCE_TYPE_SCRIPT
            acceptHeader.contains("image/") || urlPath.endsWith(".png") ||
                    urlPath.endsWith(".jpg") || urlPath.endsWith(".jpeg") ||
                    urlPath.endsWith(".webp") || urlPath.endsWith(".gif") ||
                    urlPath.endsWith(".svg") || urlPath.endsWith(".ico") -> AdBlockEngine.RESOURCE_TYPE_IMAGE
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

        val fallbackUrl = failingUrl.replaceFirst("https://", "http://")
        val normalizedFallback = fallbackUrl.trimEnd('/')
        if (preferences.httpsUpgradeMode != BrowserPreferences.HTTPS_MODE_STRICT &&
            (upgradedUrls.contains(fallbackUrl) || upgradedUrls.contains(normalizedFallback))) {
            handler?.cancel()
            view?.post { view.loadUrl(fallbackUrl) }
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
                val populatedHtml = template.replace("{{ERROR_JSON_B64}}", errorJsonB64)
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
}
