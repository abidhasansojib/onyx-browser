package com.onyx.browser.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.web.error.SyntheticNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@SuppressLint("SetJavaScriptEnabled")
class OnyxWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var tabId: String = ""
    var isIncognito: Boolean = false
    var pendingSslHandler: android.webkit.SslErrorHandler? = null
    var currentSyntheticState: SyntheticNavigationState? = null
    
    val touchBridge = OnyxTouchBridge()
    var lastTouchX: Float = 0f
        private set
    var lastTouchY: Float = 0f
        private set

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return super.onTouchEvent(event)
    }

    val regexFindBridge = RegexFindBridge(this) { activeIndex, matchCount ->
        findListener?.invoke(activeIndex, matchCount)
    }

    var onScrollChangedCallback: ((Int, Int, Int, Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedCallback?.invoke(l, t, oldl, oldt)
    }

    private var findListener: ((Int, Int) -> Unit)? = null

    fun setRegexFindListener(listener: (Int, Int) -> Unit) {
        findListener = listener
    }
    val sessionSslBypasses: MutableSet<String> = mutableSetOf()

    fun clearSyntheticState() {
        currentSyntheticState = null
        pendingSslHandler = null
    }

    override fun reload() {
        val failing = currentSyntheticState?.failingUrl
        if (!failing.isNullOrBlank()) {
            clearSyntheticState()
            loadUrl(failing)
            return
        }
        val currentUrl = url
        if (currentUrl != null && (currentUrl.startsWith("data:") || currentUrl.startsWith("file:///android_asset/error_page"))) {
            return
        }
        super.reload()
    }

    // The standard Chrome-on-Android mobile UA (used for all normal browsing).
    private val ipadUserAgent = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private val iphoneUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    private fun getBaseUserAgent(prefs: com.onyx.browser.data.preferences.BrowserPreferences): String {
        return when (prefs.userAgentSpoofTemplate) {
            "ipad" -> ipadUserAgent
            "windows" -> desktopUserAgent
            "iphone" -> iphoneUserAgent
            else -> mobileUserAgent
        }
    }
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"

    // Real Windows Chrome desktop UA — what actual desktop Chrome sends.
    // Using a proper desktop UA (not a mangled mobile one) ensures sites serve
    // full desktop layouts instead of falling back to mobile.
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.6778.135 Safari/537.36"

    // Desktop domains are now persisted globally in BrowserPreferences

    init {
        configureSettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSettings() {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            // Set to false so ALL window.open() calls are routed through our
            // onCreateWindow callback — where we enforce the isUserGesture gate.
            // This prevents ad scripts from bypassing the callback entirely.
            javaScriptCanOpenWindowsAutomatically = false

            // Performance & Rendering (Local Hardware Acceleration & 120Hz Support)
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // Advance Rendering tweaks
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                offscreenPreRaster = true
            }

            // Local File & Content Access for HTML / Markdown Previews
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true

            // Media & Streaming Support (YouTube, Twitch, Video players)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // GPU Acceleration & Smooth Scrolling
        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        isVerticalFadingEdgeEnabled = false
        isHorizontalFadingEdgeEnabled = false
        isScrollbarFadingEnabled = true


        // Apply UA Spoofer if configured
        val tempPrefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        settings.userAgentString = getBaseUserAgent(tempPrefs)

        try {
            val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
            when (prefs.cookieBlockingMode) {
                com.onyx.browser.data.preferences.BrowserPreferences.COOKIE_BLOCK_ALL -> {
                    android.webkit.CookieManager.getInstance().setAcceptCookie(false)
                }
                com.onyx.browser.data.preferences.BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY -> {
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                }
                else -> { // COOKIE_BLOCK_NONE — allow all
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
            }

            // Android Autofill & Password Manager support
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = if (prefs.isAutofillEnabled) {
                    View.IMPORTANT_FOR_AUTOFILL_YES
                } else {
                    View.IMPORTANT_FOR_AUTOFILL_NO
                }
            }
            settings.saveFormData = prefs.isAutofillEnabled
        } catch (_: Exception) {}

        // WebAuthn Passkeys bridge
        val activity = findActivity(context)
        if (activity != null) {
            try {
                val coroutineScope = (activity as? LifecycleOwner)?.lifecycleScope
                    ?: CoroutineScope(Dispatchers.Main)
                addJavascriptInterface(
                    PasskeyWebAuthnBridge(activity, this, coroutineScope),
                    PasskeyWebAuthnBridge.JS_INTERFACE_NAME
                )
            } catch (_: Exception) {}
        }

        // Media Playback Bridge (for background audio/video and lockscreen mini player)
        try {
            addJavascriptInterface(
                com.onyx.browser.media.MediaPlaybackBridge(context.applicationContext, this),
                "OnyxMediaBridge"
            )
        } catch (_: Exception) {}

        // Universal Error Page Bridge
        try {
            addJavascriptInterface(
                com.onyx.browser.web.error.OnyxErrorBridge(this, activity),
                "OnyxErrorBridge"
            )
        } catch (_: Exception) {}

        // Blob Downloads Bridge
        try {
            val bridgeScope = (activity as? LifecycleOwner)?.lifecycleScope
                ?: CoroutineScope(Dispatchers.Main)
            addJavascriptInterface(
                OnyxBlobBridge(context.applicationContext, bridgeScope),
                "OnyxBlobBridge"
            )
        } catch (_: Exception) {}

        // Interactive Elements Touch Bridge (instant context menu detection for links, media, and images)
        try {
            addJavascriptInterface(touchBridge, OnyxTouchBridge.INTERFACE_NAME)
        } catch (_: Exception) {}

        // Document-Start Adblock & Anti-Adblock Shields + WebAuthn Passkeys Polyfill + Media Playback
        try {
            val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
            val currentBlockingLevel = prefs.blockingLevel
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    AdBlockDocumentStart.getScript(currentBlockingLevel),
                    setOf("*")
                )
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    PasskeyWebAuthnBridge.getWebAuthnPolyfillJs(),
                    setOf("*")
                )
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    OnyxTouchBridge.TOUCH_LISTENER_JS,
                    setOf("*")
                )
                if (prefs.isBackgroundPlayEnabled) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        this,
                        MediaPlaybackManager.backgroundPlaybackScript,
                        setOf("*")
                    )
                }
            }
        } catch (_: Exception) {}

        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun findActivity(ctx: Context): Activity? {
        var current: Context? = ctx
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
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
            val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
            try {
                when (prefs.cookieBlockingMode) {
                    com.onyx.browser.data.preferences.BrowserPreferences.COOKIE_BLOCK_ALL -> {
                        CookieManager.getInstance().setAcceptCookie(false)
                    }
                    com.onyx.browser.data.preferences.BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY -> {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    }
                    else -> {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }
                }
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
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        return prefs.desktopDomains.any { key == it || key.endsWith(".$it") }
    }

    /**
     * Toggle desktop mode for [pageUrl]'s domain and reload.
     * Called by the 3-dot menu toggle.
     */
    fun toggleDesktopModeForPage(pageUrl: String) {
        val key = hostKey(pageUrl) ?: return
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        val currentDomains = prefs.desktopDomains.toMutableSet()
        
        if (currentDomains.any { key == it || key.endsWith(".$it") }) {
            currentDomains.removeAll { key == it || key.endsWith(".$it") || it.endsWith(".$key") }
            currentDomains.remove(key)
        } else {
            currentDomains.add(key)
        }
        prefs.desktopDomains = currentDomains
        
        setInitialScale(0)
        applyUserAgentForUrl(pageUrl, forceRefreshLayout = true)
    }

    /**
     * Called on every page navigation (from OnyxWebViewClient.onPageStarted).
     * Applies the correct UA (desktop or mobile) for the new URL's domain —
     * this is the core mechanism that makes desktop mode per-domain.
     *
     * We do NOT reload here because this fires as navigation begins; changing the
     * UA before the page loads is sufficient for the server to serve the right version.
     */
    fun applyUserAgentForUrl(urlString: String, forceRefreshLayout: Boolean = false) {
        val key = hostKey(urlString)
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        val wantsDesktop = key != null && prefs.desktopDomains.any { key == it || key.endsWith(".$it") }
        
        val targetUa = if (wantsDesktop || prefs.userAgentSpoofTemplate == "windows") {
            desktopUserAgent
        } else {
            getBaseUserAgent(prefs)
        }
        
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        val currentUaMatchesTarget = settings.userAgentString == targetUa
        
        if (currentUaMatchesTarget) {
            if (forceRefreshLayout) {
                setInitialScale(0)
                this.loadUrl(this.url ?: urlString)
            }
            return
        }
        
        settings.userAgentString = targetUa
        
        if (forceRefreshLayout) {
            setInitialScale(0)
            this.loadUrl(this.url ?: urlString)
        }
    }

    // ── Legacy compat (used by setDesktopMode call-sites that haven't been updated yet) ──

    /** @deprecated Use toggleDesktopModeForPage / isDesktopModeEnabledForCurrentPage */
    fun isDesktopModeEnabled(): Boolean = isDesktopModeEnabledForCurrentPage()

    fun updateShieldsLevel(level: Int) {
        evaluateJavascript("if (window.__onyx_set_blocking_level) window.__onyx_set_blocking_level($level);", null)
    }

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

    override fun onWindowVisibilityChanged(visibility: Int) {
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (prefs.isBackgroundPlayEnabled) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (prefs.isBackgroundPlayEnabled) {
            super.dispatchWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.dispatchWindowVisibilityChanged(visibility)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (prefs.isBackgroundPlayEnabled) {
            super.onWindowFocusChanged(true)
        } else {
            super.onWindowFocusChanged(hasWindowFocus)
        }
    }
}

