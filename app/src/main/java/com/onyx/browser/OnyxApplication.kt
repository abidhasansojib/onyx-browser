package com.onyx.browser

import android.app.Application
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
        val prefs = BrowserPreferences.getInstance(this)
        prefs.applyTheme()

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
        AppDatabase.getInstance(this)

        // Initialize AdBlockEngine in background thread with pre-compiled filters
        applicationScope.launch(Dispatchers.IO) {
            AdBlockEngine.initialize(applicationContext)
        }

        // Initialize ServiceWorker ad/tracker interception
        com.onyx.browser.web.AdBlockServiceWorkerHelper.initialize(this)
    }
}
