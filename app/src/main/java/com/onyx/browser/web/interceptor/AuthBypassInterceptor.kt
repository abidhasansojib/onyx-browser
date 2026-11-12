package com.onyx.browser.web.interceptor

import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import com.onyx.browser.web.AdBlockDomainManager
import com.onyx.browser.web.OnyxWebView
import com.onyx.browser.web.OnyxWebViewClient
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Early pass-through interceptor that short-circuits the interception chain (returning `null`
 * without calling [InterceptorChain.proceed]) for:
 * - Bot challenges and CAPTCHA verification endpoints (Cloudflare Turnstile, Arkose, reCAPTCHA, hCaptcha)
 * - OAuth, SSO, identity, and payment providers (Google, Apple, Microsoft, GitHub, Stripe, PayPal, Meta)
 * - Active OAuth grace period subresources ([OnyxWebView.isWithinOAuthGracePeriod])
 * - Partitioned media streams and HLS/DASH segments ([PrivacyUrlSanitizer.isMediaUrl])
 */
class AuthBypassInterceptor(
    private val preferences: BrowserPreferences? = null,
    private val currentPageUrlProvider: () -> String = { "" },
    private val onBlockedRequest: ((WebView?) -> Unit)? = null
) : RequestInterceptor {

    override fun intercept(chain: InterceptorChain): WebResourceResponse? {
        val ctx = chain.context
        val url = ctx.requestUrl
        val reqDomain = ctx.requestHost
        val pageUrl = ctx.pageUrl
        val pageDomain = ctx.pageHost
        val view = ctx.view
        val onyxWebView = ctx.onyxWebView
        val resourceType = ctx.resourceType

        // 1. Never block in-page translation resources
        if (url.contains("translate.google.com") || url.contains("translate.googleapis.com")) {
            return null
        }

        val currentPageUrl = currentPageUrlProvider()
        val currentDomain = preferences?.cleanDomain(currentPageUrl) ?: ""

        val isMetaContext = PrivacyUrlSanitizer.isMetaDomain(pageDomain) ||
                PrivacyUrlSanitizer.isMetaDomain(currentDomain) ||
                PrivacyUrlSanitizer.isCaptchaOrAuthUrl(currentPageUrl, currentDomain) ||
                PrivacyUrlSanitizer.isCaptchaOrAuthUrl(pageUrl, pageDomain)

        // 2. Universal CAPTCHA, Verification & Security Protection
        val isCaptchaResource = PrivacyUrlSanitizer.isCaptchaOrAuthUrl(url, reqDomain)

        // 3. OAuth / SSO / Identity & Payment Provider Exemption
        val isOAuthResource = PrivacyUrlSanitizer.isOAuthOrLoginProvider(reqDomain) ||
                PrivacyUrlSanitizer.isOAuthOrLoginProvider(pageDomain) ||
                PrivacyUrlSanitizer.isOAuthOrLoginUrl(url)

        val isMediaSubresource = resourceType == "media" ||
                PrivacyUrlSanitizer.isMediaUrl(url) ||
                OnyxWebViewClient.isMediaOrStreamingHost(reqDomain)

        // Ensure active auth, CAPTCHA iframes, and media streams retain cross-site cookies
        if (isMetaContext || isCaptchaResource || isOAuthResource) {
            onyxWebView?.lastOAuthInteractionTimestamp = System.currentTimeMillis()
            view?.post {
                try {
                    val activeCm = onyxWebView?.cookieManager ?: CookieManager.getInstance()
                    activeCm.setAcceptCookie(true)
                    activeCm.setAcceptThirdPartyCookies(view, true)
                } catch (_: Exception) {}
            }
        } else if (isMediaSubresource || resourceType == "sub_frame") {
            if (isMediaSubresource ||
                (resourceType == "sub_frame" && (
                        OnyxWebViewClient.isStreamingOrMediaPage(url) ||
                        OnyxWebViewClient.isStreamingOrMediaPage(pageUrl)
                ))
            ) {
                onyxWebView?.hasActiveMediaSubresource = true
            }
            view?.post {
                try {
                    val activeCm = onyxWebView?.cookieManager ?: CookieManager.getInstance()
                    activeCm.setAcceptCookie(true)
                    activeCm.setAcceptThirdPartyCookies(view, true)
                } catch (_: Exception) {}
            }
        }

        if (isCaptchaResource) {
            return null
        }

        // 4. Google Sign-In Prompts Blocking (Brave parity)
        // Must be evaluated before isOAuthResource short-circuits accounts.google.com
        if (url.contains("accounts.google.com/gsi/") && preferences != null) {
            val isGoogleSignInAllowed = preferences.isWebGoogleSignInAllowed(pageDomain)
                ?: preferences.isWebGoogleSignInAllowed(currentDomain)
                ?: !preferences.isGoogleSignInBlockedGlobal
            if (!isGoogleSignInAllowed &&
                !pageDomain.endsWith("google.com") &&
                !currentDomain.endsWith("google.com")
            ) {
                onBlockedRequest?.invoke(view)
                return WebResourceResponse(
                    "application/javascript",
                    "UTF-8",
                    200,
                    "OK",
                    GSI_STUB_HEADERS,
                    ByteArrayInputStream(GSI_STUB_BYTES)
                )
            }
        }

        if (isOAuthResource) {
            return null
        }

        // 5. Meta / Facebook First-Party Integrity & Active OAuth Grace Period
        val isMetaResource = PrivacyUrlSanitizer.isMetaDomain(reqDomain)
        val isMetaAdPixel = reqDomain == "pixel.facebook.com" ||
                reqDomain == "an.facebook.com" ||
                reqDomain == "tr.facebook.com"
        val isWithinOAuthGrace = onyxWebView?.isWithinOAuthGracePeriod() == true

        if (isMetaResource) {
            if (isMetaContext) {
                return null
            }
            if (isWithinOAuthGrace && !isMetaAdPixel) {
                return null
            }
        }

        if (isWithinOAuthGrace && !isMetaAdPixel) {
            return null
        }

        // 6. Permitted Social Content & Embeds
        if (preferences != null) {
            val isInFbAuth = isMetaContext || isWithinOAuthGrace || isOAuthResource
            val isFbContent = preferences.allowFacebookLogins && isInFbAuth &&
                    FACEBOOK_CONTENT_DOMAINS.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
            if (isFbContent) {
                return null
            }

            val isTwitterContent = preferences.allowTwitterEmbeds &&
                    TWITTER_CONTENT_DOMAINS.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
            if (isTwitterContent) {
                return null
            }

            val isLinkedInContent = preferences.allowLinkedInEmbeds &&
                    LINKEDIN_CONTENT_DOMAINS.any { d -> reqDomain == d || reqDomain.endsWith(".$d") }
            if (isLinkedInContent) {
                return null
            }

            // Element blocking in private windows: respect setting
            val isIncognitoView = onyxWebView?.isIncognito ?: false
            if (isIncognitoView && !preferences.isElementBlockingInPrivateEnabled) {
                return null
            }
        }

        // 7. Partitioned media streams and HLS/DASH segments pass-through
        // Only defer to AdBlockRequestInterceptor if an explicit $media rule matches in AdBlockEngine.
        if (PrivacyUrlSanitizer.isMediaUrl(url) || resourceType == "media") {
            val explicitlyBlocked = try {
                val uncloakedMediaUrl = AdBlockDomainManager.uncloakUrl(url)
                val method = ctx.request.method ?: "GET"
                AdBlockEngine.checkRequest(
                    uncloakedMediaUrl,
                    pageUrl,
                    AdBlockEngine.RESOURCE_TYPE_MEDIA,
                    method
                ).shouldBlock
            } catch (_: Throwable) {
                false
            }
            if (!explicitlyBlocked) {
                return null
            }
        }

        return chain.proceed(ctx)
    }

    companion object {
        val FACEBOOK_CONTENT_DOMAINS: Set<String> = setOf(
            "facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "touch.facebook.com",
            "static.facebook.com", "staticxx.facebook.com", "static.xx.fbcdn.net",
            "connect.facebook.net", "facebook.net", "graph.facebook.com",
            "fbcdn.net", "scontent.xx.fbcdn.net", "fbsbx.com", "fb.com", "fb.me", "accountkit.com",
            "meta.com", "threads.net", "cdninstagram.com"
        )

        val TWITTER_CONTENT_DOMAINS: Set<String> = setOf(
            "platform.twitter.com", "cdn.syndication.twimg.com",
            "syndication.twitter.com", "pbs.twimg.com", "abs.twimg.com"
        )

        val LINKEDIN_CONTENT_DOMAINS: Set<String> = setOf(
            "www.linkedin.com", "platform.linkedin.com", "badges.linkedin.com"
        )

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
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        private val GSI_STUB_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Content-Type" to "application/javascript; charset=utf-8",
            "Content-Length" to GSI_STUB_BYTES.size.toString()
        )
    }
}
