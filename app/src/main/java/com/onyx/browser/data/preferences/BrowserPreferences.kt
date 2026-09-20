package com.onyx.browser.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.onyx.browser.data.model.SearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class BrowserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _blockedRequestsFlow = MutableStateFlow(getBlockedRequestsCount())
    val blockedRequestsFlow: StateFlow<Long> = _blockedRequestsFlow.asStateFlow()

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

    var isAdBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ADBLOCK_ENABLED, value).apply()
        }

    var isCosmeticFilteringEnabled: Boolean
        get() = prefs.getBoolean(KEY_COSMETIC_FILTERING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_COSMETIC_FILTERING, value).apply()
        }

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
        when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_DYNAMIC, THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    companion object {
        private const val PREF_NAME = "onyx_browser_prefs"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_DYNAMIC = 3

        const val KEY_SEARCH_ENGINE = "pref_search_engine"
        const val KEY_THEME_MODE = "pref_theme_mode"
        const val KEY_ADBLOCK_ENABLED = "pref_adblock_enabled"
        const val KEY_COSMETIC_FILTERING = "pref_cosmetic_filtering"
        const val KEY_ASK_BEFORE_DOWNLOAD = "pref_ask_before_download"
        const val KEY_DESKTOP_MODE = "pref_desktop_mode"
        const val KEY_JAVASCRIPT_ENABLED = "pref_javascript_enabled"
        const val KEY_BLOCKED_REQUESTS_COUNT = "pref_blocked_requests_count"
        const val KEY_FILTER_UPDATED = "pref_filter_updated"

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
