package com.onyx.browser.data.local

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabManager
import com.onyx.browser.web.LocalFileLoader
import com.onyx.browser.web.OnyxWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

object ClearDataOnExitHelper {

    /**
     * Synchronously and comprehensively purges all browsing data (history, normal & incognito tabs,
     * cookies across default and multi-profiles, WebStorage, WebView caches, HTTP auth, form data,
     * preview archives, and thumbnails).
     */
    fun clearAllBrowsingDataSync(context: Context, tabManager: TabManager? = null) {
        val appContext = context.applicationContext

        // 1. Synchronously purge Room Database (History and Normal Tabs)
        try {
            val db = AppDatabase.getInstance(appContext)
            runBlocking(Dispatchers.IO) {
                try {
                    db.historyDao().clearAllHistory()
                } catch (_: Throwable) {}
                try {
                    db.tabDao().clearNormalTabs()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 2. Close and purge in-memory Tabs, WebViews, and Tab undo stack
        val tm = tabManager ?: TabManager.activeInstance
        try {
            tm?.clearAllTabsAndDataForExit()
        } catch (_: Throwable) {}

        // 3. Clear Chromium Cookies across Default and Multi-Profiles
        try {
            CookieManager.getInstance().removeAllCookies {
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {}
            }
            CookieManager.getInstance().removeSessionCookies {
                try {
                    CookieManager.getInstance().flush()
                } catch (_: Throwable) {}
            }
            CookieManager.getInstance().flush()

            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    val store = ProfileStore.getInstance()
                    val incognitoProfile = store.getProfile(OnyxWebView.INCOGNITO_PROFILE_NAME)
                    if (incognitoProfile != null) {
                        incognitoProfile.cookieManager.removeAllCookies(null)
                        incognitoProfile.cookieManager.flush()
                        incognitoProfile.webStorage.deleteAllData()
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 4. Clear WebStorage (LocalStorage, IndexedDB, Web SQL) & WebViewDatabase (Form Data, Passwords)
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {}

        try {
            val webViewDb = WebViewDatabase.getInstance(appContext)
            webViewDb.clearHttpAuthUsernamePassword()
            webViewDb.clearFormData()
        } catch (_: Throwable) {}

        // 5. Clear active WebView caches
        try {
            tm?.getAllWebViews()?.forEach { wv ->
                try {
                    wv.clearCache(true)
                    wv.clearFormData()
                    wv.clearSslPreferences()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // 6. Delete file caches on disk (previews, favicons, archives, thumbnails, webview cache)
        try {
            LocalFileLoader.cleanupAllPreviews(appContext)
        } catch (_: Throwable) {}

        try {
            FaviconManager.clearCache(appContext)
        } catch (_: Throwable) {}

        try {
            val archivesDir = File(appContext.cacheDir, "web_archives")
            if (archivesDir.exists()) archivesDir.deleteRecursively()
        } catch (_: Throwable) {}

        try {
            val thumbDir = File(appContext.cacheDir, "tab_thumbnails")
            if (thumbDir.exists()) thumbDir.deleteRecursively()
        } catch (_: Throwable) {}

        try {
            val wvCacheDir = File(appContext.cacheDir, "org.chromium.android_webview")
            if (wvCacheDir.exists()) wvCacheDir.deleteRecursively()
        } catch (_: Throwable) {}

        // 7. Reset session preferences
        try {
            val prefs = BrowserPreferences.getInstance(appContext)
            prefs.lastActiveTabId = null
            prefs.clearAllCustomBlockedRules()
        } catch (_: Throwable) {}
    }
}
