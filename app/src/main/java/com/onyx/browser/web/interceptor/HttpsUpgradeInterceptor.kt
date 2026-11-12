package com.onyx.browser.web.interceptor

import android.webkit.WebResourceResponse
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.web.EmptyInputStream

/**
 * Enforces subresource HTTP -> HTTPS strict mode blocking while preserving passive
 * mixed-content media streams and local/private network addresses.
 *
 * Note: Top-level main-frame HTTP -> HTTPS navigation upgrades are performed in
 * `OnyxWebViewClient.shouldOverrideUrlLoading` because Android WebView's
 * `shouldInterceptRequest` does not follow synthetic 3xx redirects.
 */
class HttpsUpgradeInterceptor(
    private val httpsUpgradeModeProvider: () -> Int,
    private val isDomainWhitelisted: (String) -> Boolean = { false },
    private val onBlockedSubresource: ((InterceptRequestContext) -> WebResourceResponse)? = null
) : RequestInterceptor {

    constructor(
        preferences: BrowserPreferences,
        onBlockedSubresource: ((InterceptRequestContext) -> WebResourceResponse)? = null
    ) : this(
        httpsUpgradeModeProvider = { preferences.httpsUpgradeMode },
        isDomainWhitelisted = { domain -> preferences.isDomainWhitelisted(domain) },
        onBlockedSubresource = onBlockedSubresource
    )

    override fun intercept(chain: InterceptorChain): WebResourceResponse? {
        val ctx = chain.context
        if (ctx.isMainFrame) {
            return chain.proceed(ctx)
        }

        val url = ctx.requestUrl
        if (!url.startsWith("http://", ignoreCase = true)) {
            return chain.proceed(ctx)
        }

        if (httpsUpgradeModeProvider() != BrowserPreferences.HTTPS_MODE_STRICT) {
            return chain.proceed(ctx)
        }

        // Preserve passive mixed-content media streams and HLS/DASH segments
        if (ctx.resourceType == "media" || PrivacyUrlSanitizer.isMediaUrl(url)) {
            return chain.proceed(ctx)
        }

        // Preserve localhost, loopback, and RFC1918 private network hosts
        if (isLocalOrPrivateHost(ctx.requestHost)) {
            return chain.proceed(ctx)
        }

        // Respect per-site shield exemptions
        if (isDomainWhitelisted(ctx.pageHost) || isDomainWhitelisted(ctx.requestHost)) {
            return chain.proceed(ctx)
        }

        return onBlockedSubresource?.invoke(ctx) ?: WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked Cleartext HTTP Subresource (Strict HTTPS)",
            STRICT_HTTPS_BLOCKED_HEADERS,
            EmptyInputStream
        )
    }

    companion object {
        private val STRICT_HTTPS_BLOCKED_HEADERS: Map<String, String> = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Content-Length" to "0"
        )

        fun isLocalOrPrivateHost(host: String): Boolean {
            if (host.isBlank()) return false
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0.0.0.0" ||
                h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal")
            ) {
                return true
            }
            if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("127.")) {
                return true
            }
            if (h.startsWith("172.")) {
                val secondOctet = h.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
            return false
        }
    }
}
