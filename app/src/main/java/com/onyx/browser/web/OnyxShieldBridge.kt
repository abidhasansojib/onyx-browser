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
    fun saveCustomCosmeticRule(domain: String?, selector: String?) {
        val d = domain?.trim() ?: return
        val s = selector?.trim() ?: return
        if (d.isBlank() || s.isBlank()) return

        val rule = "$d##$s"
        val existing = preferences.customFilterRules
        val lines = existing.lines().map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        if (lines.add(rule)) {
            preferences.customFilterRules = lines.joinToString("\n")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    com.onyx.browser.data.filter.FilterListManager.recompileFilters(context)
                } catch (_: Exception) {}
            }
            // Reset picker state and confirm to user on Main thread
            com.onyx.browser.ui.menu.ElementPickerManager.resetPickerState()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Element blocked: $s", Toast.LENGTH_SHORT).show()
            }
        }
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
        if (d.contains("recaptcha") || d.contains("hcaptcha") || d.contains("arkose") ||
            d.contains("turnstile") || d.contains("funcaptcha") || d.contains("datadome") ||
            d.contains("challenges.cloudflare.com") || u.contains("/cdn-cgi/") ||
            u.contains("/checkpoint/") || u.contains("/challenge/") ||
            d == "facebook.com" || d.endsWith(".facebook.com") || d == "fb.com" || d.endsWith(".fb.com") ||
            d == "facebook.net" || d.endsWith(".facebook.net") || d == "fbcdn.net" || d.endsWith(".fbcdn.net")
        ) {
            return false
        }
        val isAggressive = preferences.blockingLevel == BrowserPreferences.BLOCKING_AGGRESSIVE
        return com.onyx.browser.nativebridge.AdBlockEngine.shouldBlock(reqUrl, page, "other") ||
                AdBlockDomainManager.isBlockedInStandard(reqDomain) ||
                (isAggressive && AdBlockDomainManager.isBlockedInAggressive(reqDomain))
    }
}

