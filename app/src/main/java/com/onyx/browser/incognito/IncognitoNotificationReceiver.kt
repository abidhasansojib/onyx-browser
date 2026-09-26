package com.onyx.browser.incognito

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.onyx.browser.R
import com.onyx.browser.ui.browser.TabManager

class IncognitoNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == IncognitoNotificationHelper.ACTION_CLOSE_ALL_INCOGNITO) {
            Handler(Looper.getMainLooper()).post {
                TabManager.activeInstance?.closeAllTabs(incognitoOnly = true)
                IncognitoNotificationHelper.dismissNotification(context)
                try {
                    Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.all_incognito_tabs_closed),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {}
            }
        }
    }
}
