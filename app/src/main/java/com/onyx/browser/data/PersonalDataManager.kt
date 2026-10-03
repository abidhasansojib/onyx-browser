package com.onyx.browser.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.web.LocalFileLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PersonalDataManager {

    private val scope = CoroutineScope(Dispatchers.IO + NonCancellable)

    /**
     * Deeply purges all personal browsing data:
     * - Full browsing history
     * - Web cookies & storage
     * - WebView HTTP cache
     * - Favicon disk & memory cache
     * - Tab thumbnail disk cache
     * - Local web archives & temporary downloads
     */
    fun clearAllPersonalData(context: Context, clearTabs: Boolean = false, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                // 1. Clear History Database
                val db = AppDatabase.getInstance(appContext)
                db.historyDao().clearAllHistory()
                if (clearTabs) {
                    db.tabDao().clearNormalTabs()
                }
            } catch (_: Exception) {}

            try {
                // 2. Clear Cookies
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            } catch (_: Exception) {}

            try {
                // 3. Clear Web Storage (LocalStorage, IndexedDB, etc.)
                WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) {}

            // 4. Clear WebView Cache on Main thread
            withContext(Dispatchers.Main) {
                try {
                    WebView(appContext).clearCache(true)
                } catch (_: Exception) {}
            }

            // 5. Clean Disk Caches & Temporary Artifacts
            try {
                FaviconManager.clearCache(appContext)
                File(appContext.cacheDir, "favicons").deleteRecursively()
                File(appContext.cacheDir, "tab_thumbnails").deleteRecursively()
                File(appContext.cacheDir, "web_archives").deleteRecursively()
                File(appContext.cacheDir, "onyx_downloads").deleteRecursively()
                File(appContext.cacheDir, "install_pending.apk").delete()
                LocalFileLoader.cleanupAllPreviews(appContext)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Checks if the scheduled periodic auto-clear interval has elapsed (60 mins, 1 day, 7 days)
     * and performs the cleanup if due.
     */
    fun checkAndPerformScheduledAutoClear(context: Context) {
        val appContext = context.applicationContext
        val prefs = BrowserPreferences.getInstance(appContext)
        val interval = prefs.autoClearInterval

        if (interval == BrowserPreferences.AUTO_CLEAR_NEVER || interval == BrowserPreferences.AUTO_CLEAR_ON_EXIT) {
            return
        }

        val now = System.currentTimeMillis()
        val last = prefs.lastAutoClearTimestamp

        val threshold = when (interval) {
            BrowserPreferences.AUTO_CLEAR_60_MINS -> 60 * 60 * 1000L
            BrowserPreferences.AUTO_CLEAR_1_DAY -> 24 * 60 * 60 * 1000L
            BrowserPreferences.AUTO_CLEAR_7_DAYS -> 7 * 24 * 60 * 60 * 1000L
            else -> Long.MAX_VALUE
        }

        // Initialize baseline timestamp if never set
        if (last <= 0L) {
            prefs.lastAutoClearTimestamp = now
            return
        }

        if (now - last >= threshold) {
            prefs.lastAutoClearTimestamp = now
            clearAllPersonalData(appContext)
        }
    }

    /**
     * Invoked when the user exits the app. If the auto-clear preference is set to
     * [BrowserPreferences.AUTO_CLEAR_ON_EXIT], executes a full data purge.
     */
    fun performExitAutoClear(context: Context) {
        val appContext = context.applicationContext
        val prefs = BrowserPreferences.getInstance(appContext)
        if (prefs.autoClearInterval == BrowserPreferences.AUTO_CLEAR_ON_EXIT) {
            prefs.lastAutoClearTimestamp = System.currentTimeMillis()
            clearAllPersonalData(appContext, clearTabs = true)
        }
    }
}
