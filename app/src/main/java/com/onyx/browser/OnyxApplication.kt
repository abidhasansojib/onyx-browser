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

        // Apply saved theme early in process startup
        BrowserPreferences.getInstance(this).applyTheme()

        // Initialize Room Database
        AppDatabase.getInstance(this)

        // Initialize AdBlockEngine in background thread with pre-compiled filters
        applicationScope.launch(Dispatchers.IO) {
            AdBlockEngine.initialize(applicationContext)
        }
    }
}
