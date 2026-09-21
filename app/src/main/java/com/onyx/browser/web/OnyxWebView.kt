package com.onyx.browser.web

import android.annotation.SuppressLint
import android.content.Context
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
    private var defaultUserAgent: String = ""
    private var isDesktopUserAgent: Boolean = false

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

            // Performance & Rendering (Via Browser optimized)
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

        // Use an authentic Chrome Mobile UA so sites like Facebook don't detect us as a
        // bot / unknown client (which causes CAPTCHA confirmation timeouts and login failures).
        val chromeUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"
        settings.userAgentString = chromeUa
        defaultUserAgent = chromeUa

        try {
            // Allow third-party cookies for normal sessions (required for Facebook login,
            // Google accounts, etc.).  Incognito mode disables them in setIncognitoMode().
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        } catch (e: Exception) {
            // Safe fallback
        }
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
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) } catch (_: Exception) {}
        }
    }

    fun setDesktopMode(enabled: Boolean) {
        if (isDesktopUserAgent == enabled) return
        isDesktopUserAgent = enabled
        if (enabled) {
            val desktopUa = defaultUserAgent
                .replace("Mobile", "eliboM")
                .replace("Android", "Linux")
            settings.userAgentString = desktopUa
        } else {
            settings.userAgentString = defaultUserAgent
        }
        reload()
    }

    fun isDesktopModeEnabled(): Boolean = isDesktopUserAgent

    fun destroySafely() {
        try {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        } catch (e: Exception) {
            // Ignore during teardown
        }
    }
}
