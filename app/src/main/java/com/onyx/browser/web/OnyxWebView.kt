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
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.onyx.browser.web.error.SyntheticNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@SuppressLint("SetJavaScriptEnabled")
class OnyxWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private val webViewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var tabId: String = ""
    var isIncognito: Boolean = false
    var pendingSslHandler: android.webkit.SslErrorHandler? = null
    var currentSyntheticState: SyntheticNavigationState? = null
    var lastFailingUrl: String? = null
    var isLoadingSyntheticPage: Boolean = false
    @Volatile var isPopupPendingDisplay: Boolean = false
    @Volatile var isPopupTab: Boolean = false
    @Volatile var lastOAuthInteractionTimestamp: Long = 0L

    fun isWithinOAuthGracePeriod(): Boolean {
        val elapsed = System.currentTimeMillis() - lastOAuthInteractionTimestamp
        // 10 minutes grace — long enough for Facebook checkpoint/confirmation flows
        return elapsed in 0..600_000L
    }

    @Volatile var pendingMainFrameUrl: String? = null
    @Volatile var isMainFrameDocumentLoaded: Boolean = false
    
    val touchBridge = OnyxTouchBridge()
    var lastTouchX: Float = 0f
        private set
    var lastTouchY: Float = 0f
        private set
    @Volatile private var lastTouchTimestamp: Long = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                lastTouchTimestamp = System.currentTimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Returns true if a user touch event occurred within the last [windowMs] milliseconds.
     * Used as a fallback in onCreateWindow when Blink clears isUserGesture after async
     * promise resolutions (OAuth, payment processors, etc.)
     */
    fun isWithinRecentTouchWindow(windowMs: Long = 1500L): Boolean {
        return (System.currentTimeMillis() - lastTouchTimestamp) <= windowMs
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
    var isDevToolsActive: Boolean = false
    val sessionSslBypasses: MutableSet<String> = mutableSetOf()

    fun clearSyntheticState() {
        currentSyntheticState = null
        pendingSslHandler = null
    }

    override fun reload() {
        val failing = currentSyntheticState?.failingUrl ?: lastFailingUrl
        if (!failing.isNullOrBlank()) {
            clearSyntheticState()
            stopLoading()
            loadUrl(failing)
            return
        }
        val currentUrl = url
        if (currentUrl != null && isSyntheticOrDataUrl(currentUrl)) {
            return
        }
        if (currentUrl != null) {
            pendingMainFrameUrl = currentUrl
            isMainFrameDocumentLoaded = false
        }
        super.reload()
    }

    companion object {
        const val INCOGNITO_PROFILE_NAME = "incognito"

        fun isSyntheticOrDataUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return true
            return url.startsWith("data:") ||
                    url.startsWith("file:///android_asset/") ||
                    url.startsWith("file:///android_res/") ||
                    url.startsWith("about:blank", ignoreCase = true)
        }
    }

    /**
     * Scoped profile reference. Returns the incognito profile when in incognito mode,
     * or the default profile / null when in normal mode or multi-profile is unsupported.
     */
    val profile: Profile?
        get() = if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                WebViewCompat.getProfile(this)
            } catch (_: Throwable) {
                null
            }
        } else null

    /**
     * Scoped CookieManager. Binds to the isolated incognito profile when in incognito mode,
     * or the default profile CookieManager for normal tabs.
     */
    val cookieManager: CookieManager
        get() = profile?.cookieManager ?: CookieManager.getInstance()

    /**
     * Scoped WebStorage. Binds to the isolated incognito profile when in incognito mode,
     * or the default WebStorage for normal tabs.
     */
    val webStorage: WebStorage
        get() = profile?.webStorage ?: WebStorage.getInstance()

    private fun getBaseUserAgent(prefs: com.onyx.browser.data.preferences.BrowserPreferences): String {
        return UserAgentManager.getUserAgentForTemplate(prefs.userAgentSpoofTemplate, prefs, context)
    }

    private val desktopUserAgent: String
        get() = UserAgentManager.getEffectiveDesktopUserAgent(context)

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
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            // Media & Streaming Support (YouTube, Twitch, Video players)
            mediaPlaybackRequiresUserGesture = false
            // Suppress X-Requested-With header to avoid identifying as an embedded WebView to anti-bot systems
            try {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                    androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(this, emptySet())
                }
            } catch (_: Throwable) {}
        }

        // GPU Acceleration & Smooth Scrolling (Direct window hardware rasterization)
        setLayerType(android.view.View.LAYER_TYPE_NONE, null)
        isVerticalFadingEdgeEnabled = false
        isHorizontalFadingEdgeEnabled = false
        isScrollbarFadingEnabled = true


        // Apply UA Spoofer and all persisted settings — one singleton lookup for the whole function
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        settings.userAgentString = getBaseUserAgent(prefs)

        try {
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
                val coroutineScope = (activity as? LifecycleOwner)?.lifecycleScope ?: webViewScope
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
            val bridgeScope = (activity as? LifecycleOwner)?.lifecycleScope ?: webViewScope
            addJavascriptInterface(
                OnyxBlobBridge(context.applicationContext, bridgeScope),
                "OnyxBlobBridge"
            )
        } catch (_: Exception) {}

        // Interactive Elements Touch Bridge (instant context menu detection for links, media, and images)
        try {
            addJavascriptInterface(touchBridge, OnyxTouchBridge.INTERFACE_NAME)
        } catch (_: Exception) {}

        // Onyx Shield & AdBlock Whitelist Bridge (synchronous checks at document-start)
        try {
            addJavascriptInterface(OnyxShieldBridge(context.applicationContext), "OnyxShieldBridge")
        } catch (_: Exception) {}

        // Native DOM Translation Bridge (CSP-immune page translation)
        try {
            val translateScope = (activity as? LifecycleOwner)?.lifecycleScope ?: webViewScope
            addJavascriptInterface(
                com.onyx.browser.web.translate.OnyxTranslateBridge(this, translateScope),
                com.onyx.browser.web.translate.OnyxTranslateBridge.INTERFACE_NAME
            )
        } catch (_: Exception) {}

        // Document-Start Adblock & Anti-Adblock Shields + WebAuthn Passkeys Polyfill + Media Playback
        try {
            val currentBlockingLevel = prefs.blockingLevel
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // Native Chrome environment polyfill (window.chrome, navigator.userAgentData, plugins, bridge masking)
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    ChromeEnvironmentBridge.SCRIPT,
                    setOf("*")
                )
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
                    WebGLCompatibilityBridge.SCRIPT,
                    setOf("*")
                )
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    OnyxTouchBridge.TOUCH_LISTENER_JS,
                    setOf("*")
                )
                // Always inject the media monitor: powers floating video menu pill, media session,
                // PiP video bounds, and OnyxMediaBridge events regardless of background-play setting.
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    MediaPlaybackManager.mediaMonitorScript,
                    setOf("*")
                )
                // Always inject the background playback suppression script at document start:
                // Pre-hooks visibilitychange listeners, ytcfg experiment flags, and unblocks background playback
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    MediaPlaybackManager.backgroundPlaybackScript,
                    setOf("*")
                )
                // Pre-buffer console logs and unhandled errors for DevTools
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    DevToolsManager.consoleBufferScript,
                    setOf("*")
                )
                // Desktop Mode Viewport Scaling & Client Hints spoofing
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    DesktopModeManager.DESKTOP_VIEWPORT_SCRIPT,
                    setOf("*")
                )
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                if (incognito) {
                    ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE_NAME)
                    WebViewCompat.setProfile(this, INCOGNITO_PROFILE_NAME)
                } else {
                    WebViewCompat.setProfile(this, Profile.DEFAULT_PROFILE_NAME)
                }
            } catch (e: Throwable) {
                android.util.Log.e("OnyxWebView", "Failed to set profile (incognito=$incognito)", e)
            }
        }
        if (incognito) {
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = true
            clearHistory()
            clearFormData()
        } else {
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.domStorageEnabled = true
        }
        // Apply cookie settings AFTER profile is set so cookieManager returns the correct profile manager
        applyCookieSettings()
    }

    private fun applyCookieSettings() {
        try {
            if (isIncognito) {
                // Incognito: always accept first-party cookies so login works.
                // Enable third-party cookies during active OAuth/auth flows (Facebook, Arkose,
                // checkpoint confirmation), block them when fully outside any auth session.
                cookieManager.setAcceptCookie(true)
                val allow3pIncognito = isPopupTab || isWithinOAuthGracePeriod()
                cookieManager.setAcceptThirdPartyCookies(this, allow3pIncognito)
            } else {
                val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
                when (prefs.cookieBlockingMode) {
                    com.onyx.browser.data.preferences.BrowserPreferences.COOKIE_BLOCK_ALL -> {
                        cookieManager.setAcceptCookie(false)
                    }
                    com.onyx.browser.data.preferences.BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY -> {
                        cookieManager.setAcceptCookie(true)
                        val allow3p = isPopupTab || isWithinOAuthGracePeriod()
                        cookieManager.setAcceptThirdPartyCookies(this, allow3p)
                    }
                    else -> {
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                    }
                }
            }
        } catch (_: Exception) {}
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
        val host = try { android.net.Uri.parse(urlString).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        val isAuthOrMeta = host == "facebook.com" || host.endsWith(".facebook.com") ||
                host == "meta.com" || host.endsWith(".meta.com") ||
                host == "fb.com" || host.endsWith(".fb.com") ||
                host == "fb.me" || host.endsWith(".fb.me") ||
                host == "messenger.com" || host.endsWith(".messenger.com") ||
                host == "instagram.com" || host.endsWith(".instagram.com") ||
                host == "threads.net" || host.endsWith(".threads.net") ||
                host.contains("arkose") || host.contains("funcaptcha") ||
                urlString.contains("checkpoint") || urlString.contains("/login")
        if (isAuthOrMeta) {
            lastOAuthInteractionTimestamp = System.currentTimeMillis()
            try {
                cookieManager.setAcceptThirdPartyCookies(this, true)
            } catch (_: Exception) {}
        }

        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        val wantsDesktop = key != null && prefs.desktopDomains.any { key == it || key.endsWith(".$it") }
        
        val targetUa = if (wantsDesktop) {
            if (prefs.userAgentSpoofTemplate in UserAgentManager.DESKTOP_KEYS) {
                getBaseUserAgent(prefs)
            } else {
                desktopUserAgent
            }
        } else {
            getBaseUserAgent(prefs)
        }

        val scalePercent = if (wantsDesktop) {
            DesktopModeManager.calculateDesktopScalePercent(context)
        } else {
            0
        }
        setInitialScale(scalePercent)
        
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        val currentUaMatchesTarget = settings.userAgentString == targetUa
        
        if (currentUaMatchesTarget) {
            if (forceRefreshLayout) {
                this.loadUrl(this.url ?: urlString)
            }
            return
        }
        
        settings.userAgentString = targetUa
        
        if (forceRefreshLayout) {
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
            if (com.onyx.browser.media.MediaPlaybackBridge.currentPlayingTabId == tabId ||
                com.onyx.browser.media.MediaPlaybackBridge.currentPlayingWebView?.get() == this) {
                com.onyx.browser.media.MediaPlaybackBridge.resetMediaPlayback(context)
            }
            evaluateJavascript("try { var v = document.querySelectorAll('video, audio'); for(var i=0; i<v.length; i++) { v[i].pause(); v[i].src = ''; } } catch(e){}", null)
            evaluateJavascript("try { " + com.onyx.browser.web.translate.PageTranslateManager.cleanupScript + " } catch(e){}", null)
            stopLoading()
            clearHistory()
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        } catch (_: Exception) {}
        webViewScope.cancel()
    }

    override fun loadUrl(url: String) {
        if (!isSyntheticOrDataUrl(url)) {
            pendingMainFrameUrl = url
            isMainFrameDocumentLoaded = false
        }
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
        if (!isSyntheticOrDataUrl(url)) {
            pendingMainFrameUrl = url
            isMainFrameDocumentLoaded = false
        }
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (prefs.isDoNotTrackEnabled) {
            additionalHttpHeaders["DNT"] = "1"
        }
        super.loadUrl(url, additionalHttpHeaders)
    }
}


