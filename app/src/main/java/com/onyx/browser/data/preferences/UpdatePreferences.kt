package com.onyx.browser.data.preferences

import android.content.SharedPreferences

/**
 * Domain-scoped preference store for update state: app release cache, and the
 * filter list and userscript auto-update schedules. Shares the browser preference file.
 */
class UpdatePreferences(private val prefs: SharedPreferences) {

    var isFilterAutoUpdateEnabled: Boolean
        get() = prefs.getBoolean(BrowserPreferences.KEY_FILTER_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(BrowserPreferences.KEY_FILTER_AUTO_UPDATE, value).apply()

    var filterAutoUpdateIntervalHours: Int
        get() = prefs.getInt(BrowserPreferences.KEY_FILTER_UPDATE_INTERVAL, 12)
        set(value) = prefs.edit().putInt(BrowserPreferences.KEY_FILTER_UPDATE_INTERVAL, value).apply()

    var filterLastUpdatedTime: Long
        get() = prefs.getLong(BrowserPreferences.KEY_FILTER_UPDATED, 0L)
        set(value) {
            prefs.edit().putLong(BrowserPreferences.KEY_FILTER_UPDATED, value).apply()
        }

    var isUserScriptAutoUpdateEnabled: Boolean
        get() = prefs.getBoolean(BrowserPreferences.KEY_USERSCRIPT_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(BrowserPreferences.KEY_USERSCRIPT_AUTO_UPDATE, value).apply()

    var userScriptAutoUpdateIntervalHours: Int
        get() = prefs.getInt(BrowserPreferences.KEY_USERSCRIPT_UPDATE_INTERVAL, 12)
        set(value) = prefs.edit().putInt(BrowserPreferences.KEY_USERSCRIPT_UPDATE_INTERVAL, value).apply()

    var userScriptLastUpdateCheckTime: Long
        get() = prefs.getLong(BrowserPreferences.KEY_USERSCRIPT_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(BrowserPreferences.KEY_USERSCRIPT_LAST_CHECK, value).apply()

    var notifiedUpdateTag: String
        get() = prefs.getString(BrowserPreferences.KEY_NOTIFIED_UPDATE_TAG, "") ?: ""
        set(value) = prefs.edit().putString(BrowserPreferences.KEY_NOTIFIED_UPDATE_TAG, value).apply()

    var lastAppUpdateCheckTime: Long
        get() = prefs.getLong(BrowserPreferences.KEY_LAST_APP_UPDATE_CHECK_TIME, 0L)
        set(value) = prefs.edit().putLong(BrowserPreferences.KEY_LAST_APP_UPDATE_CHECK_TIME, value).apply()

    var dismissedUpdateTag: String
        get() = prefs.getString(BrowserPreferences.KEY_DISMISSED_UPDATE_TAG, "") ?: ""
        set(value) = prefs.edit().putString(BrowserPreferences.KEY_DISMISSED_UPDATE_TAG, value).apply()

    var cachedUpdateTag: String
        get() = prefs.getString(BrowserPreferences.KEY_CACHED_UPDATE_TAG, "") ?: ""
        set(value) = prefs.edit().putString(BrowserPreferences.KEY_CACHED_UPDATE_TAG, value).apply()

    var cachedUpdateTitle: String
        get() = prefs.getString(BrowserPreferences.KEY_CACHED_UPDATE_TITLE, "") ?: ""
        set(value) = prefs.edit().putString(BrowserPreferences.KEY_CACHED_UPDATE_TITLE, value).apply()

    var cachedUpdateNotes: String
        get() = prefs.getString(BrowserPreferences.KEY_CACHED_UPDATE_NOTES, "") ?: ""
        set(value) = prefs.edit().putString(BrowserPreferences.KEY_CACHED_UPDATE_NOTES, value).apply()

    var cachedUpdateAssetName: String
        get() = prefs.getString(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_NAME, "") ?: ""
        set(value) = prefs.edit().putString(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_NAME, value).apply()

    var cachedUpdateAssetUrl: String
        get() = prefs.getString(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_URL, "") ?: ""
        set(value) = prefs.edit().putString(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_URL, value).apply()

    var cachedUpdateAssetSize: Long
        get() = prefs.getLong(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_SIZE, 0L)
        set(value) = prefs.edit().putLong(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_SIZE, value).apply()

    fun clearCachedUpdate() {
        prefs.edit()
            .remove(BrowserPreferences.KEY_CACHED_UPDATE_TAG)
            .remove(BrowserPreferences.KEY_CACHED_UPDATE_TITLE)
            .remove(BrowserPreferences.KEY_CACHED_UPDATE_NOTES)
            .remove(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_NAME)
            .remove(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_URL)
            .remove(BrowserPreferences.KEY_CACHED_UPDATE_ASSET_SIZE)
            .apply()
    }
}
