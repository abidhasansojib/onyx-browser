package com.onyx.browser.web

import android.content.Context
import android.webkit.JavascriptInterface
import com.onyx.browser.data.preferences.BrowserPreferences

/**
 * Synchronous JavaScript interface for checking ad-blocker status at document-start.
 * Allows web pages to query whether ad blocking or cosmetic filters should be applied
 * for the current domain.
 */
class OnyxShieldBridge(private val context: Context) {

    private val preferences = BrowserPreferences.getInstance(context)

    @JavascriptInterface
    fun isAdBlockActive(domainOrUrl: String?): Boolean {
        if (!preferences.isAdBlockEnabled) return false
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return true
        val isWhitelisted = preferences.isDomainWhitelisted(target)
        return !isWhitelisted
    }

    @JavascriptInterface
    fun isCosmeticFilteringActive(domainOrUrl: String?): Boolean {
        if (!preferences.isAdBlockEnabled || !preferences.isCosmeticFilteringEnabled) return false
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return true
        return !preferences.isDomainWhitelisted(target)
    }

    @JavascriptInterface
    fun getBlockingLevel(): Int {
        return preferences.blockingLevel
    }

    @JavascriptInterface
    fun isFacebookLoginAllowed(): Boolean {
        return preferences.allowFacebookLogins
    }

    @JavascriptInterface
    fun isAntiAdblockDetectionEnabled(): Boolean {
        return preferences.isAntiAdblockDetectionEnabled
    }

    @JavascriptInterface
    fun isSocialMediaBlockingEnabled(): Boolean {
        return preferences.isSocialMediaBlockingEnabled
    }

    @JavascriptInterface
    fun getHiddenSelectors(classesJson: String?, idsJson: String?, pageUrl: String?): String {
        if (!preferences.isAdBlockEnabled || !preferences.isCosmeticFilteringEnabled) return "[]"
        val url = pageUrl?.takeIf { it.isNotBlank() } ?: return "[]"
        val domain = preferences.cleanDomain(url)
        if (preferences.isDomainWhitelisted(domain)) return "[]"

        val d = domain.lowercase()
        val isMetaOrCaptcha = d == "facebook.com" || d.endsWith(".facebook.com") ||
                d == "fb.com" || d.endsWith(".fb.com") ||
                d == "messenger.com" || d.endsWith(".messenger.com") ||
                d == "instagram.com" || d.endsWith(".instagram.com") ||
                d.contains("arkose") || d.contains("recaptcha") ||
                d.contains("hcaptcha") || d.contains("turnstile") || d.contains("funcaptcha")
        if (isMetaOrCaptcha) return "[]"

        val cJson = classesJson?.takeIf { it.isNotBlank() } ?: "[]"
        val iJson = idsJson?.takeIf { it.isNotBlank() } ?: "[]"
        return com.onyx.browser.nativebridge.AdBlockEngine.getHiddenSelectors(cJson, iJson, url)
    }
}
