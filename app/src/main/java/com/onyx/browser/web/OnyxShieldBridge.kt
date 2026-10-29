package com.onyx.browser.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.onyx.browser.data.preferences.BrowserPreferences
import kotlinx.coroutines.launch


/**
 * Synchronous JavaScript interface for checking ad-blocker status at document-start.
 * Allows web pages to query whether ad blocking or cosmetic filters should be applied
 * for the current domain.
 */
class OnyxShieldBridge(
    private val context: Context,
    private val webViewRef: java.lang.ref.WeakReference<OnyxWebView>? = null
) {

    private val preferences = BrowserPreferences.getInstance(context)

    private fun isAuthOrMetaDomain(domain: String): Boolean {
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
                d == "github.com" || d.endsWith(".github.com") ||
                d == "githubassets.com" || d.endsWith(".githubassets.com") ||
                d == "githubusercontent.com" || d.endsWith(".githubusercontent.com") ||
                d.contains("arkose") || d.contains("funcaptcha") ||
                d.contains("recaptcha") || d.contains("hcaptcha") ||
                d.contains("turnstile") || d.contains("datadome") ||
                d.contains("perimeterx") || d.contains("kasada") ||
                d.contains("geetest") || d.contains("challenges.cloudflare.com")
    }

    @JavascriptInterface
    fun isAdBlockActive(domainOrUrl: String?): Boolean {
        if (!preferences.isAdBlockEnabled) return false
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return true
        if (preferences.isDomainWhitelisted(target)) return false
        val clean = preferences.cleanDomain(target)
        if (isAuthOrMetaDomain(clean)) return false
        return true
    }

    @JavascriptInterface
    fun isCosmeticFilteringActive(domainOrUrl: String?): Boolean {
        if (!preferences.isAdBlockEnabled || !preferences.isCosmeticFilteringEnabled) return false
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return true
        if (preferences.isDomainWhitelisted(target)) return false
        val clean = preferences.cleanDomain(target)
        if (isAuthOrMetaDomain(clean)) return false
        return true
    }

    @JavascriptInterface
    fun getBlockingLevel(): Int {
        return preferences.blockingLevel
    }

    @JavascriptInterface
    fun getBlockingLevelForDomain(domainOrUrl: String?): Int {
        return if (!domainOrUrl.isNullOrBlank()) {
            preferences.getEffectiveBlockingLevel(domainOrUrl)
        } else {
            preferences.blockingLevel
        }
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
    fun isStreamingSite(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return false
        val clean = preferences.cleanDomain(target)
        return OnyxWebViewClient.isStreamingOrMediaPage(target) || OnyxWebViewClient.isMediaOrStreamingHost(clean)
    }

    @JavascriptInterface
    fun isBettingOrAdUrl(url: String?): Boolean {
        val target = url?.takeIf { it.isNotBlank() } ?: return false
        val clean = preferences.cleanDomain(target)
        return OnyxWebViewClient.isBettingOrAdUrl(target, clean)
    }

    @JavascriptInterface
    fun isSocialMediaBlockingEnabled(): Boolean {
        return preferences.isSocialMediaBlockingEnabled
    }

    @JavascriptInterface
    fun isDesktopModeActive(): Boolean {
        return webViewRef?.get()?.isDesktopMode ?: preferences.isDesktopMode
    }

    @JavascriptInterface
    fun isDesktopModeActive(domainOrUrl: String?): Boolean {
        val wv = webViewRef?.get()
        if (wv != null) {
            return wv.isDesktopMode
        }
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return preferences.isDesktopMode
        return preferences.getEffectiveDesktopMode(target)
    }

    @JavascriptInterface
    fun isSensorsAllowed(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return !preferences.isSensorsBlockedGlobal
        return preferences.isWebSensorsAllowed(target) ?: !preferences.isSensorsBlockedGlobal
    }

    @JavascriptInterface
    fun isClipboardAllowed(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return !preferences.isClipboardBlockedGlobal
        return preferences.isWebClipboardAllowed(target) ?: !preferences.isClipboardBlockedGlobal
    }

    @JavascriptInterface
    fun isSoundAllowed(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return !preferences.isSoundMutedGlobal
        return preferences.isWebSoundAllowed(target) ?: !preferences.isSoundMutedGlobal
    }

    @JavascriptInterface
    fun isGoogleSignInAllowed(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return !preferences.isGoogleSignInBlockedGlobal
        return preferences.isWebGoogleSignInAllowed(target) ?: !preferences.isGoogleSignInBlockedGlobal
    }

    @JavascriptInterface
    fun isWakeLockAllowed(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return !preferences.isWakeLockBlockedGlobal
        return preferences.isWebWakeLockAllowed(target) ?: !preferences.isWakeLockBlockedGlobal
    }

    @JavascriptInterface
    fun isWebRtcProtected(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return preferences.isWebRtcProtectedGlobal
        return preferences.isWebRtcProtected(target)
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

    @JavascriptInterface
    fun getProceduralActions(pageUrl: String?): String {
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

        val rules = com.onyx.browser.nativebridge.AdBlockEngine.getProceduralRules(url)
        return org.json.JSONArray(rules).toString()
    }

    @JavascriptInterface
    fun getCosmeticCss(pageUrl: String?): String {
        if (!preferences.isAdBlockEnabled || !preferences.isCosmeticFilteringEnabled) return ""
        val url = pageUrl?.takeIf { it.isNotBlank() } ?: return ""
        val domain = preferences.cleanDomain(url)
        if (preferences.isDomainWhitelisted(domain)) return ""

        val d = domain.lowercase()
        val isMetaOrCaptcha = d == "facebook.com" || d.endsWith(".facebook.com") ||
                d == "fb.com" || d.endsWith(".fb.com") ||
                d == "messenger.com" || d.endsWith(".messenger.com") ||
                d == "instagram.com" || d.endsWith(".instagram.com") ||
                d.contains("arkose") || d.contains("recaptcha") ||
                d.contains("hcaptcha") || d.contains("turnstile") || d.contains("funcaptcha")
        if (isMetaOrCaptcha) return ""

        return com.onyx.browser.nativebridge.AdBlockEngine.getCosmeticCss(url)
    }

    @JavascriptInterface
    fun getScriptletJs(pageUrl: String?): String {
        if (!preferences.isAdBlockEnabled || !preferences.isCosmeticFilteringEnabled) return ""
        val url = pageUrl?.takeIf { it.isNotBlank() } ?: return ""
        val domain = preferences.cleanDomain(url)
        if (preferences.isDomainWhitelisted(domain)) return ""

        val d = domain.lowercase()
        val isMetaOrCaptcha = d == "facebook.com" || d.endsWith(".facebook.com") ||
                d == "fb.com" || d.endsWith(".fb.com") ||
                d == "messenger.com" || d.endsWith(".messenger.com") ||
                d == "instagram.com" || d.endsWith(".instagram.com") ||
                d.contains("arkose") || d.contains("recaptcha") ||
                d.contains("hcaptcha") || d.contains("turnstile") || d.contains("funcaptcha")
        if (isMetaOrCaptcha) return ""

        return com.onyx.browser.nativebridge.AdBlockEngine.getScriptletJs(url)
    }

    @JavascriptInterface
    fun getCspDirectives(pageUrl: String?): String {
        if (!preferences.isAdBlockEnabled) return ""
        val url = pageUrl?.takeIf { it.isNotBlank() } ?: return ""
        val domain = preferences.cleanDomain(url)
        if (preferences.isDomainWhitelisted(domain)) return ""

        val d = domain.lowercase()
        val isMetaOrCaptcha = d == "facebook.com" || d.endsWith(".facebook.com") ||
                d == "fb.com" || d.endsWith(".fb.com") ||
                d == "messenger.com" || d.endsWith(".messenger.com") ||
                d == "instagram.com" || d.endsWith(".instagram.com") ||
                d.contains("arkose") || d.contains("recaptcha") ||
                d.contains("hcaptcha") || d.contains("turnstile") || d.contains("funcaptcha")
        if (isMetaOrCaptcha) return ""

        val rawCsp = com.onyx.browser.nativebridge.AdBlockEngine.getCspDirectives(
            url = url,
            sourceUrl = url,
            resourceType = com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_MAIN_FRAME
        ) ?: return ""

        // Sanitize directives for <meta http-equiv="Content-Security-Policy">:
        // 1. Strip directives ignored/invalid inside <meta> tags (sandbox, frame-ancestors, report-uri).
        // 2. Rewrite "worker-src 'none'" to "worker-src blob:" so https: ServiceWorkers remain blocked
        //    while in-memory MSE blob: Web Workers (hls.js, dash.js, mpegts.js) can demux video streams.
        return rawCsp.split(';')
            .map { it.trim() }
            .filter { directive ->
                directive.isNotEmpty() &&
                !directive.startsWith("sandbox", ignoreCase = true) &&
                !directive.startsWith("frame-ancestors", ignoreCase = true) &&
                !directive.startsWith("report-uri", ignoreCase = true)
            }
            .joinToString("; ") { directive ->
                if (directive.matches(WORKER_SRC_NONE_REGEX)) {
                    "worker-src blob:"
                } else {
                    directive
                }
            }
    }

    @JavascriptInterface
    fun getCustomBlockedCss(pageUrl: String?): String {
        val url = pageUrl?.takeIf { it.isNotBlank() } ?: return ""
        val d = preferences.cleanDomain(url).lowercase()
        // Do not inject custom rules into anti-bot/challenge subframes
        if (d.contains("challenges.cloudflare.com") || d.contains("turnstile") ||
            d.contains("recaptcha") || d.contains("hcaptcha") || d.contains("arkose")) {
            return ""
        }
        return preferences.getCustomBlockedCssForDomain(url)
    }

    companion object {
        private val DANGEROUS_CSS_PATTERN = Regex("[{}<>;@\\\\]|url\\s*\\(|expression\\s*\\(|javascript\\s*:|-moz-binding", RegexOption.IGNORE_CASE)
        private val WORKER_SRC_NONE_REGEX = Regex("^worker-src\\s+'none'\\s*$", RegexOption.IGNORE_CASE)

        private fun isMediaStreamPath(lowerUrl: String): Boolean {
            val clean = lowerUrl.substringBefore('?').substringBefore('#')
            return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".ts") ||
                    clean.endsWith(".m2ts") || clean.endsWith(".m4s") || clean.endsWith(".cmfv") ||
                    clean.endsWith(".cmfa") || clean.endsWith(".fmp4") || clean.endsWith(".mp4") ||
                    clean.endsWith(".m4v") || clean.endsWith(".webm") || clean.endsWith(".mkv") ||
                    clean.endsWith(".ogv") || clean.endsWith(".ogg") || clean.endsWith(".mp3") ||
                    clean.endsWith(".m4a") || clean.endsWith(".aac") || clean.endsWith(".opus") ||
                    clean.endsWith(".flac") || clean.endsWith(".wav") || clean.endsWith(".flv") ||
                    clean.endsWith(".vtt") || clean.endsWith(".srt") || clean.endsWith(".ass")
        }

        private fun isVastAdTagUrl(lowerUrl: String): Boolean {
            val clean = lowerUrl.substringBefore('?').substringBefore('#')
            if (clean.endsWith(".js") || clean.endsWith(".mjs") || clean.endsWith(".css") ||
                clean.endsWith(".html") || clean.endsWith(".htm") || clean.endsWith(".png") ||
                clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".gif") ||
                clean.endsWith(".svg") || clean.endsWith(".webp")
            ) {
                return false
            }
            return lowerUrl.contains("vast") || lowerUrl.contains("vmap") ||
                    lowerUrl.contains("/pagead/ads?") || lowerUrl.contains("doubleclick.net/gampad/") ||
                    lowerUrl.contains("ad-vast") || lowerUrl.contains("spotxchange") ||
                    lowerUrl.contains("fwmrm.net/ad/")
        }
    }

    @JavascriptInterface
    fun saveCustomCosmeticRules(domain: String?, selectorsJson: String?) {
        // Enforce that the native Element Picker is active
        if (!com.onyx.browser.ui.menu.ElementPickerManager.isPickerActive) return

        val rawDomain = domain?.trim() ?: return
        val cleanD = preferences.cleanDomain(rawDomain).lowercase()
        if (cleanD.isBlank() || cleanD.contains("/") || cleanD.contains(":") || cleanD.contains("\\")) return

        val json = selectorsJson?.trim() ?: return
        if (json.isBlank()) return

        try {
            val jsonArray = org.json.JSONArray(json)
            val selectors = mutableListOf<String>()
            val maxCount = jsonArray.length().coerceAtMost(50)
            for (i in 0 until maxCount) {
                val s = jsonArray.optString(i)?.trim()
                if (!s.isNullOrBlank() && s.length in 1..500 && !DANGEROUS_CSS_PATTERN.containsMatchIn(s)) {
                    selectors.add(s)
                }
            }
            if (selectors.isEmpty()) return

            preferences.isBlockElementEnabled = true
            preferences.addCustomBlockedSelectors(cleanD, selectors)

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    com.onyx.browser.data.filter.FilterListManager.recompileFilters(context)
                } catch (_: Exception) {}
            }

            com.onyx.browser.ui.menu.ElementPickerManager.resetPickerState()
            Handler(Looper.getMainLooper()).post {
                val msg = if (selectors.size == 1) {
                    "Blocked 1 element on $cleanD"
                } else {
                    "Blocked ${selectors.size} elements on $cleanD"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("OnyxShieldBridge", "Error saving custom cosmetic rules", e)
        }
    }

    @JavascriptInterface
    fun saveCustomCosmeticRule(domain: String?, selector: String?) {
        val s = selector?.trim() ?: return
        if (s.isBlank() || s.length > 500 || DANGEROUS_CSS_PATTERN.containsMatchIn(s)) return
        val escaped = org.json.JSONObject.quote(s)
        saveCustomCosmeticRules(domain, "[$escaped]")
    }

    @JavascriptInterface
    fun onPickerClosed() {
        com.onyx.browser.ui.menu.ElementPickerManager.resetPickerState()
    }

    @JavascriptInterface
    fun isUrlBlocked(url: String?, pageUrl: String?): Boolean {
        return isUrlBlocked(url, pageUrl, "other")
    }

    @JavascriptInterface
    fun isUrlBlocked(url: String?, pageUrl: String?, resourceType: String?): Boolean {
        if (!preferences.isAdBlockEnabled) return false
        val reqUrl = url?.takeIf { it.isNotBlank() } ?: return false
        if (reqUrl.startsWith("blob:", ignoreCase = true) ||
            reqUrl.startsWith("data:", ignoreCase = true) ||
            reqUrl.startsWith("javascript:", ignoreCase = true)
        ) {
            return false
        }
        val page = pageUrl?.takeIf { it.isNotBlank() } ?: ""
        if (page.isNotBlank() && preferences.isDomainWhitelisted(page)) return false
        if (preferences.isDomainWhitelisted(reqUrl)) return false

        val reqDomain = preferences.cleanDomain(reqUrl)
        val d = reqDomain.lowercase()
        val u = reqUrl.lowercase()
        // Never block CAPTCHAs, bot challenges, or auth endpoints
        if (isAuthOrMetaDomain(d) || u.contains("/cdn-cgi/") ||
            u.contains("/checkpoint/") || u.contains("/challenge/") ||
            u.contains("lsd=") || u.contains("jazoest=") || u.contains("datr=")
        ) {
            return false
        }

        val effectiveType = resourceType?.takeIf { it.isNotBlank() }?.lowercase() ?: "other"
        val isXhrCheck = effectiveType == "xhr" || effectiveType == "xmlhttprequest"
        if (isXhrCheck) {
            // Let media streams and VAST/VMAP ad tags flow through to OnyxWebViewClient.shouldInterceptRequest
            // so media uses RESOURCE_TYPE_MEDIA and VAST tags receive HTTP 200 empty <VAST/> XML instead of JS TypeError.
            if (isMediaStreamPath(u) || isVastAdTagUrl(u)) return false
        }

        val typeInt = when (effectiveType) {
            "script" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_SCRIPT
            "image" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_IMAGE
            "stylesheet" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_STYLESHEET
            "sub_frame", "subdocument" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_SUB_FRAME
            "xhr", "xmlhttprequest" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_XHR
            "media" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_MEDIA
            "main_frame", "document" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_MAIN_FRAME
            "font" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_FONT
            "ping", "beacon" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_PING
            "websocket" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_WEBSOCKET
            "object" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_OBJECT
            "popup" -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_POPUP
            else -> com.onyx.browser.nativebridge.AdBlockEngine.RESOURCE_TYPE_OTHER
        }

        val uncloakedUrl = AdBlockDomainManager.uncloakUrl(reqUrl)
        var engineResult = com.onyx.browser.nativebridge.AdBlockEngine.checkRequest(uncloakedUrl, page, typeInt, "GET")
        if (!engineResult.shouldBlock && uncloakedUrl != reqUrl) {
            val originalResult = com.onyx.browser.nativebridge.AdBlockEngine.checkRequest(reqUrl, page, typeInt, "GET")
            if (originalResult.shouldBlock || !originalResult.redirectData.isNullOrBlank()) {
                engineResult = originalResult
            }
        }

        // If a $redirect surrogate exists (e.g., noopjson, noopvast-2.0, noopjs, google-ima.js),
        // do not reject in the JS fetch/XHR proxy — allow OnyxWebViewClient.shouldInterceptRequest
        // to intercept and serve the surrogate with HTTP 200 OK.
        if (isXhrCheck && !engineResult.redirectData.isNullOrBlank()) {
            return false
        }

        return engineResult.shouldBlock
    }
}

