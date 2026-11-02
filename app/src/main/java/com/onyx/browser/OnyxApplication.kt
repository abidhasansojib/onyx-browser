package com.onyx.browser

import android.app.Application
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class OnyxApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Install global uncaught exception logger to surface root causes in logcat
        try {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e("OnyxCrash", "Uncaught exception on thread [${thread.name}]: ${throwable.message}", throwable)
                var cause = throwable.cause
                while (cause != null) {
                    Log.e("OnyxCrash", "  Caused by [${cause.javaClass.name}]: ${cause.message}", cause)
                    cause = cause.cause
                }
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (_: Throwable) {}

        // Enable vector drawable support across all API levels
        androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        // Pre-warm Chromium native runtime asynchronously off main thread
        preWarmChromiumRuntimeAsync()

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
                com.onyx.browser.data.filter.FilterListManager.ensureDefaultFiltersCached(applicationContext)
                com.onyx.browser.data.filter.FilterListManager.checkAndAutoUpdateFilters(applicationContext)
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

        // Enable Chromium WebView remote debugging via chrome://inspect or edge://inspect over USB (Prerelease/Debug builds)
        if (BuildConfig.DEBUG || BuildConfig.IS_PRERELEASE) {
            try {
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            } catch (t: Throwable) {
                Log.e("OnyxApplication", "Failed to enable WebContentsDebugging", t)
            }
        }

        // Initialize Google Safe Browsing for phishing/malware interstitial pages
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.START_SAFE_BROWSING)) {
                androidx.webkit.WebViewCompat.startSafeBrowsing(this) { success ->
                    Log.d("OnyxApplication", "Safe Browsing initialized: $success")
                }
            }
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Failed to initialize Safe Browsing", t)
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

    /**
     * Pre-warms the Chromium runtime (libwebviewchromium.so, V8 JavaScript engine, and GPU sandbox)
     * asynchronously on a background worker thread via [WebViewCompat.startUpWebView].
     * Eliminates the 200–400ms main-thread hitch during initial cold launch.
     */
    private fun preWarmChromiumRuntimeAsync() {
        try {
            val backgroundExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "OnyxChromiumPreWarm").apply { priority = Thread.NORM_PRIORITY - 1 }
            }
            val startUpConfig = WebViewStartUpConfig.Builder(backgroundExecutor)
                .setShouldRunUiThreadStartUpTasks(true)
                .build()
            WebViewCompat.startUpWebView(
                this,
                startUpConfig,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        Log.i("OnyxApplication", "Chromium runtime pre-warmed successfully off main thread")
                    }

                    override fun onError(error: WebViewStartupException) {
                        Log.w("OnyxApplication", "Chromium runtime async pre-warm completed with fallback: ${error.message}")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w("OnyxApplication", "Chromium async pre-warm not available on this device: ${t.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            when {
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                    AdBlockEngine.clearDecisionCache()
                    com.onyx.browser.web.AdBlockDomainManager.clearCache()
                    com.onyx.browser.ui.browser.TabManager.activeInstance?.onTrimMemory(level)
                }
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                    AdBlockEngine.clearDecisionCache()
                    com.onyx.browser.web.AdBlockDomainManager.clearCache()
                    com.onyx.browser.ui.browser.TabManager.activeInstance?.onTrimMemory(level)
                }
            }
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Error during onTrimMemory", t)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            AdBlockEngine.clearDecisionCache()
            com.onyx.browser.web.AdBlockDomainManager.clearCache()
            com.onyx.browser.ui.browser.TabManager.activeInstance?.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        } catch (t: Throwable) {
            Log.e("OnyxApplication", "Error during onLowMemory", t)
        }
    }
}

