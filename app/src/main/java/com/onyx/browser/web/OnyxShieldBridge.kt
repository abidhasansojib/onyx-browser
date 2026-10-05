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
class OnyxShieldBridge(private val context: Context) {

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
    fun isDesktopModeActive(domainOrUrl: String?): Boolean {
        val target = domainOrUrl?.takeIf { it.isNotBlank() } ?: return false
        val clean = preferences.cleanDomain(target)
        val desktopDomains = preferences.desktopDomains
        return desktopDomains.any { clean == it || clean.endsWith(".$it") }
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
        if (!preferences.isAdBlockEnabled) return false
        val reqUrl = url?.takeIf { it.isNotBlank() } ?: return false
        val page = pageUrl?.takeIf { it.isNotBlank() } ?: ""
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
        val isAggressive = preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE
        return com.onyx.browser.nativebridge.AdBlockEngine.shouldBlock(reqUrl, page, "other") ||
                AdBlockDomainManager.isBlockedInStandard(reqDomain) ||
                (isAggressive && AdBlockDomainManager.isBlockedInAggressive(reqDomain))
    }
}

