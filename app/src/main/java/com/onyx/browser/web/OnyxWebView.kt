package com.onyx.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

@SuppressLint("SetJavaScriptEnabled")
class OnyxWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var tabId: String = ""
    var isIncognito: Boolean = false

    // The standard Chrome-on-Android mobile UA (used for all normal browsing).
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"

    // Real Windows Chrome desktop UA — what actual desktop Chrome sends.
    // Using a proper desktop UA (not a mangled mobile one) ensures sites serve
    // full desktop layouts instead of falling back to mobile.
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.6778.135 Safari/537.36"

    /**
     * Per-tab set of registered hostnames (eTLD+1 level) where the user has
     * requested desktop mode, matching Chrome/Firefox behaviour:
     *   - Desktop mode applies to the *domain* (and its subdomains), not globally.
     *   - Other domains in the same tab are unaffected.
     *   - Switching tabs always reflects the new tab's domain state.
     */
    private val desktopDomains = mutableSetOf<String>()

    init {
        configureSettings()
    }

    private fun configureSettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            // Performance & Rendering
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true

            // Security & Privacy hardening
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // Authentic Chrome Mobile UA — prevents bot detection on Facebook/Google/etc.
        settings.userAgentString = mobileUserAgent

        try {
            val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !prefs.isBlockThirdPartyCookiesEnabled)
        } catch (_: Exception) {}

        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setIncognitoMode(incognito: Boolean) {
        this.isIncognito = incognito
        if (incognito) {
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = false
            clearCache(true)
            clearHistory()
            clearFormData()
            CookieManager.getInstance().setAcceptCookie(false)
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(this, false) } catch (_: Exception) {}
        } else {
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            
            val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
            try { 
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, !prefs.isBlockThirdPartyCookiesEnabled) 
            } catch (_: Exception) {}
        }
    }

    // ── Desktop Mode (per-domain) ────────────────────────────────────────────

    /** Canonical hostname key for a URL: strips "www." so www.google.com == google.com */
    private fun hostKey(urlString: String): String? {
        if (urlString.isBlank() || !urlString.startsWith("http")) return null
        return try {
            Uri.parse(urlString).host?.removePrefix("www.")?.lowercase()
        } catch (_: Exception) { null }
    }

    /**
     * Returns true if the user has enabled desktop mode for the *current page's* domain.
     * This is what the menu toggle should read to show the correct checked state.
     */
    fun isDesktopModeEnabledForCurrentPage(): Boolean {
        val key = hostKey(url ?: "") ?: return false
        return desktopDomains.any { key == it || key.endsWith(".$it") }
    }

    /**
     * Toggle desktop mode for [pageUrl]'s domain and reload.
     * Called by the 3-dot menu toggle.
     */
    fun toggleDesktopModeForPage(pageUrl: String) {
        val key = hostKey(pageUrl) ?: return
        if (desktopDomains.any { key == it || key.endsWith(".$it") }) {
            desktopDomains.removeAll { key == it || key.endsWith(".$it") || it.endsWith(".$key") }
            // Remove exact stored key too
            desktopDomains.remove(key)
        } else {
            desktopDomains.add(key)
        }
        applyUserAgentForUrl(pageUrl, reload = true)
    }

    /**
     * Called on every page navigation (from OnyxWebViewClient.onPageStarted).
     * Applies the correct UA (desktop or mobile) for the new URL's domain —
     * this is the core mechanism that makes desktop mode per-domain.
     *
     * We do NOT reload here because this fires as navigation begins; changing the
     * UA before the page loads is sufficient for the server to serve the right version.
     */
    fun applyUserAgentForUrl(urlString: String, reload: Boolean = false) {
        val key = hostKey(urlString)
        val wantsDesktop = key != null && desktopDomains.any { key == it || key.endsWith(".$it") }
        val currentIsDesktop = settings.userAgentString == desktopUserAgent
        if (wantsDesktop == currentIsDesktop) {
            if (reload) this.reload()
            return
        }
        settings.userAgentString = if (wantsDesktop) desktopUserAgent else mobileUserAgent
        if (reload) this.reload()
    }

    // ── Legacy compat (used by setDesktopMode call-sites that haven't been updated yet) ──

    /** @deprecated Use toggleDesktopModeForPage / isDesktopModeEnabledForCurrentPage */
    fun isDesktopModeEnabled(): Boolean = isDesktopModeEnabledForCurrentPage()

    fun destroySafely() {
        try {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        } catch (_: Exception) {}
    }

    override fun loadUrl(url: String) {
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (prefs.isDoNotTrackEnabled) {
            val headers = mutableMapOf<String, String>()
            headers["DNT"] = "1"
            super.loadUrl(url, headers)
        } else {
            super.loadUrl(url)
        }
    }

    override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (prefs.isDoNotTrackEnabled) {
            additionalHttpHeaders["DNT"] = "1"
        }
        super.loadUrl(url, additionalHttpHeaders)
    }
}
