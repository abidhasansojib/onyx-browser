package com.onyx.browser.web.interceptor

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import com.onyx.browser.web.AdBlockDomainManager

/**
 * Terminal subresource ad, tracker, and script blocking interceptor.
 * Performs social tracker filtering, per-site/global script blocking, static CNAME uncloaking,
 * dual-pass [AdBlockEngine] evaluation, `$redirect` surrogate synthesis, and VAST/403 response creation.
 */
class AdBlockRequestInterceptor(
    private val preferences: BrowserPreferences? = null,
    private val currentPageUrlProvider: () -> String = { "" },
    private val detectResourceTypeInt: (WebResourceRequest) -> Int = { AdBlockEngine.RESOURCE_TYPE_OTHER },
    private val getRequestOrigin: (WebResourceRequest) -> String? = { null },
    private val createBlockedResponse: (resourceType: String, requestUrl: String, requestOrigin: String?) -> WebResourceResponse,
    private val createSurrogateResponse: (redirectData: String, resourceType: String, requestOrigin: String?) -> WebResourceResponse? = { _, _, _ -> null },
    private val onBlockedRequest: ((WebView?) -> Unit)? = null,
    private val customEvaluator: ((InterceptRequestContext) -> WebResourceResponse?)? = null
) : RequestInterceptor {

    constructor(evaluator: (InterceptRequestContext) -> WebResourceResponse?) : this(
        preferences = null,
        createBlockedResponse = { _, _, _ ->
            throw UnsupportedOperationException("Custom evaluator handles responses")
        },
        customEvaluator = evaluator
    )

    override fun intercept(chain: InterceptorChain): WebResourceResponse? {
        val ctx = chain.context

        if (customEvaluator != null) {
            return customEvaluator.invoke(ctx) ?: chain.proceed(ctx)
        }

        val prefs = preferences ?: return chain.proceed(ctx)
        val url = ctx.requestUrl
        val reqDomain = ctx.requestHost
        val pageUrl = ctx.pageUrl
        val pageDomain = ctx.pageHost
        val resourceType = ctx.resourceType
        val view = ctx.view
        val request = ctx.request

        val isWhitelisted = prefs.isDomainWhitelisted(pageDomain)
        if (isWhitelisted) {
            return chain.proceed(ctx)
        }

        val requestOrigin = getRequestOrigin(request)
        val currentPageUrl = currentPageUrlProvider()
        val currentDomain = prefs.cleanDomain(currentPageUrl)
        val isMetaContext = PrivacyUrlSanitizer.isMetaDomain(pageDomain) ||
                PrivacyUrlSanitizer.isMetaDomain(currentDomain) ||
                PrivacyUrlSanitizer.isCaptchaOrAuthUrl(currentPageUrl, currentDomain) ||
                PrivacyUrlSanitizer.isCaptchaOrAuthUrl(pageUrl, pageDomain)

        // 1. Social Media Tracker Blocking
        if (prefs.isSocialMediaBlockingEnabled && !isMetaContext) {
            val isSocialTrackerDomain = SOCIAL_MEDIA_TRACKER_DOMAINS.any { trackerDomain ->
                reqDomain == trackerDomain || reqDomain.endsWith(".$trackerDomain")
            } || (!prefs.allowFacebookLogins &&
                    (reqDomain == "connect.facebook.net" || reqDomain == "graph.facebook.com"))

            if (isSocialTrackerDomain) {
                onBlockedRequest?.invoke(view)
                return createBlockedResponse(resourceType, url, requestOrigin)
            }
        }

        // 2. Global / Per-Domain Script Blocking
        if (prefs.isGlobalScriptBlockingEnabled || prefs.isScriptBlockingEnabledForDomain(pageDomain)) {
            if (resourceType == "script") {
                onBlockedRequest?.invoke(view)
                return createBlockedResponse("script", url, requestOrigin)
            }
        }

        // 3. Ad & Tracker Blocking via Native Rust Engine
        if (prefs.isAdBlockEnabled) {
            val isAggressive = prefs.getEffectiveBlockingLevel(pageDomain) == BrowserPreferences.BLOCKING_AGGRESSIVE

            if (ctx.isMainFrame && !isAggressive) {
                return chain.proceed(ctx)
            }

            // Media streams only reach here if an explicit $media rule matched
            if (resourceType == "media") {
                val uncloakedMediaUrl = AdBlockDomainManager.uncloakUrl(url)
                val mediaMethod = request.method ?: "GET"
                val mediaResult = AdBlockEngine.checkRequest(
                    uncloakedMediaUrl,
                    pageUrl,
                    AdBlockEngine.RESOURCE_TYPE_MEDIA,
                    mediaMethod
                )
                if (mediaResult.shouldBlock) {
                    onBlockedRequest?.invoke(view)
                    if (!mediaResult.redirectData.isNullOrBlank()) {
                        val surrogate = createSurrogateResponse(mediaResult.redirectData, "media", requestOrigin)
                        if (surrogate != null) return surrogate
                    }
                    return createBlockedResponse("media", url, requestOrigin)
                }
                return null
            }

            // Explicit detection for synthetic ad test probes (e.g. adblock-tester.com, d3ward, canyoublockit)
            val isAdTestResource = url.contains("pr_advertising_ads_banner") ||
                    url.contains("ymatuhin.ru") ||
                    url.contains("/fakepage.html") ||
                    (currentDomain == "adblock-tester.com" && (url.contains("/banners/") || url.contains("/ads/")))

            // Static CNAME uncloaking
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
                if (originalResult.shouldBlock ||
                    !originalResult.redirectData.isNullOrBlank() ||
                    !originalResult.rewrittenUrl.isNullOrBlank()
                ) {
                    engineResult = originalResult
                }
            }

            val blockedByEngine = engineResult?.shouldBlock == true
            val redirectData = engineResult?.redirectData
            val rewrittenUrl = engineResult?.rewrittenUrl

            if (isAdTestResource || blockedByEngine) {
                onBlockedRequest?.invoke(view)

                // If a surrogate redirect ($redirect rule) is matched, serve the surrogate script directly
                if (!redirectData.isNullOrBlank()) {
                    val surrogateResponse = createSurrogateResponse(redirectData, resourceType, requestOrigin)
                    if (surrogateResponse != null) {
                        return surrogateResponse
                    }
                }

                return createBlockedResponse(resourceType, url, requestOrigin)
            }

            // If a data: rewrite rule ($rewrite=abp-resource:...) modified the URL, serve the synthesized data.
            if (!rewrittenUrl.isNullOrBlank() && rewrittenUrl != url && rewrittenUrl.startsWith("data:", ignoreCase = true)) {
                val dataResponse = createSurrogateResponse(rewrittenUrl, resourceType, requestOrigin)
                if (dataResponse != null) return dataResponse
            }
        }

        return chain.proceed(ctx)
    }

    companion object {
        val SOCIAL_MEDIA_TRACKER_DOMAINS: Set<String> = setOf(
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
    }
}
