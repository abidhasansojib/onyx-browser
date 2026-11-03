package com.onyx.browser.data

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.onyx.browser.data.local.ClearDataOnExitHelper
import com.onyx.browser.data.preferences.BrowserPreferences

class ClearOnExitService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val prefs = BrowserPreferences.getInstance(applicationContext)
            if (prefs.isClearOnExitEnabled) {
                prefs.hasPendingExitClearance = true
                ClearDataOnExitHelper.clearAllBrowsingDataSync(applicationContext)
                prefs.hasPendingExitClearance = false
            }
        } catch (_: Throwable) {}
        stopSelf()
    }
}
