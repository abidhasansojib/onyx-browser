package com.onyx.browser.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.model.QuickActionItem
import com.onyx.browser.data.model.ShortcutItem
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

    private val _quickActionsFlow = MutableStateFlow<List<String>>(emptyList())
    val quickActionsFlow: StateFlow<List<String>> = _quickActionsFlow.asStateFlow()

    init {
        _shortcutsFlow.value = getShortcuts()
        _quickActionsFlow.value = getQuickActionOrder()
    }

    private val blockedCounter = AtomicLong(getBlockedRequestsCount())

    var searchEngine: SearchEngine
        get() {
            val id = prefs.getString(KEY_SEARCH_ENGINE, SearchEngine.BRAVE.id)
            return SearchEngine.fromId(id)
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

    var askBeforeDownload: Boolean
        get() = prefs.getBoolean(KEY_ASK_BEFORE_DOWNLOAD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ASK_BEFORE_DOWNLOAD, value).apply()
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

    fun getBlockedRequestsCount(): Long {
        return prefs.getLong(KEY_BLOCKED_REQUESTS_COUNT, 0L)
    }

    fun incrementBlockedRequests(): Long {
        val newCount = blockedCounter.incrementAndGet()
        prefs.edit().putLong(KEY_BLOCKED_REQUESTS_COUNT, newCount).apply()
        _blockedRequestsFlow.value = newCount
        return newCount
    }

    fun resetBlockedRequests() {
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
                list
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
            current[index] = updated
            saveShortcuts(current)
        }
    }

    fun deleteShortcut(id: String) {
        val current = getShortcuts().toMutableList()
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
        val serialized = order.joinToString(",")
        prefs.edit().putString(KEY_QUICK_ACTION_ORDER, serialized).apply()
        _quickActionsFlow.value = order
    }

    companion object {
        private const val PREF_NAME = "onyx_browser_prefs"

        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2
        const val THEME_DYNAMIC = 0 // Legacy alias to THEME_SYSTEM

        /** Blocking level constants (used with KEY_BLOCKING_LEVEL) */
        const val BLOCKING_STANDARD = 0   // third-party ads & trackers only
        const val BLOCKING_AGGRESSIVE = 1 // all ads & trackers incl. first-party

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
