package com.onyx.browser.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.web.LocalFileLoader
import com.onyx.browser.web.OnyxWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

object ClearDataOnExitHelper {

    /**
     * Synchronously and comprehensively purges browsing data according to user preferences or explicit flags
     * (history, normal tabs, cookies across default and multi-profiles, WebStorage, WebView caches,
     * preview archives, and thumbnails).
     */
    fun clearAllBrowsingDataSync(
        context: Context,
        tabManager: TabCoordinator? = null,
        clearTabs: Boolean? = null,
        clearHistory: Boolean? = null,
        clearCookies: Boolean? = null,
        clearCache: Boolean? = null
    ) {
        val appContext = context.applicationContext
        val prefs = BrowserPreferences.getInstance(appContext)

        val doClearTabs = clearTabs ?: prefs.isClearOnExitTabsEnabled
        val doClearHistory = clearHistory ?: prefs.isClearOnExitHistoryEnabled
        val doClearCookies = clearCookies ?: prefs.isClearOnExitCookiesEnabled
        val doClearCache = clearCache ?: prefs.isClearOnExitCacheEnabled

        // 1. Synchronously purge Room Database (History)
        if (doClearHistory) {
            try {
                val db = AppDatabase.getInstance(appContext)
                runBlocking(Dispatchers.IO) {
                    try {
                        db.historyDao().clearAllHistory()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        // 2. Clear Tabs (Room Database, active WebViews, tab states & thumbnails)
        if (doClearTabs) {
            try {
                val db = AppDatabase.getInstance(appContext)
                runBlocking(Dispatchers.IO) {
                    try {
                        db.tabDao().clearNormalTabs()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            val tm = tabManager ?: TabCoordinator.activeInstance
            val clearWebViewsAction = {
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

            try {
                val thumbDir = File(appContext.cacheDir, "tab_thumbnails")
                if (thumbDir.exists()) thumbDir.deleteRecursively()
            } catch (_: Throwable) {}

            try {
                val stateDir = File(appContext.filesDir, "tab_states")
                if (stateDir.exists()) stateDir.deleteRecursively()
            } catch (_: Throwable) {}

            try {
                prefs.lastActiveTabId = null
            } catch (_: Throwable) {}
        }

        // 3. Clear Chromium Cookies across Default and Multi-Profiles, WebStorage & HTTP Auth
        if (doClearCookies) {
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

            try {
                WebStorage.getInstance().deleteAllData()
            } catch (_: Throwable) {}

            try {
                val webViewDb = WebViewDatabase.getInstance(appContext)
                webViewDb.clearHttpAuthUsernamePassword()
                webViewDb.clearFormData()
            } catch (_: Throwable) {}

            try {
                prefs.clearAllCustomBlockedRules()
            } catch (_: Throwable) {}
        }

        // 4. Delete file caches on disk and clear active WebView caches
        if (doClearCache) {
            val tm = tabManager ?: TabCoordinator.activeInstance
            val clearCacheAction = {
                try {
                    tm?.getAllWebViews()?.forEach { wv ->
                        try {
                            wv.clearCache(true)
                            wv.clearFormData()
                            wv.clearSslPreferences()
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }

            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                clearCacheAction()
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    clearCacheAction()
                    latch.countDown()
                }
                try {
                    latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: Throwable) {}
            }

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
                val wvCacheDir = File(appContext.cacheDir, "org.chromium.android_webview")
                if (wvCacheDir.exists()) wvCacheDir.deleteRecursively()
            } catch (_: Throwable) {}
        }
    }
}
