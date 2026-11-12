package com.onyx.browser.incognito

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.onyx.browser.R
import com.onyx.browser.ui.browser.TabCoordinator

class IncognitoNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == IncognitoNotificationHelper.ACTION_CLOSE_ALL_INCOGNITO) {
            val pendingResult = goAsync()
            val active = TabCoordinator.activeInstance
            if (active != null) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        active.closeAllTabs(incognitoOnly = true)
                    } finally {
                        IncognitoNotificationHelper.dismissNotification(context)
                        showToast(context)
                        pendingResult.finish()
                    }
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    try {
                        TabCoordinator.purgeIncognitoProfileStatic()
                    } finally {
                        IncognitoNotificationHelper.dismissNotification(context)
                        showToast(context)
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun showToast(context: Context) {
        try {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.all_incognito_tabs_closed),
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {}
    }
}
