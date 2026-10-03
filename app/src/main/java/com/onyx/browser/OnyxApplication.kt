package com.onyx.browser

import android.app.Application
import android.util.Log
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OnyxApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Enable vector drawable support across all API levels
        androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        // Apply saved theme early in process startup
        try {
            val prefs = BrowserPreferences.getInstance(this)
            prefs.applyTheme()
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Failed to apply theme in onCreate", t)
        }

        // Initialize Room Database
        // Remove legacy unencrypted database file (migration to SQLCipher encrypted DB)
        try {
            val oldDb = getDatabasePath("onyx_browser.db")
            if (oldDb.exists()) {
                oldDb.delete()
                getDatabasePath("onyx_browser.db-wal").delete()
                getDatabasePath("onyx_browser.db-shm").delete()
            }
        } catch (_: Exception) {}

        try {
            AppDatabase.getInstance(this)
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Failed to initialize AppDatabase in onCreate", t)
        }

        // Initialize AdBlockEngine in background thread with pre-compiled filters
        applicationScope.launch(Dispatchers.IO) {
            try {
                com.onyx.browser.data.filter.FilterListManager.autoEnableRegionalListsForLocale(applicationContext)
                AdBlockEngine.initialize(applicationContext)
            } catch (t: Throwable) {
                Log.e("OnyxApplication", "Failed to initialize AdBlockEngine in background", t)
            }
            try {
                com.onyx.browser.web.LocalFileLoader.cleanupAllPreviews(applicationContext)
            } catch (_: Exception) {}
        }

        // Initialize ServiceWorker ad/tracker interception
        try {
            com.onyx.browser.web.AdBlockServiceWorkerHelper.initialize(this)
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Failed to initialize ServiceWorker interception", t)
        }

        // Initialize Incognito tabs notification channel
        try {
            com.onyx.browser.incognito.IncognitoNotificationHelper.initChannel(this)
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Failed to initialize Incognito notification channel", t)
        }

        // Enable Chromium WebView remote debugging via chrome://inspect or edge://inspect over USB
        try {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Failed to enable WebContentsDebugging", t)
        }

        // Schedule periodic background filter update worker (24h interval, Wi-Fi only, battery not low)
        try {
            scheduleFilterUpdateWork()
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Failed to schedule filter update worker", t)
        }
    }

    private fun scheduleFilterUpdateWork() {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.onyx.browser.data.filter.FilterUpdateWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            com.onyx.browser.data.filter.FilterUpdateWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

