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

        // 2. Clear active WebView caches, close tabs, and purge tab undo stack
        val tm = tabManager ?: TabManager.activeInstance
        val clearWebViewsAction = {
            try {
                tm?.getAllWebViews()?.forEach { wv ->
                    try {
                        wv.clearCache(true)
                        wv.clearFormData()
                        wv.clearSslPreferences()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            try {
                tm?.clearAllTabsAndDataForExit()
            } catch (_: Throwable) {}
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            clearWebViewsAction()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                clearWebViewsAction()
                latch.countDown()
            }
            try {
                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
        }

        // 3. Clear Chromium Cookies across Default and Multi-Profiles
        try {
            val cookieLatch = java.util.concurrent.CountDownLatch(1)
            val cm = CookieManager.getInstance()
            cm.removeAllCookies {
                try {
                    cm.flush()
                } catch (_: Throwable) {}
                cookieLatch.countDown()
            }
            cm.removeSessionCookies(null)
            try {
                cookieLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
            cm.flush()

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

        // 5. Delete file caches on disk (previews, favicons, archives, thumbnails, webview cache)
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

        try {
            val stateDir = File(appContext.filesDir, "tab_states")
            if (stateDir.exists()) stateDir.deleteRecursively()
        } catch (_: Throwable) {}

        // 7. Reset session preferences
        try {
            val prefs = BrowserPreferences.getInstance(appContext)
            prefs.lastActiveTabId = null
            prefs.clearAllCustomBlockedRules()
        } catch (_: Throwable) {}
    }
}
