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
}
