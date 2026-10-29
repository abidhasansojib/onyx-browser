package com.onyx.browser.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.model.QuickActionItem
import com.onyx.browser.data.model.ShortcutItem
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

enum class SitePermissionState {
    ALLOWED,
    BLOCKED,
    ASK
}

class BrowserPreferences private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _blockedRequestsFlow = MutableStateFlow(getBlockedRequestsCount())
    val blockedRequestsFlow: StateFlow<Long> = _blockedRequestsFlow.asStateFlow()

    private val _shortcutsFlow = MutableStateFlow<List<ShortcutItem>>(emptyList())
    val shortcutsFlow: StateFlow<List<ShortcutItem>> = _shortcutsFlow.asStateFlow()


    init {
        _shortcutsFlow.value = getShortcuts()
        migrateDefaultSearchEngine()
        migrateAutoRedirectDefaults()
    }

    private fun migrateDefaultSearchEngine() {
        if (!prefs.getBoolean(KEY_DEFAULT_ENGINE_MIGRATED, false)) {
            val current = prefs.getString(KEY_SEARCH_ENGINE, null)
            if (current == null || current == "brave") {
                prefs.edit()
                    .putString(KEY_SEARCH_ENGINE, SearchEngine.GOOGLE.id)
                    .putBoolean(KEY_DEFAULT_ENGINE_MIGRATED, true)
                    .apply()
            } else {
                prefs.edit().putBoolean(KEY_DEFAULT_ENGINE_MIGRATED, true).apply()
            }
        }
    }

    private fun migrateAutoRedirectDefaults() {
        if (!prefs.getBoolean(KEY_AUTO_REDIRECT_DEFAULTS_MIGRATED, false)) {
            val editor = prefs.edit()
            if (!prefs.contains(KEY_AUTO_REDIRECT_AMP)) {
                editor.putBoolean(KEY_AUTO_REDIRECT_AMP, true)
            }
            if (!prefs.contains(KEY_AUTO_REDIRECT_TRACKING)) {
                editor.putBoolean(KEY_AUTO_REDIRECT_TRACKING, true)
            }
            editor.putBoolean(KEY_AUTO_REDIRECT_DEFAULTS_MIGRATED, true).apply()
        }
    }

    private val blockedCounter = AtomicLong(getBlockedRequestsCount())

    var searchEngine: SearchEngine
        get() {
            val id = prefs.getString(KEY_SEARCH_ENGINE, SearchEngine.GOOGLE.id)
            return SearchEngine.fromId(id, getCustomSearchEngines())
        }
        set(value) {
            prefs.edit().putString(KEY_SEARCH_ENGINE, value.id).apply()
        }

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
        }

    var accentColorPreset: String
        get() = prefs.getString(KEY_ACCENT_COLOR_PRESET, ACCENT_PRESET_CRIMSON) ?: ACCENT_PRESET_CRIMSON
        set(value) {
            prefs.edit().putString(KEY_ACCENT_COLOR_PRESET, value).apply()
        }

    var accentColorHex: String
        get() = prefs.getString(KEY_ACCENT_COLOR_HEX, DEFAULT_ACCENT_HEX) ?: DEFAULT_ACCENT_HEX
        set(value) {
            val formatted = if (value.startsWith("#")) value.uppercase() else "#${value.uppercase()}"
            prefs.edit().putString(KEY_ACCENT_COLOR_HEX, formatted).apply()
        }

    // ── Ad & Tracker Blocking ────────────────────────────────────────────────

    var isAdBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADBLOCK_ENABLED, value).apply()
            com.onyx.browser.nativebridge.AdBlockEngine.clearDecisionCache()
        }

    /**
     * Blocking aggressiveness level:
     *   BLOCKING_STANDARD (0)   — blocks third-party ads & trackers (default, Brave Standard)
     *   BLOCKING_AGGRESSIVE (1) — blocks all ads & trackers incl. first-party (Brave Aggressive)
     */
    var blockingLevel: Int
        get() = prefs.getInt(KEY_BLOCKING_LEVEL, BLOCKING_STANDARD)
        set(value) {
            prefs.edit().putInt(KEY_BLOCKING_LEVEL, value).apply()
            com.onyx.browser.nativebridge.AdBlockEngine.clearDecisionCache()
        }

    var isCosmeticFilteringEnabled: Boolean
        get() = prefs.getBoolean(KEY_COSMETIC_FILTERING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_COSMETIC_FILTERING, value).apply()
        }

    // ── Fingerprint Protection ───────────────────────────────────────────────

    /**
     * When enabled, injects a JS snippet that overrides Canvas, AudioContext, WebGL,
     * hardwareConcurrency, deviceMemory, and screen dimension APIs with slightly-randomised
     * values to frustrate cross-site fingerprinting — similar to Brave's approach.
     */
    var isFingerprintProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT_PROTECTION, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FINGERPRINT_PROTECTION, value).apply()
        }

    // ── HTTPS Upgrade ────────────────────────────────────────────────────────

    /**
     * When enabled, http:// main-frame navigations are automatically upgraded to https://.
     * Implemented in OnyxWebViewClient.shouldOverrideUrlLoading.
     */
    var isHttpsUpgradeEnabled: Boolean
        get() = httpsUpgradeMode != HTTPS_MODE_DISABLED
        set(value) {
            httpsUpgradeMode = if (value) HTTPS_MODE_WHEN_POSSIBLE else HTTPS_MODE_DISABLED
            prefs.edit().putBoolean(KEY_HTTPS_UPGRADE, value).apply()
        }

    var isBlockThirdPartyCookiesEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, value).apply()

    var isDoNotTrackEnabled: Boolean
        get() = prefs.getBoolean(KEY_DO_NOT_TRACK, true)
        set(value) = prefs.edit().putBoolean(KEY_DO_NOT_TRACK, value).apply()

    // ── AMP Redirect ─────────────────────────────────────────────────────────
    var isAutoRedirectAmpEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REDIRECT_AMP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REDIRECT_AMP, value).apply()

    // ── Tracking URL Redirect ─────────────────────────────────────────────────
    var isAutoRedirectTrackingUrlsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REDIRECT_TRACKING, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REDIRECT_TRACKING, value).apply()

    // ── HTTPS Upgrade Mode ────────────────────────────────────────────────────
    // 0 = Disabled, 1 = When Possible (default), 2 = Strict (fail if HTTPS unavailable)
    var httpsUpgradeMode: Int
        get() = prefs.getInt(KEY_HTTPS_UPGRADE_MODE, HTTPS_MODE_WHEN_POSSIBLE)
        set(value) = prefs.edit().putInt(KEY_HTTPS_UPGRADE_MODE, value).apply()

    // ── Global Script Blocking / JavaScript Execution ─────────────────────────
    var isGlobalScriptBlockingEnabled: Boolean
        get() = !isJavaScriptEnabledGlobal
        set(value) {
            isJavaScriptEnabledGlobal = !value
        }

    // ── Cookie Blocking Mode ──────────────────────────────────────────────────
    // 0 = Allow All, 1 = Block Third-Party (default, Brave parity), 2 = Block All
    var cookieBlockingMode: Int
        get() = prefs.getInt(KEY_COOKIE_BLOCKING_MODE, COOKIE_BLOCK_THIRD_PARTY)
        set(value) = prefs.edit().putInt(KEY_COOKIE_BLOCKING_MODE, value).apply()

    // ── Fingerprint via Language ──────────────────────────────────────────────
    var isFingerprintLangEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT_LANG, true)
        set(value) = prefs.edit().putBoolean(KEY_FINGERPRINT_LANG, value).apply()

    // ── Custom Filter Rules ───────────────────────────────────────────────────
    private val domainCustomRulesCache = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var isDomainRulesCacheInitialized = false

    private fun ensureDomainRulesCache() {
        if (isDomainRulesCacheInitialized) return
        synchronized(domainCustomRulesCache) {
            if (isDomainRulesCacheInitialized) return
            domainCustomRulesCache.clear()
            val raw = prefs.getString(KEY_CUSTOM_FILTER_RULES, "") ?: ""
            if (raw.isNotBlank()) {
                raw.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("!") && !trimmed.startsWith("[")) {
                        val separator = if (trimmed.contains("##")) "##" else if (trimmed.contains("#?#")) "#?#" else null
                        if (separator != null) {
                            val parts = trimmed.split(separator, limit = 2)
                            if (parts.size == 2) {
                                val domains = parts[0].split(",")
                                val selector = parts[1].trim()
                                if (selector.isNotBlank()) {
                                    for (d in domains) {
                                        val cleanD = cleanDomain(d).lowercase().removePrefix("||")
                                        if (cleanD.isNotBlank()) {
                                            domainCustomRulesCache.getOrPut(cleanD) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(selector)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            isDomainRulesCacheInitialized = true
        }
    }

    var customFilterRules: String
        get() = prefs.getString(KEY_CUSTOM_FILTER_RULES, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_FILTER_RULES, value).apply()
            synchronized(domainCustomRulesCache) {
                isDomainRulesCacheInitialized = false
            }
        }

    fun addCustomBlockedSelectors(domain: String, selectors: List<String>) {
        if (selectors.isEmpty()) return
        val cleanD = cleanDomain(domain).lowercase()
        if (cleanD.isBlank()) return

        ensureDomainRulesCache()
        val domainSet = domainCustomRulesCache.getOrPut(cleanD) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        val newRules = mutableListOf<String>()
        for (sel in selectors) {
            val trimmedSel = sel.trim()
            if (trimmedSel.isNotBlank() && domainSet.add(trimmedSel)) {
                newRules.add("$cleanD##$trimmedSel")
            }
        }

        if (newRules.isNotEmpty()) {
            val existing = customFilterRules
            val sb = StringBuilder(existing)
            if (existing.isNotBlank() && !existing.endsWith("\n")) {
                sb.append("\n")
            }
            sb.append(newRules.joinToString("\n")).append("\n")
            prefs.edit().putString(KEY_CUSTOM_FILTER_RULES, sb.toString()).apply()
        }
    }

    fun getCustomBlockedSelectorsForDomain(domainOrUrl: String): List<String> {
        if (!isBlockElementEnabled) return emptyList()

        val cleanD = cleanDomain(domainOrUrl).lowercase()
        if (cleanD.isBlank()) return emptyList()

        ensureDomainRulesCache()
        val result = mutableSetOf<String>()

        domainCustomRulesCache[cleanD]?.let { result.addAll(it) }

        var dotIdx = cleanD.indexOf('.')
        while (dotIdx != -1 && dotIdx < cleanD.length - 1) {
            val parentDomain = cleanD.substring(dotIdx + 1)
            if (parentDomain.contains('.')) {
                domainCustomRulesCache[parentDomain]?.let { result.addAll(it) }
            }
            dotIdx = cleanD.indexOf('.', dotIdx + 1)
        }

        return result.toList()
    }

    fun getCustomBlockedCssForDomain(domainOrUrl: String): String {
        if (!isBlockElementEnabled) return ""
        val selectors = getCustomBlockedSelectorsForDomain(domainOrUrl)
        if (selectors.isEmpty()) return ""
        return selectors.joinToString(", ") + " { display: none !important; }"
    }

    fun clearCustomBlockedRulesForDomain(domain: String) {
        val cleanD = cleanDomain(domain).lowercase()
        if (cleanD.isBlank()) return
        ensureDomainRulesCache()
        domainCustomRulesCache.remove(cleanD)

        val existing = customFilterRules
        val updatedLines = existing.lineSequence().filterNot { line ->
            val trimmed = line.trim()
            (trimmed.startsWith("$cleanD##") || trimmed.startsWith("$cleanD#?#"))
        }.joinToString("\n")
        prefs.edit().putString(KEY_CUSTOM_FILTER_RULES, updatedLines).apply()
    }

    fun clearAllCustomBlockedRules() {
        synchronized(domainCustomRulesCache) {
            domainCustomRulesCache.clear()
            isDomainRulesCacheInitialized = true
        }
        prefs.edit()
            .remove(KEY_CUSTOM_FILTER_RULES)
            .remove(KEY_BLOCK_ELEMENT_ENABLED)
            .apply()
    }

    // ── Filter List Subscriptions (comma-separated keys) ─────────────────────
    var enabledFilterLists: Set<String>
        get() = prefs.getStringSet(KEY_ENABLED_FILTER_LISTS, DEFAULT_FILTER_LISTS) ?: DEFAULT_FILTER_LISTS
        set(value) = prefs.edit().putStringSet(KEY_ENABLED_FILTER_LISTS, value).apply()

    var hasCheckedDefaultRegion: Boolean
        get() = prefs.getBoolean(KEY_CHECKED_DEFAULT_REGION, false)
        set(value) = prefs.edit().putBoolean(KEY_CHECKED_DEFAULT_REGION, value).apply()

    // ── Filter List Auto-Update ────────────────────────────────────────────────
    var isFilterAutoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILTER_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_AUTO_UPDATE, value).apply()

    var filterAutoUpdateIntervalHours: Int
        get() = prefs.getInt(KEY_FILTER_UPDATE_INTERVAL, 24)
        set(value) = prefs.edit().putInt(KEY_FILTER_UPDATE_INTERVAL, value).apply()

    // ── Element Blocking in Private Windows ──────────────────────────────────
    var isElementBlockingInPrivateEnabled: Boolean
        get() = prefs.getBoolean(KEY_ELEMENT_BLOCKING_PRIVATE, true)
        set(value) = prefs.edit().putBoolean(KEY_ELEMENT_BLOCKING_PRIVATE, value).apply()

    // ── Social Media Blocking ─────────────────────────────────────────────────
    var isSocialMediaBlockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOCIAL_MEDIA_BLOCKING, true)
        set(value) = prefs.edit().putBoolean(KEY_SOCIAL_MEDIA_BLOCKING, value).apply()

    var allowFacebookLogins: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_FB_LOGINS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_FB_LOGINS, value).apply()

    var allowTwitterEmbeds: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_TWITTER_EMBEDS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_TWITTER_EMBEDS, value).apply()

    var allowLinkedInEmbeds: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_LINKEDIN_EMBEDS, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_LINKEDIN_EMBEDS, value).apply()

    // ── Open Links in App ─────────────────────────────────────────────────────
    var isOpenLinksInAppEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPEN_LINKS_IN_APP, true)
        set(value) = prefs.edit().putBoolean(KEY_OPEN_LINKS_IN_APP, value).apply()

    // ── Secure DNS ────────────────────────────────────────────────────────────
    var isSecureDnsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SECURE_DNS, false)
        set(value) = prefs.edit().putBoolean(KEY_SECURE_DNS, value).apply()

    var secureDnsProvider: String
        get() = prefs.getString(KEY_SECURE_DNS_PROVIDER, "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query"
        set(value) = prefs.edit().putString(KEY_SECURE_DNS_PROVIDER, value).apply()

    // ── Block Switch-to-App Banners ───────────────────────────────────────────
    var isBlockAppBannerEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_APP_BANNER, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_APP_BANNER, value).apply()

    // ── Last Active Tab ID ──────────────────────────────────────────────────
    var lastActiveTabId: String?
        get() = prefs.getString(KEY_LAST_ACTIVE_TAB_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_ACTIVE_TAB_ID, value).apply()

    // ── Clear Browsing Data on Exit ──────────────────────────────────────────
    var isClearOnExitEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_ON_EXIT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLEAR_ON_EXIT, value).apply()

    // ── Pull to Refresh ──────────────────────────────────────────────────────
    var isPullToRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_PULL_TO_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_PULL_TO_REFRESH, value).apply()

    // ── Force Dark Mode for Web Contents ─────────────────────────────────────
    var isForceDarkModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DARK_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_DARK_MODE, value).apply()

    // ── Address Bar Customization ───────────────────────────────────────────
    var isHomeReplacedWithRefresh: Boolean
        get() = prefs.getBoolean(KEY_REPLACE_HOME_WITH_REFRESH, false)
        set(value) = prefs.edit().putBoolean(KEY_REPLACE_HOME_WITH_REFRESH, value).apply()

    // ── Anti-Adblock Detection Spoofing ──────────────────────────────────────
    /**
     * When enabled (Adblocker Spoofing), injects JS stubs (adsbygoogle.loaded, window.ga, window.gtag, fbq, etc.)
     * and synthesizes safe 200 OK stubs for blocked ad/tracker network requests, preventing
     * "Please disable your adblocker" overlays and script crash walls.
     * Disabled by default so standard adblocking (403 Forbidden on ads) works and adblock-test sites report correctly.
     */
    var isAntiAdblockDetectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_ADBLOCK_DETECTION, false)
        set(value) = prefs.edit().putBoolean(KEY_ANTI_ADBLOCK_DETECTION, value).apply()

    var isAdblockerSpoofingEnabled: Boolean
        get() = isAntiAdblockDetectionEnabled
        set(value) { isAntiAdblockDetectionEnabled = value }

    // ── Per-domain Script Blocking ───────────────────────────────────────────

    @Volatile
    private var cachedScriptBlockingDomains: Set<String>? = null

    fun isScriptBlockingEnabledForDomain(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return false
        val set = cachedScriptBlockingDomains ?: run {
            val s = prefs.getStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, emptySet()) ?: emptySet()
            cachedScriptBlockingDomains = s
            s
        }
        return set.contains(domain)
    }

    fun setScriptBlockingForDomain(domainOrUrl: String, block: Boolean) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val current = prefs.getStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (block) current.add(domain) else current.remove(domain)
        cachedScriptBlockingDomains = current

        val a = prefs.getStringSet(KEY_ALLOWED_JS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_JS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (block) {
            b.add(domain)
            a.remove(domain)
        } else {
            b.remove(domain)
        }
        cachedAllowedJsDomains = a
        cachedBlockedJsDomains = b

        prefs.edit()
            .putStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, current)
            .putStringSet(KEY_ALLOWED_JS_DOMAINS, a)
            .putStringSet(KEY_BLOCKED_JS_DOMAINS, b)
            .apply()
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

    var downloadManagerBehavior: Int
        get() = prefs.getInt("download_manager_behavior", 0) // 0: Ask, 1: Internal, 2: External
        set(value) {
            prefs.edit().putInt("download_manager_behavior", value).apply()
        }

    var isDownloadWifiOnly: Boolean
        get() = prefs.getBoolean("download_wifi_only", false)
        set(value) {
            prefs.edit().putBoolean("download_wifi_only", value).apply()
        }

    var downloadSpeedLimit: Long
        get() = prefs.getLong("download_speed_limit", 0L) // 0 = Unlimited, or bytes/sec
        set(value) {
            prefs.edit().putLong("download_speed_limit", value).apply()
        }

    var isBackgroundPlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_PLAY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BACKGROUND_PLAY, value).apply()
        }

    var isPipEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIP_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PIP_ENABLED, value).apply()
        }


    var isDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()
        }

    var isJavaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JAVASCRIPT_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_JAVASCRIPT_ENABLED, value).apply()
        }

    var filterLastUpdatedTime: Long
        get() = prefs.getLong(KEY_FILTER_UPDATED, 0L)
        set(value) {
            prefs.edit().putLong(KEY_FILTER_UPDATED, value).apply()
        }

    var compiledFilterSignature: String
        get() = prefs.getString(KEY_COMPILED_FILTER_SIGNATURE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COMPILED_FILTER_SIGNATURE, value).apply()

    @Volatile
    private var cachedDesktopDomains: Set<String>? = null
    @Volatile
    private var cachedMobileDomains: Set<String>? = null

    var desktopDomains: Set<String>
        get() = cachedDesktopDomains ?: run {
            val raw = prefs.getStringSet(KEY_DESKTOP_DOMAINS, emptySet()) ?: emptySet()
            val canonical = raw.mapNotNull { getCanonicalDesktopKey(it).takeIf { k -> k.isNotBlank() } }.toSet()
            cachedDesktopDomains = canonical
            canonical
        }
        set(value) {
            val canonical = value.mapNotNull { getCanonicalDesktopKey(it).takeIf { k -> k.isNotBlank() } }.toSet()
            cachedDesktopDomains = canonical
            prefs.edit().putStringSet(KEY_DESKTOP_DOMAINS, canonical).apply()
        }

    var mobileDomains: Set<String>
        get() = cachedMobileDomains ?: run {
            val raw = prefs.getStringSet(KEY_MOBILE_DOMAINS, emptySet()) ?: emptySet()
            val canonical = raw.mapNotNull { getCanonicalDesktopKey(it).takeIf { k -> k.isNotBlank() } }.toSet()
            cachedMobileDomains = canonical
            canonical
        }
        set(value) {
            val canonical = value.mapNotNull { getCanonicalDesktopKey(it).takeIf { k -> k.isNotBlank() } }.toSet()
            cachedMobileDomains = canonical
            prefs.edit().putStringSet(KEY_MOBILE_DOMAINS, canonical).apply()
        }

    var targetTranslateLanguage: String
        get() = prefs.getString(KEY_TRANSLATE_TARGET_LANG, java.util.Locale.getDefault().language.ifBlank { "en" }) ?: "en"
        set(value) {
            prefs.edit().putString(KEY_TRANSLATE_TARGET_LANG, value).apply()
        }

    var targetTranslateLanguageName: String
        get() = prefs.getString(KEY_TRANSLATE_TARGET_NAME, java.util.Locale.getDefault().displayLanguage.ifBlank { "English" }) ?: "English"
        set(value) {
            prefs.edit().putString(KEY_TRANSLATE_TARGET_NAME, value).apply()
        }

    @Volatile
    private var cachedWhitelist: Set<String>? = null

    fun cleanDomain(domainOrUrl: String): String {
        val trimmed = domainOrUrl.trim()
        if (trimmed.isBlank()) return ""
        val startIdx = if (trimmed.startsWith("http://", ignoreCase = true)) {
            7
        } else if (trimmed.startsWith("https://", ignoreCase = true)) {
            8
        } else {
            val schemeIdx = trimmed.indexOf("://")
            if (schemeIdx != -1) schemeIdx + 3 else 0
        }
        var endIdx = trimmed.length
        for (i in startIdx until trimmed.length) {
            val c = trimmed[i]
            if (c == '/' || c == ':' || c == '?' || c == '#') {
                endIdx = i
                break
            }
        }
        val host = if (startIdx < endIdx) trimmed.substring(startIdx, endIdx) else trimmed
        val clean = host.lowercase()
        return if (clean.startsWith("www.")) clean.substring(4) else clean
    }

    /**
     * Strips common mobile subdomains ('m.', 'mobile.', 'touch.', 'wap.', '*.m.*')
     * to obtain the canonical site domain.
     */
    fun stripMobilePrefix(domain: String): String {
        var d = domain.trim().lowercase()
        if (d.startsWith("www.")) {
            d = d.substring(4)
        }
        if (d.startsWith("m.")) {
            d = d.substring(2)
        } else if (d.startsWith("mobile.")) {
            d = d.substring(7)
        } else if (d.startsWith("touch.")) {
            d = d.substring(6)
        } else if (d.startsWith("wap.")) {
            d = d.substring(4)
        }
        if (d.contains(".m.")) {
            d = d.replace(".m.", ".")
        }
        if (d.contains(".mobile.")) {
            d = d.replace(".mobile.", ".")
        }
        if (d.startsWith("www.")) {
            d = d.substring(4)
        }
        return d
    }

    /**
     * Resolves the registrable / apex base domain of a host (e.g. 'm.facebook.com' -> 'facebook.com',
     * 'en.m.wikipedia.org' -> 'wikipedia.org', 'm.bbc.co.uk' -> 'bbc.co.uk').
     */
    fun getBaseDomain(domainOrUrl: String): String {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return ""
        val parts = domain.split('.')
        if (parts.size <= 2) return domain

        val tld = parts[parts.size - 1]
        val sld = parts[parts.size - 2]
        val commonCcSlds = setOf("co", "com", "org", "net", "edu", "gov", "ac", "ne", "gen", "firm", "ind")
        return if (parts.size >= 3 && tld.length == 2 && commonCcSlds.contains(sld)) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    fun isDomainWhitelisted(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return false
        val set = cachedWhitelist ?: run {
            val s = prefs.getStringSet(KEY_ADBLOCK_WHITELIST, emptySet()) ?: emptySet()
            cachedWhitelist = s
            s
        }
        return set.contains(domain)
    }

    fun setDomainWhitelisted(domainOrUrl: String, whitelisted: Boolean) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val currentSet = prefs.getStringSet(KEY_ADBLOCK_WHITELIST, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (whitelisted) {
            currentSet.add(domain)
        } else {
            currentSet.remove(domain)
        }
        cachedWhitelist = currentSet
        prefs.edit().putStringSet(KEY_ADBLOCK_WHITELIST, currentSet).apply()
        com.onyx.browser.nativebridge.AdBlockEngine.clearDecisionCache()
    }

    fun addWhitelistedDomain(domainOrUrl: String) {
        setDomainWhitelisted(domainOrUrl, true)
    }

    fun removeWhitelistedDomain(domainOrUrl: String) {
        setDomainWhitelisted(domainOrUrl, false)
    }

    // ── Web Site Permissions (Camera, Microphone, Location per-domain) ──
    @Volatile private var cachedAllowedCameraDomains: Set<String>? = null
    @Volatile private var cachedBlockedCameraDomains: Set<String>? = null
    @Volatile private var cachedAllowedMicDomains: Set<String>? = null
    @Volatile private var cachedBlockedMicDomains: Set<String>? = null
    @Volatile private var cachedAllowedLocationDomains: Set<String>? = null
    @Volatile private var cachedBlockedLocationDomains: Set<String>? = null

    fun isWebCameraAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedCameraDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_CAMERA_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedCameraDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedCameraDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_CAMERA_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedCameraDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun setWebCameraAllowed(domainOrUrl: String, allowed: Boolean) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val allowedSet = prefs.getStringSet(KEY_ALLOWED_CAMERA_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedSet = prefs.getStringSet(KEY_BLOCKED_CAMERA_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (allowed) {
            allowedSet.add(domain)
            blockedSet.remove(domain)
        } else {
            blockedSet.add(domain)
            allowedSet.remove(domain)
        }
        cachedAllowedCameraDomains = allowedSet
        cachedBlockedCameraDomains = blockedSet
        prefs.edit()
            .putStringSet(KEY_ALLOWED_CAMERA_DOMAINS, allowedSet)
            .putStringSet(KEY_BLOCKED_CAMERA_DOMAINS, blockedSet)
            .apply()
    }

    fun isWebMicAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedMicDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_MIC_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedMicDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedMicDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_MIC_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedMicDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun setWebMicAllowed(domainOrUrl: String, allowed: Boolean) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val allowedSet = prefs.getStringSet(KEY_ALLOWED_MIC_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedSet = prefs.getStringSet(KEY_BLOCKED_MIC_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (allowed) {
            allowedSet.add(domain)
            blockedSet.remove(domain)
        } else {
            blockedSet.add(domain)
            allowedSet.remove(domain)
        }
        cachedAllowedMicDomains = allowedSet
        cachedBlockedMicDomains = blockedSet
        prefs.edit()
            .putStringSet(KEY_ALLOWED_MIC_DOMAINS, allowedSet)
            .putStringSet(KEY_BLOCKED_MIC_DOMAINS, blockedSet)
            .apply()
    }

    fun isWebLocationAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedLocationDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_LOCATION_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedLocationDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedLocationDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_LOCATION_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedLocationDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun setWebLocationAllowed(domainOrUrl: String, allowed: Boolean) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val allowedSet = prefs.getStringSet(KEY_ALLOWED_LOCATION_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedSet = prefs.getStringSet(KEY_BLOCKED_LOCATION_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (allowed) {
            allowedSet.add(domain)
            blockedSet.remove(domain)
        } else {
            blockedSet.add(domain)
            allowedSet.remove(domain)
        }
        cachedAllowedLocationDomains = allowedSet
        cachedBlockedLocationDomains = blockedSet
        prefs.edit()
            .putStringSet(KEY_ALLOWED_LOCATION_DOMAINS, allowedSet)
            .putStringSet(KEY_BLOCKED_LOCATION_DOMAINS, blockedSet)
            .apply()
    }

    var isCameraEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_ENABLED_GLOBAL, true)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_ENABLED_GLOBAL, value).apply()

    var isMicEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_MIC_ENABLED_GLOBAL, true)
        set(value) = prefs.edit().putBoolean(KEY_MIC_ENABLED_GLOBAL, value).apply()

    var isLocationEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_ENABLED_GLOBAL, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCATION_ENABLED_GLOBAL, value).apply()

    fun getSiteCameraPermission(domainOrUrl: String): SitePermissionState {
        return when (isWebCameraAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }
    }

    fun setSiteCameraPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val allowedSet = prefs.getStringSet(KEY_ALLOWED_CAMERA_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedSet = prefs.getStringSet(KEY_BLOCKED_CAMERA_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> {
                allowedSet.add(domain)
                blockedSet.remove(domain)
            }
            SitePermissionState.BLOCKED -> {
                blockedSet.add(domain)
                allowedSet.remove(domain)
            }
            SitePermissionState.ASK -> {
                allowedSet.remove(domain)
                blockedSet.remove(domain)
            }
        }
        cachedAllowedCameraDomains = allowedSet
        cachedBlockedCameraDomains = blockedSet
        prefs.edit()
            .putStringSet(KEY_ALLOWED_CAMERA_DOMAINS, allowedSet)
            .putStringSet(KEY_BLOCKED_CAMERA_DOMAINS, blockedSet)
            .apply()
    }

    fun getSiteMicPermission(domainOrUrl: String): SitePermissionState {
        return when (isWebMicAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }
    }

    fun setSiteMicPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val allowedSet = prefs.getStringSet(KEY_ALLOWED_MIC_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedSet = prefs.getStringSet(KEY_BLOCKED_MIC_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> {
                allowedSet.add(domain)
                blockedSet.remove(domain)
            }
            SitePermissionState.BLOCKED -> {
                blockedSet.add(domain)
                allowedSet.remove(domain)
            }
            SitePermissionState.ASK -> {
                allowedSet.remove(domain)
                blockedSet.remove(domain)
            }
        }
        cachedAllowedMicDomains = allowedSet
        cachedBlockedMicDomains = blockedSet
        prefs.edit()
            .putStringSet(KEY_ALLOWED_MIC_DOMAINS, allowedSet)
            .putStringSet(KEY_BLOCKED_MIC_DOMAINS, blockedSet)
            .apply()
    }

    fun getSiteLocationPermission(domainOrUrl: String): SitePermissionState {
        return when (isWebLocationAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }
    }

    fun setSiteLocationPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val allowedSet = prefs.getStringSet(KEY_ALLOWED_LOCATION_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val blockedSet = prefs.getStringSet(KEY_BLOCKED_LOCATION_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> {
                allowedSet.add(domain)
                blockedSet.remove(domain)
            }
            SitePermissionState.BLOCKED -> {
                blockedSet.add(domain)
                allowedSet.remove(domain)
            }
            SitePermissionState.ASK -> {
                allowedSet.remove(domain)
                blockedSet.remove(domain)
            }
        }
        cachedAllowedLocationDomains = allowedSet
        cachedBlockedLocationDomains = blockedSet
        prefs.edit()
            .putStringSet(KEY_ALLOWED_LOCATION_DOMAINS, allowedSet)
            .putStringSet(KEY_BLOCKED_LOCATION_DOMAINS, blockedSet)
            .apply()
    }

    // ── Per-domain Ad & Tracker Blocking Level (Standard / Aggressive) ─────────
    @Volatile private var cachedAggressiveDomains: Set<String>? = null
    @Volatile private var cachedStandardDomains: Set<String>? = null

    fun getSiteBlockingLevel(domainOrUrl: String): Int? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val aggressive = cachedAggressiveDomains ?: run {
            val s = prefs.getStringSet(KEY_AGGRESSIVE_DOMAINS, emptySet()) ?: emptySet()
            cachedAggressiveDomains = s
            s
        }
        if (aggressive.contains(domain)) return BLOCKING_AGGRESSIVE
        val standard = cachedStandardDomains ?: run {
            val s = prefs.getStringSet(KEY_STANDARD_DOMAINS, emptySet()) ?: emptySet()
            cachedStandardDomains = s
            s
        }
        if (standard.contains(domain)) return BLOCKING_STANDARD
        return null
    }

    fun getEffectiveBlockingLevel(domainOrUrl: String): Int {
        return getSiteBlockingLevel(domainOrUrl) ?: blockingLevel
    }

    fun setSiteBlockingLevel(domainOrUrl: String, level: Int?) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val aggressive = prefs.getStringSet(KEY_AGGRESSIVE_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val standard = prefs.getStringSet(KEY_STANDARD_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (level) {
            BLOCKING_AGGRESSIVE -> {
                aggressive.add(domain)
                standard.remove(domain)
            }
            BLOCKING_STANDARD -> {
                standard.add(domain)
                aggressive.remove(domain)
            }
            null -> {
                aggressive.remove(domain)
                standard.remove(domain)
            }
        }
        cachedAggressiveDomains = aggressive
        cachedStandardDomains = standard
        prefs.edit()
            .putStringSet(KEY_AGGRESSIVE_DOMAINS, aggressive)
            .putStringSet(KEY_STANDARD_DOMAINS, standard)
            .apply()
        com.onyx.browser.nativebridge.AdBlockEngine.clearDecisionCache()
    }

    /**
     * Resolves the canonical eTLD+1 base domain key for per-site desktop mode settings,
     * matching Chromium / Brave's ContentSettingsPattern::FromURLToRegistrableDomain (e.g. 'm.youtube.com' -> 'youtube.com').
     */
    fun getCanonicalDesktopKey(domainOrUrl: String): String {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return ""
        val base = getBaseDomain(domain)
        return if (base.isNotBlank()) base else domain
    }

    fun isDesktopModeForDomain(domainOrUrl: String): Boolean {
        val key = getCanonicalDesktopKey(domainOrUrl)
        if (key.isBlank()) return false
        if (mobileDomains.contains(key)) return false
        return desktopDomains.contains(key)
    }

    fun isMobileModeForDomain(domainOrUrl: String): Boolean {
        val key = getCanonicalDesktopKey(domainOrUrl)
        if (key.isBlank()) return false
        return mobileDomains.contains(key)
    }

    fun getSiteDesktopModeState(domainOrUrl: String): SitePermissionState {
        val key = getCanonicalDesktopKey(domainOrUrl)
        if (key.isBlank()) return SitePermissionState.ASK
        if (mobileDomains.contains(key)) {
            return SitePermissionState.BLOCKED
        }
        if (desktopDomains.contains(key)) {
            return SitePermissionState.ALLOWED
        }
        return SitePermissionState.ASK
    }

    fun setSiteDesktopModeState(domainOrUrl: String, state: SitePermissionState) {
        val key = getCanonicalDesktopKey(domainOrUrl)
        if (key.isBlank()) return
        val desk = desktopDomains.toMutableSet()
        val mob = mobileDomains.toMutableSet()

        // Clean any existing entries matching this key or legacy subdomains
        desk.removeAll { it == key || getCanonicalDesktopKey(it) == key }
        mob.removeAll { it == key || getCanonicalDesktopKey(it) == key }

        when (state) {
            SitePermissionState.ALLOWED -> desk.add(key)
            SitePermissionState.BLOCKED -> mob.add(key)
            SitePermissionState.ASK -> { /* removed from both */ }
        }
        desktopDomains = desk
        mobileDomains = mob
    }

    fun setDesktopModeForDomain(domainOrUrl: String, enabled: Boolean) {
        setSiteDesktopModeState(domainOrUrl, if (enabled) SitePermissionState.ALLOWED else SitePermissionState.BLOCKED)
    }

    fun getEffectiveDesktopMode(domainOrUrl: String): Boolean {
        val key = getCanonicalDesktopKey(domainOrUrl)
        if (key.isNotBlank()) {
            if (mobileDomains.contains(key)) return false
            if (desktopDomains.contains(key)) return true
        }
        return isDesktopMode
    }

    fun hasCustomSitePermissions(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return false
        return isWebCameraAllowed(domain) != null ||
               isWebMicAllowed(domain) != null ||
               isWebLocationAllowed(domain) != null ||
               isWebNotificationsAllowed(domain) != null ||
               isWebJavaScriptAllowed(domain) != null ||
               isWebPopupsAllowed(domain) != null ||
               isWebAutoplayAllowed(domain) != null ||
               isWebImagesAllowed(domain) != null ||
               isWebZoomAllowed(domain) != null ||
               isWebMixedContentAllowed(domain) != null ||
               isWebGoogleSignInAllowed(domain) != null ||
               isSiteAutoShredEnabled(domain) ||
               isSiteAutoKeepEnabled(domain) ||
               isWebSensorsAllowed(domain) != null ||
               isWebClipboardAllowed(domain) != null ||
               isWebSoundAllowed(domain) != null ||
               isWebAutoDownloadsAllowed(domain) != null ||
               isWebWakeLockAllowed(domain) != null ||
               isWebWebRtcExplicitlySet(domain) != null ||
               isDomainWhitelisted(domain) ||
               isScriptBlockingEnabledForDomain(domain) ||
               getSiteDesktopModeState(domain) != SitePermissionState.ASK ||
               getSiteBlockingLevel(domain) != null ||
               getCustomBlockedSelectorsForDomain(domain).isNotEmpty()
    }

    fun resetSitePermissions(domainOrUrl: String) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        setSiteCameraPermission(domain, SitePermissionState.ASK)
        setSiteMicPermission(domain, SitePermissionState.ASK)
        setSiteLocationPermission(domain, SitePermissionState.ASK)
        setSiteNotificationsPermission(domain, SitePermissionState.ASK)
        setSiteJavaScriptPermission(domain, SitePermissionState.ASK)
        setSitePopupsPermission(domain, SitePermissionState.ASK)
        setSiteAutoplayPermission(domain, SitePermissionState.ASK)
        setSiteImagesPermission(domain, SitePermissionState.ASK)
        setSiteZoomPermission(domain, SitePermissionState.ASK)
        setSiteMixedContentPermission(domain, SitePermissionState.ASK)
        setSiteGoogleSignInPermission(domain, SitePermissionState.ASK)
        setSiteAutoShredState(domain, SitePermissionState.ASK)
        setSiteSensorsPermission(domain, SitePermissionState.ASK)
        setSiteClipboardPermission(domain, SitePermissionState.ASK)
        setSiteSoundPermission(domain, SitePermissionState.ASK)
        setSiteAutoDownloadsPermission(domain, SitePermissionState.ASK)
        setSiteWakeLockPermission(domain, SitePermissionState.ASK)
        setSiteWebRtcPermission(domain, SitePermissionState.ASK)
        setDomainWhitelisted(domain, false)
        setScriptBlockingForDomain(domain, false)
        setSiteDesktopModeState(domain, SitePermissionState.ASK)
        setSiteBlockingLevel(domain, null)
        clearCustomBlockedRulesForDomain(domain)
    }

    fun getAllowedCameraDomains(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_CAMERA_DOMAINS, emptySet()) ?: emptySet()

    fun getBlockedCameraDomains(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_CAMERA_DOMAINS, emptySet()) ?: emptySet()

    fun getAllowedMicDomains(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_MIC_DOMAINS, emptySet()) ?: emptySet()

    fun getBlockedMicDomains(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_MIC_DOMAINS, emptySet()) ?: emptySet()

    fun getAllowedLocationDomains(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_LOCATION_DOMAINS, emptySet()) ?: emptySet()

    fun getBlockedLocationDomains(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_LOCATION_DOMAINS, emptySet()) ?: emptySet()

    // ── Notifications Permission (per-domain) ────────────────────────────────
    @Volatile private var cachedAllowedNotifDomains: Set<String>? = null
    @Volatile private var cachedBlockedNotifDomains: Set<String>? = null

    fun isWebNotificationsAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedNotifDomains ?: run { val s = prefs.getStringSet(KEY_ALLOWED_NOTIF_DOMAINS, emptySet()) ?: emptySet(); cachedAllowedNotifDomains = s; s }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedNotifDomains ?: run { val s = prefs.getStringSet(KEY_BLOCKED_NOTIF_DOMAINS, emptySet()) ?: emptySet(); cachedBlockedNotifDomains = s; s }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteNotificationsPermission(domainOrUrl: String): SitePermissionState = when (isWebNotificationsAllowed(domainOrUrl)) { true -> SitePermissionState.ALLOWED; false -> SitePermissionState.BLOCKED; null -> SitePermissionState.ASK }

    fun setSiteNotificationsPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl); if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_NOTIF_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_NOTIF_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) { SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }; SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }; SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) } }
        cachedAllowedNotifDomains = a; cachedBlockedNotifDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_NOTIF_DOMAINS, a).putStringSet(KEY_BLOCKED_NOTIF_DOMAINS, b).apply()
    }

    fun getAllowedNotifDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_NOTIF_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedNotifDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_NOTIF_DOMAINS, emptySet()) ?: emptySet()

    var isNotificationsEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED_GLOBAL, false) // default off: don't allow by default
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED_GLOBAL, value).apply()

    // ── JavaScript Permission (per-domain) ───────────────────────────────────
    @Volatile private var cachedAllowedJsDomains: Set<String>? = null
    @Volatile private var cachedBlockedJsDomains: Set<String>? = null

    fun isWebJavaScriptAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedJsDomains ?: run { val s = prefs.getStringSet(KEY_ALLOWED_JS_DOMAINS, emptySet()) ?: emptySet(); cachedAllowedJsDomains = s; s }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedJsDomains ?: run { val s = prefs.getStringSet(KEY_BLOCKED_JS_DOMAINS, emptySet()) ?: emptySet(); cachedBlockedJsDomains = s; s }
        if (blocked.contains(domain)) return false
        return null
    }

    fun isJavaScriptEnabledForDomain(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return isJavaScriptEnabledGlobal && !isGlobalScriptBlockingEnabled
        val jsPerm = isWebJavaScriptAllowed(domain)
        val jsGloballyEnabled = isJavaScriptEnabledGlobal && !isGlobalScriptBlockingEnabled && !isScriptBlockingEnabledForDomain(domain)
        return jsPerm ?: jsGloballyEnabled
    }

    fun getSiteJavaScriptPermission(domainOrUrl: String): SitePermissionState = when (isWebJavaScriptAllowed(domainOrUrl)) { true -> SitePermissionState.ALLOWED; false -> SitePermissionState.BLOCKED; null -> SitePermissionState.ASK }

    fun setSiteJavaScriptPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl); if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_JS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_JS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedJsDomains = a; cachedBlockedJsDomains = b

        val scriptBlocked = prefs.getStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (state == SitePermissionState.BLOCKED) {
            scriptBlocked.add(domain)
        } else {
            scriptBlocked.remove(domain)
        }
        cachedScriptBlockingDomains = scriptBlocked

        prefs.edit()
            .putStringSet(KEY_ALLOWED_JS_DOMAINS, a)
            .putStringSet(KEY_BLOCKED_JS_DOMAINS, b)
            .putStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, scriptBlocked)
            .apply()
    }

    fun getAllowedJsDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_JS_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedJsDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_JS_DOMAINS, emptySet()) ?: emptySet()

    var isJavaScriptEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_JS_ENABLED_GLOBAL, true)
        set(value) {
            prefs.edit()
                .putBoolean(KEY_JS_ENABLED_GLOBAL, value)
                .putBoolean(KEY_GLOBAL_SCRIPT_BLOCKING, !value)
                .apply()
        }

    // ── Popups Permission (per-domain) ───────────────────────────────────────
    @Volatile private var cachedAllowedPopupDomains: Set<String>? = null
    @Volatile private var cachedBlockedPopupDomains: Set<String>? = null

    fun isWebPopupsAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedPopupDomains ?: run { val s = prefs.getStringSet(KEY_ALLOWED_POPUP_DOMAINS, emptySet()) ?: emptySet(); cachedAllowedPopupDomains = s; s }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedPopupDomains ?: run { val s = prefs.getStringSet(KEY_BLOCKED_POPUP_DOMAINS, emptySet()) ?: emptySet(); cachedBlockedPopupDomains = s; s }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSitePopupsPermission(domainOrUrl: String): SitePermissionState = when (isWebPopupsAllowed(domainOrUrl)) { true -> SitePermissionState.ALLOWED; false -> SitePermissionState.BLOCKED; null -> SitePermissionState.ASK }

    fun setSitePopupsPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl); if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_POPUP_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_POPUP_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) { SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }; SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }; SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) } }
        cachedAllowedPopupDomains = a; cachedBlockedPopupDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_POPUP_DOMAINS, a).putStringSet(KEY_BLOCKED_POPUP_DOMAINS, b).apply()
    }

    fun getAllowedPopupDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_POPUP_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedPopupDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_POPUP_DOMAINS, emptySet()) ?: emptySet()

    var isPopupsBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_POPUPS_BLOCKED_GLOBAL, true) // default block
        set(value) = prefs.edit().putBoolean(KEY_POPUPS_BLOCKED_GLOBAL, value).apply()

    // ── Autoplay Permission (per-domain) ─────────────────────────────────────
    @Volatile private var cachedAllowedAutoplayDomains: Set<String>? = null
    @Volatile private var cachedBlockedAutoplayDomains: Set<String>? = null

    fun isWebAutoplayAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedAutoplayDomains ?: run { val s = prefs.getStringSet(KEY_ALLOWED_AUTOPLAY_DOMAINS, emptySet()) ?: emptySet(); cachedAllowedAutoplayDomains = s; s }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedAutoplayDomains ?: run { val s = prefs.getStringSet(KEY_BLOCKED_AUTOPLAY_DOMAINS, emptySet()) ?: emptySet(); cachedBlockedAutoplayDomains = s; s }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteAutoplayPermission(domainOrUrl: String): SitePermissionState = when (isWebAutoplayAllowed(domainOrUrl)) { true -> SitePermissionState.ALLOWED; false -> SitePermissionState.BLOCKED; null -> SitePermissionState.ASK }

    fun setSiteAutoplayPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl); if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_AUTOPLAY_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_AUTOPLAY_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) { SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }; SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }; SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) } }
        cachedAllowedAutoplayDomains = a; cachedBlockedAutoplayDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_AUTOPLAY_DOMAINS, a).putStringSet(KEY_BLOCKED_AUTOPLAY_DOMAINS, b).apply()
    }

    fun getAllowedAutoplayDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_AUTOPLAY_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedAutoplayDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_AUTOPLAY_DOMAINS, emptySet()) ?: emptySet()

    var isAutoplayBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY_BLOCKED_GLOBAL, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOPLAY_BLOCKED_GLOBAL, value).apply()

    // ── Images Permission (per-domain) ───────────────────────────────────────
    @Volatile private var cachedAllowedImagesDomains: Set<String>? = null
    @Volatile private var cachedBlockedImagesDomains: Set<String>? = null

    fun isWebImagesAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedImagesDomains ?: run { val s = prefs.getStringSet(KEY_ALLOWED_IMAGES_DOMAINS, emptySet()) ?: emptySet(); cachedAllowedImagesDomains = s; s }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedImagesDomains ?: run { val s = prefs.getStringSet(KEY_BLOCKED_IMAGES_DOMAINS, emptySet()) ?: emptySet(); cachedBlockedImagesDomains = s; s }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteImagesPermission(domainOrUrl: String): SitePermissionState = when (isWebImagesAllowed(domainOrUrl)) { true -> SitePermissionState.ALLOWED; false -> SitePermissionState.BLOCKED; null -> SitePermissionState.ASK }

    fun setSiteImagesPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl); if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_IMAGES_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_IMAGES_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) { SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }; SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }; SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) } }
        cachedAllowedImagesDomains = a; cachedBlockedImagesDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_IMAGES_DOMAINS, a).putStringSet(KEY_BLOCKED_IMAGES_DOMAINS, b).apply()
    }

    fun getAllowedImagesDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_IMAGES_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedImagesDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_IMAGES_DOMAINS, emptySet()) ?: emptySet()

    var isImagesEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_IMAGES_ENABLED_GLOBAL, true)
        set(value) = prefs.edit().putBoolean(KEY_IMAGES_ENABLED_GLOBAL, value).apply()

    // ── Zoom Permission (per-domain) ─────────────────────────────────────────
    @Volatile private var cachedAllowedZoomDomains: Set<String>? = null
    @Volatile private var cachedBlockedZoomDomains: Set<String>? = null

    fun isWebZoomAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedZoomDomains ?: run { val s = prefs.getStringSet(KEY_ALLOWED_ZOOM_DOMAINS, emptySet()) ?: emptySet(); cachedAllowedZoomDomains = s; s }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedZoomDomains ?: run { val s = prefs.getStringSet(KEY_BLOCKED_ZOOM_DOMAINS, emptySet()) ?: emptySet(); cachedBlockedZoomDomains = s; s }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteZoomPermission(domainOrUrl: String): SitePermissionState = when (isWebZoomAllowed(domainOrUrl)) { true -> SitePermissionState.ALLOWED; false -> SitePermissionState.BLOCKED; null -> SitePermissionState.ASK }

    fun setSiteZoomPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl); if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_ZOOM_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_ZOOM_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) { SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }; SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }; SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) } }
        cachedAllowedZoomDomains = a; cachedBlockedZoomDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_ZOOM_DOMAINS, a).putStringSet(KEY_BLOCKED_ZOOM_DOMAINS, b).apply()
    }

    fun getAllowedZoomDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_ZOOM_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedZoomDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_ZOOM_DOMAINS, emptySet()) ?: emptySet()

    var isZoomEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_ZOOM_ENABLED_GLOBAL, true)
        set(value) = prefs.edit().putBoolean(KEY_ZOOM_ENABLED_GLOBAL, value).apply()

    // ── Mixed Content Permission (per-domain) ────────────────────────────────
    @Volatile private var cachedAllowedMixedDomains: Set<String>? = null
    @Volatile private var cachedBlockedMixedDomains: Set<String>? = null

    fun isWebMixedContentAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedMixedDomains ?: run { val s = prefs.getStringSet(KEY_ALLOWED_MIXED_DOMAINS, emptySet()) ?: emptySet(); cachedAllowedMixedDomains = s; s }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedMixedDomains ?: run { val s = prefs.getStringSet(KEY_BLOCKED_MIXED_DOMAINS, emptySet()) ?: emptySet(); cachedBlockedMixedDomains = s; s }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteMixedContentPermission(domainOrUrl: String): SitePermissionState = when (isWebMixedContentAllowed(domainOrUrl)) { true -> SitePermissionState.ALLOWED; false -> SitePermissionState.BLOCKED; null -> SitePermissionState.ASK }

    fun setSiteMixedContentPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl); if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_MIXED_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_MIXED_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) { SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }; SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }; SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) } }
        cachedAllowedMixedDomains = a; cachedBlockedMixedDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_MIXED_DOMAINS, a).putStringSet(KEY_BLOCKED_MIXED_DOMAINS, b).apply()
    }

    fun getAllowedMixedDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_MIXED_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedMixedDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_MIXED_DOMAINS, emptySet()) ?: emptySet()

    var isMixedContentBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_MIXED_CONTENT_BLOCKED_GLOBAL, true) // default: block mixed content
        set(value) = prefs.edit().putBoolean(KEY_MIXED_CONTENT_BLOCKED_GLOBAL, value).apply()

    /** Protected content (DRM / EME Media) — allow sites to use protected device identifiers. */
    var isProtectedContentEnabledGlobal: Boolean
        get() = prefs.getBoolean(KEY_PROTECTED_CONTENT_ENABLED_GLOBAL, true) // default: allow (Brave default)
        set(value) = prefs.edit().putBoolean(KEY_PROTECTED_CONTENT_ENABLED_GLOBAL, value).apply()

    // ── Google Sign-In Permission (per-domain, Brave parity) ─────────────────
    @Volatile private var cachedAllowedGoogleSignInDomains: Set<String>? = null
    @Volatile private var cachedBlockedGoogleSignInDomains: Set<String>? = null

    fun isWebGoogleSignInAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedGoogleSignInDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_GOOGLE_SIGNIN_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedGoogleSignInDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedGoogleSignInDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_GOOGLE_SIGNIN_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedGoogleSignInDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteGoogleSignInPermission(domainOrUrl: String): SitePermissionState =
        when (isWebGoogleSignInAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }

    fun setSiteGoogleSignInPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_GOOGLE_SIGNIN_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_GOOGLE_SIGNIN_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedGoogleSignInDomains = a
        cachedBlockedGoogleSignInDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_GOOGLE_SIGNIN_DOMAINS, a).putStringSet(KEY_BLOCKED_GOOGLE_SIGNIN_DOMAINS, b).apply()
    }

    fun getAllowedGoogleSignInDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_GOOGLE_SIGNIN_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedGoogleSignInDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_GOOGLE_SIGNIN_DOMAINS, emptySet()) ?: emptySet()

    var isGoogleSignInBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_SIGNIN_BLOCKED_GLOBAL, false)
        set(value) = prefs.edit().putBoolean(KEY_GOOGLE_SIGNIN_BLOCKED_GLOBAL, value).apply()

    // ── Auto-Shred Site Data on Tab Close (per-domain, Brave parity) ─────────
    @Volatile private var cachedAutoShredDomains: Set<String>? = null
    @Volatile private var cachedAutoKeepDomains: Set<String>? = null

    var autoShredDomains: Set<String>
        get() = cachedAutoShredDomains ?: run {
            val s = prefs.getStringSet(KEY_AUTO_SHRED_DOMAINS, emptySet()) ?: emptySet()
            cachedAutoShredDomains = s
            s
        }
        set(value) {
            cachedAutoShredDomains = value
            prefs.edit().putStringSet(KEY_AUTO_SHRED_DOMAINS, value).apply()
        }

    var autoKeepDomains: Set<String>
        get() = cachedAutoKeepDomains ?: run {
            val s = prefs.getStringSet(KEY_AUTO_KEEP_DOMAINS, emptySet()) ?: emptySet()
            cachedAutoKeepDomains = s
            s
        }
        set(value) {
            cachedAutoKeepDomains = value
            prefs.edit().putStringSet(KEY_AUTO_KEEP_DOMAINS, value).apply()
        }

    fun isSiteAutoShredEnabled(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return false
        return autoShredDomains.contains(domain)
    }

    fun isSiteAutoKeepEnabled(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return false
        return autoKeepDomains.contains(domain)
    }

    fun getSiteAutoShredState(domainOrUrl: String): SitePermissionState {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return SitePermissionState.ASK
        if (autoShredDomains.contains(domain)) return SitePermissionState.ALLOWED
        if (autoKeepDomains.contains(domain)) return SitePermissionState.BLOCKED
        return SitePermissionState.ASK
    }

    fun setSiteAutoShredState(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val shred = autoShredDomains.toMutableSet()
        val keep = autoKeepDomains.toMutableSet()
        when (state) {
            SitePermissionState.ALLOWED -> { shred.add(domain); keep.remove(domain) }
            SitePermissionState.BLOCKED -> { keep.add(domain); shred.remove(domain) }
            SitePermissionState.ASK -> { shred.remove(domain); keep.remove(domain) }
        }
        autoShredDomains = shred
        autoKeepDomains = keep
    }

    fun setSiteAutoShredEnabled(domainOrUrl: String, enabled: Boolean) {
        setSiteAutoShredState(domainOrUrl, if (enabled) SitePermissionState.ALLOWED else SitePermissionState.ASK)
    }

    // ── Motion & Light Sensors (per-domain, Brave parity) ────────────────────
    @Volatile private var cachedAllowedSensorsDomains: Set<String>? = null
    @Volatile private var cachedBlockedSensorsDomains: Set<String>? = null

    fun isWebSensorsAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedSensorsDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_SENSORS_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedSensorsDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedSensorsDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_SENSORS_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedSensorsDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteSensorsPermission(domainOrUrl: String): SitePermissionState =
        when (isWebSensorsAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }

    fun setSiteSensorsPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_SENSORS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_SENSORS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedSensorsDomains = a
        cachedBlockedSensorsDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_SENSORS_DOMAINS, a).putStringSet(KEY_BLOCKED_SENSORS_DOMAINS, b).apply()
    }

    fun getAllowedSensorsDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_SENSORS_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedSensorsDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_SENSORS_DOMAINS, emptySet()) ?: emptySet()

    var isSensorsBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_SENSORS_BLOCKED_GLOBAL, true) // Brave default: blocked
        set(value) = prefs.edit().putBoolean(KEY_SENSORS_BLOCKED_GLOBAL, value).apply()

    // ── Clipboard Permission (per-domain) ────────────────────────────────────
    @Volatile private var cachedAllowedClipboardDomains: Set<String>? = null
    @Volatile private var cachedBlockedClipboardDomains: Set<String>? = null

    fun isWebClipboardAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedClipboardDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_CLIPBOARD_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedClipboardDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedClipboardDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_CLIPBOARD_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedClipboardDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteClipboardPermission(domainOrUrl: String): SitePermissionState =
        when (isWebClipboardAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }

    fun setSiteClipboardPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_CLIPBOARD_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_CLIPBOARD_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedClipboardDomains = a
        cachedBlockedClipboardDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_CLIPBOARD_DOMAINS, a).putStringSet(KEY_BLOCKED_CLIPBOARD_DOMAINS, b).apply()
    }

    fun getAllowedClipboardDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_CLIPBOARD_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedClipboardDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_CLIPBOARD_DOMAINS, emptySet()) ?: emptySet()

    var isClipboardBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_BLOCKED_GLOBAL, false)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD_BLOCKED_GLOBAL, value).apply()

    // ── Sound / Audio Muting (per-domain) ────────────────────────────────────
    @Volatile private var cachedAllowedSoundDomains: Set<String>? = null
    @Volatile private var cachedBlockedSoundDomains: Set<String>? = null

    fun isWebSoundAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedSoundDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_SOUND_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedSoundDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedSoundDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_SOUND_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedSoundDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteSoundPermission(domainOrUrl: String): SitePermissionState =
        when (isWebSoundAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }

    fun setSiteSoundPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_SOUND_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_SOUND_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedSoundDomains = a
        cachedBlockedSoundDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_SOUND_DOMAINS, a).putStringSet(KEY_BLOCKED_SOUND_DOMAINS, b).apply()
    }

    fun getAllowedSoundDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_SOUND_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedSoundDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_SOUND_DOMAINS, emptySet()) ?: emptySet()

    var isSoundMutedGlobal: Boolean
        get() = prefs.getBoolean(KEY_SOUND_MUTED_GLOBAL, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_MUTED_GLOBAL, value).apply()

    // ── Automatic Downloads (per-domain) ─────────────────────────────────────
    @Volatile private var cachedAllowedAutoDownloadsDomains: Set<String>? = null
    @Volatile private var cachedBlockedAutoDownloadsDomains: Set<String>? = null

    fun isWebAutoDownloadsAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedAutoDownloadsDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_AUTO_DOWNLOADS_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedAutoDownloadsDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedAutoDownloadsDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_AUTO_DOWNLOADS_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedAutoDownloadsDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteAutoDownloadsPermission(domainOrUrl: String): SitePermissionState =
        when (isWebAutoDownloadsAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }

    fun setSiteAutoDownloadsPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_AUTO_DOWNLOADS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_AUTO_DOWNLOADS_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedAutoDownloadsDomains = a
        cachedBlockedAutoDownloadsDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_AUTO_DOWNLOADS_DOMAINS, a).putStringSet(KEY_BLOCKED_AUTO_DOWNLOADS_DOMAINS, b).apply()
    }

    fun getAllowedAutoDownloadsDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_AUTO_DOWNLOADS_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedAutoDownloadsDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_AUTO_DOWNLOADS_DOMAINS, emptySet()) ?: emptySet()

    var isAutoDownloadsBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOADS_BLOCKED_GLOBAL, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOADS_BLOCKED_GLOBAL, value).apply()

    // ── Screen Wake Lock Permission (per-domain) ─────────────────────────────
    @Volatile private var cachedAllowedWakeLockDomains: Set<String>? = null
    @Volatile private var cachedBlockedWakeLockDomains: Set<String>? = null

    fun isWebWakeLockAllowed(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedWakeLockDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_WAKE_LOCK_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedWakeLockDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedWakeLockDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_WAKE_LOCK_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedWakeLockDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    fun getSiteWakeLockPermission(domainOrUrl: String): SitePermissionState =
        when (isWebWakeLockAllowed(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }

    fun setSiteWakeLockPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_WAKE_LOCK_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_WAKE_LOCK_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedWakeLockDomains = a
        cachedBlockedWakeLockDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_WAKE_LOCK_DOMAINS, a).putStringSet(KEY_BLOCKED_WAKE_LOCK_DOMAINS, b).apply()
    }

    fun getAllowedWakeLockDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_WAKE_LOCK_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedWakeLockDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_WAKE_LOCK_DOMAINS, emptySet()) ?: emptySet()

    var isWakeLockBlockedGlobal: Boolean
        get() = prefs.getBoolean(KEY_WAKE_LOCK_BLOCKED_GLOBAL, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_LOCK_BLOCKED_GLOBAL, value).apply()

    // ── WebRTC IP Handling / Leak Protection (per-domain) ────────────────────
    @Volatile private var cachedAllowedWebRtcDomains: Set<String>? = null
    @Volatile private var cachedBlockedWebRtcDomains: Set<String>? = null

    fun isWebWebRtcExplicitlySet(domainOrUrl: String): Boolean? {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return null
        val allowed = cachedAllowedWebRtcDomains ?: run {
            val s = prefs.getStringSet(KEY_ALLOWED_WEBRTC_DOMAINS, emptySet()) ?: emptySet()
            cachedAllowedWebRtcDomains = s
            s
        }
        if (allowed.contains(domain)) return true
        val blocked = cachedBlockedWebRtcDomains ?: run {
            val s = prefs.getStringSet(KEY_BLOCKED_WEBRTC_DOMAINS, emptySet()) ?: emptySet()
            cachedBlockedWebRtcDomains = s
            s
        }
        if (blocked.contains(domain)) return false
        return null
    }

    /** Returns true if WebRTC local IP leak protection is actively enforced for the domain. */
    fun isWebRtcProtected(domainOrUrl: String): Boolean {
        return when (isWebWebRtcExplicitlySet(domainOrUrl)) {
            false -> false // Excluded from leak protection (raw IP allowed)
            true -> true  // Explicitly protected
            null -> isWebRtcProtectedGlobal // Fallback to global setting (defaults to true)
        }
    }

    fun getSiteWebRtcPermission(domainOrUrl: String): SitePermissionState =
        when (isWebWebRtcExplicitlySet(domainOrUrl)) {
            true -> SitePermissionState.ALLOWED
            false -> SitePermissionState.BLOCKED
            null -> SitePermissionState.ASK
        }

    fun setSiteWebRtcPermission(domainOrUrl: String, state: SitePermissionState) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val a = prefs.getStringSet(KEY_ALLOWED_WEBRTC_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val b = prefs.getStringSet(KEY_BLOCKED_WEBRTC_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        when (state) {
            SitePermissionState.ALLOWED -> { a.add(domain); b.remove(domain) }
            SitePermissionState.BLOCKED -> { b.add(domain); a.remove(domain) }
            SitePermissionState.ASK -> { a.remove(domain); b.remove(domain) }
        }
        cachedAllowedWebRtcDomains = a
        cachedBlockedWebRtcDomains = b
        prefs.edit().putStringSet(KEY_ALLOWED_WEBRTC_DOMAINS, a).putStringSet(KEY_BLOCKED_WEBRTC_DOMAINS, b).apply()
    }

    fun getAllowedWebRtcDomains(): Set<String> = prefs.getStringSet(KEY_ALLOWED_WEBRTC_DOMAINS, emptySet()) ?: emptySet()
    fun getBlockedWebRtcDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_WEBRTC_DOMAINS, emptySet()) ?: emptySet()

    var isWebRtcProtectedGlobal: Boolean
        get() = prefs.getBoolean(KEY_WEBRTC_PROTECTED_GLOBAL, true)
        set(value) = prefs.edit().putBoolean(KEY_WEBRTC_PROTECTED_GLOBAL, value).apply()

    fun getAllConfiguredDomains(): Set<String> {
        val domains = sortedSetOf<String>()
        domains.addAll(getAllowedCameraDomains())
        domains.addAll(getBlockedCameraDomains())
        domains.addAll(getAllowedMicDomains())
        domains.addAll(getBlockedMicDomains())
        domains.addAll(getAllowedLocationDomains())
        domains.addAll(getBlockedLocationDomains())
        domains.addAll(getAllowedNotifDomains())
        domains.addAll(getBlockedNotifDomains())
        domains.addAll(getAllowedJsDomains())
        domains.addAll(getBlockedJsDomains())
        domains.addAll(getAllowedPopupDomains())
        domains.addAll(getBlockedPopupDomains())
        domains.addAll(getAllowedAutoplayDomains())
        domains.addAll(getBlockedAutoplayDomains())
        domains.addAll(getAllowedImagesDomains())
        domains.addAll(getBlockedImagesDomains())
        domains.addAll(getAllowedZoomDomains())
        domains.addAll(getBlockedZoomDomains())
        domains.addAll(getAllowedMixedDomains())
        domains.addAll(getBlockedMixedDomains())
        domains.addAll(getAllowedGoogleSignInDomains())
        domains.addAll(getBlockedGoogleSignInDomains())
        domains.addAll(autoShredDomains)
        domains.addAll(autoKeepDomains)
        domains.addAll(getAllowedSensorsDomains())
        domains.addAll(getBlockedSensorsDomains())
        domains.addAll(getAllowedClipboardDomains())
        domains.addAll(getBlockedClipboardDomains())
        domains.addAll(getAllowedSoundDomains())
        domains.addAll(getBlockedSoundDomains())
        domains.addAll(getAllowedAutoDownloadsDomains())
        domains.addAll(getBlockedAutoDownloadsDomains())
        domains.addAll(getAllowedWakeLockDomains())
        domains.addAll(getBlockedWakeLockDomains())
        domains.addAll(getAllowedWebRtcDomains())
        domains.addAll(getBlockedWebRtcDomains())
        // Desktop mode per-domain overrides (both Desktop and Mobile)
        domains.addAll(desktopDomains)
        domains.addAll(mobileDomains)
        // Per-domain script blocking
        val scriptBlocked = prefs.getStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, emptySet()) ?: emptySet()
        domains.addAll(scriptBlocked)
        val whitelist = prefs.getStringSet(KEY_ADBLOCK_WHITELIST, emptySet()) ?: emptySet()
        domains.addAll(whitelist)
        val aggressive = prefs.getStringSet(KEY_AGGRESSIVE_DOMAINS, emptySet()) ?: emptySet()
        domains.addAll(aggressive)
        val standard = prefs.getStringSet(KEY_STANDARD_DOMAINS, emptySet()) ?: emptySet()
        domains.addAll(standard)
        // Custom Blocked Elements / Custom Cosmetic Filter rules per domain
        ensureDomainRulesCache()
        for ((d, selectors) in domainCustomRulesCache) {
            if (selectors.isNotEmpty()) {
                domains.add(d)
            }
        }
        return domains
    }

    fun getBlockedRequestsCount(): Long {
        return prefs.getLong(KEY_BLOCKED_REQUESTS_COUNT, 0L)
    }

    private val saveCounterHandler = Handler(Looper.getMainLooper())
    private val saveCounterRunnable = Runnable {
        val count = blockedCounter.get()
        prefs.edit().putLong(KEY_BLOCKED_REQUESTS_COUNT, count).apply()
        _blockedRequestsFlow.value = count
    }

    fun incrementBlockedRequests(): Long {
        val newCount = blockedCounter.incrementAndGet()
        saveCounterHandler.removeCallbacks(saveCounterRunnable)
        saveCounterHandler.postDelayed(saveCounterRunnable, 300)
        return newCount
    }

    fun resetBlockedRequests() {
        saveCounterHandler.removeCallbacks(saveCounterRunnable)
        blockedCounter.set(0L)
        prefs.edit().putLong(KEY_BLOCKED_REQUESTS_COUNT, 0L).apply()
        _blockedRequestsFlow.value = 0L
    }

    fun applyTheme() {
        val targetMode = when (themeMode) {
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_SYSTEM, THEME_DYNAMIC -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }
    }

    var isDevToolsEnabled: Boolean
        get() = prefs.getBoolean("pref_devtools_enabled", false)
        set(value) = prefs.edit().putBoolean("pref_devtools_enabled", value).apply()

    fun isDarkMode(): Boolean {
        return when (themeMode) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            else -> {
                val mode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun getShortcuts(): List<ShortcutItem> {
        val jsonString = prefs.getString(KEY_HOMEPAGE_SHORTCUTS, null)
        if (jsonString.isNullOrBlank()) {
            val defaults = ShortcutItem.getDefaultShortcuts()
            saveShortcuts(defaults)
            return defaults
        }
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<ShortcutItem>()
            for (i in 0 until jsonArray.length()) {
                list.add(ShortcutItem.fromJson(jsonArray.getJSONObject(i)))
            }
            if (list.isEmpty()) {
                val defaults = ShortcutItem.getDefaultShortcuts()
                saveShortcuts(defaults)
                defaults
            } else {
                // Deduplication-safe migration: add missing sys_ defaults at the top,
                // only if they are not already present by ID. Mark done IMMEDIATELY so
                // it never re-runs even if the app is killed before the next save.
                if (!prefs.getBoolean("migrated_unified_shortcuts_v2", false)) {
                    prefs.edit().putBoolean("migrated_unified_shortcuts_v2", true).apply()
                    val existingIds = list.map { it.id }.toSet()
                    val missingSysItems = ShortcutItem.getDefaultShortcuts()
                        .filter { it.id.startsWith("sys_") && !existingIds.contains(it.id) }
                    if (missingSysItems.isNotEmpty()) {
                        list.addAll(0, missingSysItems)
                    }
                }
                // Always deduplicate by id and filter out legacy sys_qr before returning.
                val deduped = list.distinctBy { it.id }.filterNot { it.id == "sys_qr" || it.url == "onyx://qr" }
                if (deduped.size != list.size) saveShortcuts(deduped)
                deduped
            }
        } catch (_: Exception) {
            val defaults = ShortcutItem.getDefaultShortcuts()
            saveShortcuts(defaults)
            defaults
        }
    }

    fun saveShortcuts(shortcuts: List<ShortcutItem>) {
        val jsonArray = JSONArray()
        for (item in shortcuts) {
            jsonArray.put(item.toJson())
        }
        prefs.edit().putString(KEY_HOMEPAGE_SHORTCUTS, jsonArray.toString()).apply()
        _shortcutsFlow.value = shortcuts
    }

    fun addShortcut(shortcut: ShortcutItem) {
        val current = getShortcuts().toMutableList()
        current.add(shortcut)
        saveShortcuts(current)
    }

    fun updateShortcut(updated: ShortcutItem) {
        val current = getShortcuts().toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            if (current[index].isSystemShortcut) return
            current[index] = updated
            saveShortcuts(current)
        }
    }

    fun deleteShortcut(id: String) {
        val current = getShortcuts().toMutableList()
        val item = current.find { it.id == id }
        if (item != null && item.isSystemShortcut) {
            return
        }
        val removed = current.removeAll { it.id == id }
        if (removed) {
            saveShortcuts(current)
        }
    }

    fun getQuickActionOrder(): List<String> {
        val raw = prefs.getString(KEY_QUICK_ACTION_ORDER, null)
        if (raw.isNullOrBlank()) {
            return QuickActionItem.DEFAULT_ORDER
        }
        return try {
            val parts = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) QuickActionItem.DEFAULT_ORDER else parts
        } catch (_: Exception) {
            QuickActionItem.DEFAULT_ORDER
        }
    }

    fun saveQuickActionOrder(order: List<String>) {
        prefs.edit().putString(KEY_QUICK_ACTION_ORDER, order.joinToString(",")).apply()
    }

    private fun normalizeFilterKey(key: String): String {
        return when (key) {
            "ublock" -> "ublock_filters"
            "brave_social" -> "social_media"
            "cookie_notices" -> "cookie_notice"
            else -> key
        }
    }

    fun isFilterListEnabled(key: String): Boolean = enabledFilterLists.contains(normalizeFilterKey(key))

    fun setFilterListEnabled(key: String, enabled: Boolean) {
        val canonicalKey = normalizeFilterKey(key)
        val current = enabledFilterLists.toMutableSet()
        if (enabled) {
            current.add(canonicalKey)
            if (key != canonicalKey) current.remove(key)
        } else {
            current.remove(canonicalKey)
            current.remove(key)
        }
        enabledFilterLists = current
    }

    // ── Custom Filter Items (URL or Manual Rules) ───────────────────────────
    fun getCustomFilters(): List<com.onyx.browser.data.filter.CustomFilterItem> {
        val jsonString = prefs.getString(KEY_CUSTOM_FILTER_ITEMS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<com.onyx.browser.data.filter.CustomFilterItem>()
            for (i in 0 until jsonArray.length()) {
                list.add(com.onyx.browser.data.filter.CustomFilterItem.fromJson(jsonArray.getJSONObject(i)))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomFilters(filters: List<com.onyx.browser.data.filter.CustomFilterItem>) {
        val jsonArray = JSONArray()
        for (item in filters) {
            jsonArray.put(item.toJson())
        }
        prefs.edit().putString(KEY_CUSTOM_FILTER_ITEMS, jsonArray.toString()).apply()
    }

    fun addCustomFilter(filter: com.onyx.browser.data.filter.CustomFilterItem) {
        val list = getCustomFilters().toMutableList()
        list.removeAll { it.id == filter.id }
        list.add(0, filter)
        saveCustomFilters(list)
    }

    fun updateCustomFilter(filter: com.onyx.browser.data.filter.CustomFilterItem) {
        val list = getCustomFilters().toMutableList()
        val index = list.indexOfFirst { it.id == filter.id }
        if (index != -1) {
            list[index] = filter
            saveCustomFilters(list)
        }
    }

    fun deleteCustomFilter(id: String) {
        val list = getCustomFilters().toMutableList()
        if (list.removeAll { it.id == id }) {
            saveCustomFilters(list)
        }
    }

    // ── Custom Search Engines ────────────────────────────────────────────────
    fun getCustomSearchEngines(): List<SearchEngine> {
        val raw = prefs.getString(KEY_CUSTOM_SEARCH_ENGINES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<SearchEngine>()
            for (i in 0 until array.length()) {
                list.add(SearchEngine.fromJson(array.getJSONObject(i)))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomSearchEngines(engines: List<SearchEngine>) {
        val array = JSONArray()
        for (engine in engines) {
            array.put(engine.toJson())
        }
        prefs.edit().putString(KEY_CUSTOM_SEARCH_ENGINES, array.toString()).apply()
    }

    fun addCustomSearchEngine(engine: SearchEngine) {
        val current = getCustomSearchEngines().toMutableList()
        current.add(engine)
        saveCustomSearchEngines(current)
    }

    fun updateCustomSearchEngine(engine: SearchEngine) {
        val current = getCustomSearchEngines().toMutableList()
        val index = current.indexOfFirst { it.id == engine.id }
        if (index != -1) {
            current[index] = engine
            saveCustomSearchEngines(current)
        }
    }

    fun deleteCustomSearchEngine(id: String) {
        val current = getCustomSearchEngines().toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            saveCustomSearchEngines(current)
            if (prefs.getString(KEY_SEARCH_ENGINE, "") == id) {
                searchEngine = SearchEngine.GOOGLE
            }
        }
    }

    fun getAllSearchEngines(): List<SearchEngine> {
        return SearchEngine.BUILT_IN + getCustomSearchEngines()
    }

    // ── Autofill & Passkeys ──────────────────────────────────────────────────
    var isAutofillEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOFILL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOFILL_ENABLED, value).apply()

    var isSavePasswordsPromptEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAVE_PASSWORDS_PROMPT, true)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_PASSWORDS_PROMPT, value).apply()

    var isPasskeysEnabled: Boolean
        get() = prefs.getBoolean(KEY_PASSKEYS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PASSKEYS_ENABLED, value).apply()

    var isCookieAutoclearOnCloseEnabled: Boolean
        get() = prefs.getBoolean(KEY_COOKIE_AUTOCLEAR, false)
        set(value) = prefs.edit().putBoolean(KEY_COOKIE_AUTOCLEAR, value).apply()

    var isBiometricIncognitoEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_INCOGNITO, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_INCOGNITO, value).apply()
        
    var userAgentSpoofTemplate: String
        get() = prefs.getString(KEY_UA_SPOOF, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_UA_SPOOF, value).apply()

    var customUserAgent: String
        get() = prefs.getString(KEY_CUSTOM_UA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_UA, value).apply()

    var isScrollToTopEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCROLL_TO_TOP, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL_TO_TOP, value).apply()

    var isAutoHideToolbarEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HIDE_TOOLBAR, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HIDE_TOOLBAR, value).apply()

    var isAddToHomeScreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADD_TO_HOME_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ADD_TO_HOME_SCREEN, value).apply()

    var isBlockElementEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_ELEMENT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_ELEMENT_ENABLED, value).apply()

    companion object {
        private const val PREF_NAME = "onyx_browser_prefs"

        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
        const val THEME_DYNAMIC = 0 // Legacy alias to THEME_SYSTEM

        const val DEFAULT_ACCENT_HEX = "#DC4B64"
        const val KEY_ACCENT_COLOR_PRESET = "pref_accent_color_preset"
        const val KEY_ACCENT_COLOR_HEX = "pref_accent_color_hex"

        const val ACCENT_PRESET_CRIMSON = "crimson"
        const val ACCENT_PRESET_ORANGE = "orange"
        const val ACCENT_PRESET_BLUE = "blue"
        const val ACCENT_PRESET_GREEN = "green"
        const val ACCENT_PRESET_VIOLET = "violet"
        const val ACCENT_PRESET_AMBER = "amber"
        const val ACCENT_PRESET_PINK = "pink"
        const val ACCENT_PRESET_CYAN = "cyan"
        const val ACCENT_PRESET_SLATE = "slate" // Legacy alias for cyan
        const val ACCENT_PRESET_CUSTOM = "custom"

        /** Blocking level constants (used with KEY_BLOCKING_LEVEL) */
        const val BLOCKING_STANDARD = 0   // third-party ads & trackers only
        const val BLOCKING_AGGRESSIVE = 1 // all ads & trackers incl. first-party

        // HTTPS Upgrade Mode
        const val HTTPS_MODE_DISABLED = 0
        const val HTTPS_MODE_WHEN_POSSIBLE = 1
        const val HTTPS_MODE_STRICT = 2

        // Cookie Blocking Mode
        const val COOKIE_BLOCK_NONE = 0
        const val COOKIE_BLOCK_THIRD_PARTY = 1
        const val COOKIE_BLOCK_ALL = 2

        const val KEY_SEARCH_ENGINE = "pref_search_engine"
        const val KEY_THEME_MODE = "pref_theme_mode"
        const val KEY_ADBLOCK_ENABLED = "pref_adblock_enabled"
        const val KEY_BLOCKING_LEVEL = "pref_blocking_level"
        const val KEY_AGGRESSIVE_DOMAINS = "pref_aggressive_domains"
        const val KEY_STANDARD_DOMAINS = "pref_standard_domains"
        const val KEY_COSMETIC_FILTERING = "pref_cosmetic_filtering"
        const val KEY_FINGERPRINT_PROTECTION = "pref_fingerprint_protection"
        const val KEY_HTTPS_UPGRADE = "pref_https_upgrade"
        const val KEY_BLOCK_THIRD_PARTY_COOKIES = "pref_block_third_party_cookies"
        const val KEY_DO_NOT_TRACK = "pref_do_not_track"
        const val KEY_SCRIPT_BLOCKING_DOMAINS = "pref_script_blocking_domains"
        const val KEY_DESKTOP_MODE = "pref_desktop_mode"
        const val KEY_DESKTOP_DOMAINS = "desktop_domains"
        const val KEY_MOBILE_DOMAINS = "mobile_domains"
        const val KEY_JAVASCRIPT_ENABLED = "pref_javascript_enabled"
        const val KEY_BLOCKED_REQUESTS_COUNT = "pref_blocked_requests_count"
        const val KEY_FILTER_UPDATED = "pref_filter_updated"
        const val KEY_COMPILED_FILTER_SIGNATURE = "pref_compiled_filter_signature"
        const val KEY_ADBLOCK_WHITELIST = "pref_adblock_whitelist"
        const val KEY_TRANSLATE_TARGET_LANG = "pref_translate_target_lang"
        const val KEY_TRANSLATE_TARGET_NAME = "pref_translate_target_name"
        const val KEY_HOMEPAGE_SHORTCUTS = "pref_homepage_shortcuts"
        const val KEY_QUICK_ACTION_ORDER = "pref_quick_action_order"
        const val KEY_BACKGROUND_PLAY = "pref_background_play"
        const val KEY_PIP_ENABLED = "pref_pip_enabled"

        // New keys
        const val KEY_AUTO_REDIRECT_AMP = "pref_auto_redirect_amp"
        const val KEY_AUTO_REDIRECT_TRACKING = "pref_auto_redirect_tracking"
        const val KEY_AUTO_REDIRECT_DEFAULTS_MIGRATED = "pref_auto_redirect_defaults_migrated"
        const val KEY_HTTPS_UPGRADE_MODE = "pref_https_upgrade_mode"
        const val KEY_GLOBAL_SCRIPT_BLOCKING = "pref_global_script_blocking"
        const val KEY_COOKIE_BLOCKING_MODE = "pref_cookie_blocking_mode"
        const val KEY_FINGERPRINT_LANG = "pref_fingerprint_lang"
        const val KEY_CUSTOM_FILTER_RULES = "pref_custom_filter_rules"
        const val KEY_ENABLED_FILTER_LISTS = "pref_enabled_filter_lists"
        const val KEY_ELEMENT_BLOCKING_PRIVATE = "pref_element_blocking_private"
        const val KEY_SOCIAL_MEDIA_BLOCKING = "pref_social_media_blocking"
        const val KEY_ALLOW_FB_LOGINS = "pref_allow_fb_logins"
        const val KEY_ALLOW_TWITTER_EMBEDS = "pref_allow_twitter_embeds"
        const val KEY_ALLOW_LINKEDIN_EMBEDS = "pref_allow_linkedin_embeds"
        const val KEY_OPEN_LINKS_IN_APP = "pref_open_links_in_app"
        const val KEY_SECURE_DNS = "pref_secure_dns"
        const val KEY_SECURE_DNS_PROVIDER = "pref_secure_dns_provider"
        const val KEY_BLOCK_APP_BANNER = "pref_block_app_banner"
        const val KEY_ANTI_ADBLOCK_DETECTION = "pref_anti_adblock_detection"
        const val KEY_CUSTOM_SEARCH_ENGINES = "pref_custom_search_engines"
        const val KEY_AUTOFILL_ENABLED = "pref_autofill_enabled"
        const val KEY_SAVE_PASSWORDS_PROMPT = "pref_save_passwords_prompt"
        const val KEY_PASSKEYS_ENABLED = "pref_passkeys_enabled"
        const val KEY_FILTER_AUTO_UPDATE = "pref_filter_auto_update"
        const val KEY_FILTER_UPDATE_INTERVAL = "pref_filter_update_interval"
        const val KEY_CUSTOM_FILTER_ITEMS = "pref_custom_filter_items"
        const val KEY_COOKIE_AUTOCLEAR = "pref_cookie_autoclear"
        const val KEY_BIOMETRIC_INCOGNITO = "pref_biometric_incognito"
        const val KEY_UA_SPOOF = "pref_ua_spoof"
        const val KEY_CUSTOM_UA = "pref_custom_ua"
        const val KEY_SCROLL_TO_TOP = "pref_scroll_to_top"
        const val KEY_AUTO_HIDE_TOOLBAR = "pref_auto_hide_toolbar"
        const val KEY_ADD_TO_HOME_SCREEN = "pref_add_to_home_screen"
        const val KEY_BLOCK_ELEMENT_ENABLED = "pref_block_element_enabled"
        const val KEY_DEFAULT_ENGINE_MIGRATED = "pref_default_engine_google_migrated"
        const val KEY_CHECKED_DEFAULT_REGION = "pref_checked_default_region"
        const val KEY_ALLOWED_CAMERA_DOMAINS = "pref_allowed_camera_domains"
        const val KEY_BLOCKED_CAMERA_DOMAINS = "pref_blocked_camera_domains"
        const val KEY_ALLOWED_MIC_DOMAINS = "pref_allowed_mic_domains"
        const val KEY_BLOCKED_MIC_DOMAINS = "pref_blocked_mic_domains"
        const val KEY_ALLOWED_LOCATION_DOMAINS = "pref_allowed_location_domains"
        const val KEY_BLOCKED_LOCATION_DOMAINS = "pref_blocked_location_domains"
        const val KEY_CAMERA_ENABLED_GLOBAL = "pref_camera_enabled_global"
        const val KEY_MIC_ENABLED_GLOBAL = "pref_mic_enabled_global"
        const val KEY_LOCATION_ENABLED_GLOBAL = "pref_location_enabled_global"
        const val KEY_LAST_ACTIVE_TAB_ID = "pref_last_active_tab_id"
        const val KEY_CLEAR_ON_EXIT = "pref_clear_on_exit"
        const val KEY_PULL_TO_REFRESH = "pref_pull_to_refresh"
        const val KEY_FORCE_DARK_MODE = "pref_force_dark_mode"
        const val KEY_REPLACE_HOME_WITH_REFRESH = "pref_replace_home_with_refresh"

        // ── Extended Permission Keys ──────────────────────────────────────────
        const val KEY_ALLOWED_NOTIF_DOMAINS = "pref_allowed_notif_domains"
        const val KEY_BLOCKED_NOTIF_DOMAINS = "pref_blocked_notif_domains"
        const val KEY_NOTIFICATIONS_ENABLED_GLOBAL = "pref_notifications_enabled_global"
        const val KEY_ALLOWED_JS_DOMAINS = "pref_allowed_js_domains"
        const val KEY_BLOCKED_JS_DOMAINS = "pref_blocked_js_domains"
        const val KEY_JS_ENABLED_GLOBAL = "pref_js_enabled_global"
        const val KEY_ALLOWED_POPUP_DOMAINS = "pref_allowed_popup_domains"
        const val KEY_BLOCKED_POPUP_DOMAINS = "pref_blocked_popup_domains"
        const val KEY_POPUPS_BLOCKED_GLOBAL = "pref_popups_blocked_global"
        const val KEY_ALLOWED_AUTOPLAY_DOMAINS = "pref_allowed_autoplay_domains"
        const val KEY_BLOCKED_AUTOPLAY_DOMAINS = "pref_blocked_autoplay_domains"
        const val KEY_AUTOPLAY_BLOCKED_GLOBAL = "pref_autoplay_blocked_global"
        const val KEY_ALLOWED_IMAGES_DOMAINS = "pref_allowed_images_domains"
        const val KEY_BLOCKED_IMAGES_DOMAINS = "pref_blocked_images_domains"
        const val KEY_IMAGES_ENABLED_GLOBAL = "pref_images_enabled_global"
        const val KEY_ALLOWED_ZOOM_DOMAINS = "pref_allowed_zoom_domains"
        const val KEY_BLOCKED_ZOOM_DOMAINS = "pref_blocked_zoom_domains"
        const val KEY_ZOOM_ENABLED_GLOBAL = "pref_zoom_enabled_global"
        const val KEY_ALLOWED_MIXED_DOMAINS = "pref_allowed_mixed_domains"
        const val KEY_BLOCKED_MIXED_DOMAINS = "pref_blocked_mixed_domains"
        const val KEY_MIXED_CONTENT_BLOCKED_GLOBAL = "pref_mixed_content_blocked_global"
        const val KEY_PROTECTED_CONTENT_ENABLED_GLOBAL = "pref_protected_content_enabled_global"

        // ── Brave-Parity Site Settings Keys ───────────────────────────────────
        const val KEY_ALLOWED_GOOGLE_SIGNIN_DOMAINS = "pref_allowed_google_signin_domains"
        const val KEY_BLOCKED_GOOGLE_SIGNIN_DOMAINS = "pref_blocked_google_signin_domains"
        const val KEY_GOOGLE_SIGNIN_BLOCKED_GLOBAL = "pref_google_signin_blocked_global"

        const val KEY_AUTO_SHRED_DOMAINS = "pref_auto_shred_domains"
        const val KEY_AUTO_KEEP_DOMAINS = "pref_auto_keep_domains"

        const val KEY_ALLOWED_SENSORS_DOMAINS = "pref_allowed_sensors_domains"
        const val KEY_BLOCKED_SENSORS_DOMAINS = "pref_blocked_sensors_domains"
        const val KEY_SENSORS_BLOCKED_GLOBAL = "pref_sensors_blocked_global"

        const val KEY_ALLOWED_CLIPBOARD_DOMAINS = "pref_allowed_clipboard_domains"
        const val KEY_BLOCKED_CLIPBOARD_DOMAINS = "pref_blocked_clipboard_domains"
        const val KEY_CLIPBOARD_BLOCKED_GLOBAL = "pref_clipboard_blocked_global"

        const val KEY_ALLOWED_SOUND_DOMAINS = "pref_allowed_sound_domains"
        const val KEY_BLOCKED_SOUND_DOMAINS = "pref_blocked_sound_domains"
        const val KEY_SOUND_MUTED_GLOBAL = "pref_sound_muted_global"

        const val KEY_ALLOWED_AUTO_DOWNLOADS_DOMAINS = "pref_allowed_auto_downloads_domains"
        const val KEY_BLOCKED_AUTO_DOWNLOADS_DOMAINS = "pref_blocked_auto_downloads_domains"
        const val KEY_AUTO_DOWNLOADS_BLOCKED_GLOBAL = "pref_auto_downloads_blocked_global"

        const val KEY_ALLOWED_WAKE_LOCK_DOMAINS = "pref_allowed_wake_lock_domains"
        const val KEY_BLOCKED_WAKE_LOCK_DOMAINS = "pref_blocked_wake_lock_domains"
        const val KEY_WAKE_LOCK_BLOCKED_GLOBAL = "pref_wake_lock_blocked_global"

        const val KEY_ALLOWED_WEBRTC_DOMAINS = "pref_allowed_webrtc_domains"
        const val KEY_BLOCKED_WEBRTC_DOMAINS = "pref_blocked_webrtc_domains"
        const val KEY_WEBRTC_PROTECTED_GLOBAL = "pref_webrtc_protected_global"


        /**
         * Default filter lists matching Brave Android:
         * - Core ad & tracker blocking (EasyList, EasyPrivacy, uBlock filters/privacy/badware/quick-fixes/unbreak, Brave Default)
         * - First-party ad & tracking protections (Brave First-party)
         * - Cookie notices & annoyance protection (EasyList Cookie / Fanboy's Cookie Monster)
         * - Mobile app notification/install promo blocking (Fanboy's Mobile Notifications)
         */
        val DEFAULT_FILTER_LISTS: Set<String> = setOf(
            "easylist",
            "easyprivacy",
            "ublock_filters",
            "ublock_privacy",
            "ublock_badware",
            "ublock_quick_fixes",
            "ublock_unbreak",
            "brave_default",
            "brave_firstparty",
            "cookie_notice",
            "mobile_app_promo"
        )



        @Volatile
        private var INSTANCE: BrowserPreferences? = null

        fun getInstance(context: Context): BrowserPreferences {
            return INSTANCE ?: synchronized(this) {
                val instance = BrowserPreferences(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
