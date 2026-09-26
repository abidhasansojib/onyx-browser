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

class BrowserPreferences private constructor(context: Context) {

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

    // ── Ad & Tracker Blocking ────────────────────────────────────────────────

    var isAdBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADBLOCK_ENABLED, value).apply()
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
        get() = prefs.getBoolean(KEY_HTTPS_UPGRADE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HTTPS_UPGRADE, value).apply()
        }

    var isBlockThirdPartyCookiesEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, true)
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

    // ── Global Script Blocking ────────────────────────────────────────────────
    var isGlobalScriptBlockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_SCRIPT_BLOCKING, false)
        set(value) = prefs.edit().putBoolean(KEY_GLOBAL_SCRIPT_BLOCKING, value).apply()

    // ── Cookie Blocking Mode ──────────────────────────────────────────────────
    // 0 = Allow All, 1 = Block Third-Party (default), 2 = Block All
    var cookieBlockingMode: Int
        get() = prefs.getInt(KEY_COOKIE_BLOCKING_MODE, COOKIE_BLOCK_THIRD_PARTY)
        set(value) = prefs.edit().putInt(KEY_COOKIE_BLOCKING_MODE, value).apply()

    // ── Fingerprint via Language ──────────────────────────────────────────────
    var isFingerprintLangEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT_LANG, true)
        set(value) = prefs.edit().putBoolean(KEY_FINGERPRINT_LANG, value).apply()

    // ── Custom Filter Rules ───────────────────────────────────────────────────
    var customFilterRules: String
        get() = prefs.getString(KEY_CUSTOM_FILTER_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_FILTER_RULES, value).apply()

    // ── Filter List Subscriptions (comma-separated keys) ─────────────────────
    var enabledFilterLists: Set<String>
        get() = prefs.getStringSet(KEY_ENABLED_FILTER_LISTS, setOf("easylist", "easyprivacy", "ublock_filters", "brave_default")) ?: setOf("easylist", "easyprivacy", "ublock_filters", "brave_default")
        set(value) = prefs.edit().putStringSet(KEY_ENABLED_FILTER_LISTS, value).apply()

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
        get() = prefs.getBoolean(KEY_SOCIAL_MEDIA_BLOCKING, false)
        set(value) = prefs.edit().putBoolean(KEY_SOCIAL_MEDIA_BLOCKING, value).apply()

    var allowFacebookLogins: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_FB_LOGINS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_FB_LOGINS, value).apply()

    var allowTwitterEmbeds: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_TWITTER_EMBEDS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_TWITTER_EMBEDS, value).apply()

    var allowLinkedInEmbeds: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_LINKEDIN_EMBEDS, true)
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

    // ── Per-domain Script Blocking ───────────────────────────────────────────

    fun isScriptBlockingEnabledForDomain(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return false
        val set = prefs.getStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, emptySet()) ?: emptySet()
        return set.contains(domain)
    }

    fun setScriptBlockingForDomain(domainOrUrl: String, block: Boolean) {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return
        val current = prefs.getStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (block) current.add(domain) else current.remove(domain)
        prefs.edit().putStringSet(KEY_SCRIPT_BLOCKING_DOMAINS, current).apply()
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
        get() = prefs.getBoolean(KEY_BACKGROUND_PLAY, true)
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

    var desktopDomains: Set<String>
        get() = prefs.getStringSet("desktop_domains", emptySet()) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet("desktop_domains", value).apply()
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

    fun cleanDomain(domainOrUrl: String): String {
        return try {
            val uri = if (!domainOrUrl.startsWith("http://") && !domainOrUrl.startsWith("https://")) {
                java.net.URI("https://$domainOrUrl")
            } else {
                java.net.URI(domainOrUrl)
            }
            (uri.host ?: domainOrUrl).lowercase().removePrefix("www.")
        } catch (_: Exception) {
            domainOrUrl.lowercase().removePrefix("www.")
        }
    }

    fun isDomainWhitelisted(domainOrUrl: String): Boolean {
        val domain = cleanDomain(domainOrUrl)
        if (domain.isBlank()) return false
        val set = prefs.getStringSet(KEY_ADBLOCK_WHITELIST, emptySet()) ?: emptySet()
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
        prefs.edit().putStringSet(KEY_ADBLOCK_WHITELIST, currentSet).apply()
    }

    fun addWhitelistedDomain(domainOrUrl: String) {
        setDomainWhitelisted(domainOrUrl, true)
    }

    fun removeWhitelistedDomain(domainOrUrl: String) {
        setDomainWhitelisted(domainOrUrl, false)
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

    fun isFilterListEnabled(key: String): Boolean = enabledFilterLists.contains(key)

    fun setFilterListEnabled(key: String, enabled: Boolean) {
        val current = enabledFilterLists.toMutableSet()
        if (enabled) current.add(key) else current.remove(key)
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

    companion object {
        private const val PREF_NAME = "onyx_browser_prefs"

        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
        const val THEME_DYNAMIC = 0 // Legacy alias to THEME_SYSTEM

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
        const val KEY_COSMETIC_FILTERING = "pref_cosmetic_filtering"
        const val KEY_FINGERPRINT_PROTECTION = "pref_fingerprint_protection"
        const val KEY_HTTPS_UPGRADE = "pref_https_upgrade"
        const val KEY_BLOCK_THIRD_PARTY_COOKIES = "pref_block_third_party_cookies"
        const val KEY_DO_NOT_TRACK = "pref_do_not_track"
        const val KEY_SCRIPT_BLOCKING_DOMAINS = "pref_script_blocking_domains"
        const val KEY_ASK_BEFORE_DOWNLOAD = "pref_ask_before_download"
        const val KEY_DESKTOP_MODE = "pref_desktop_mode"
        const val KEY_JAVASCRIPT_ENABLED = "pref_javascript_enabled"
        const val KEY_BLOCKED_REQUESTS_COUNT = "pref_blocked_requests_count"
        const val KEY_FILTER_UPDATED = "pref_filter_updated"
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
        const val KEY_DEFAULT_ENGINE_MIGRATED = "pref_default_engine_google_migrated"



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
