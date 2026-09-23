package com.onyx.browser.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.onyx.browser.R

object SearchWidgetManager {

    fun isPinSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
            return appWidgetManager?.isRequestPinAppWidgetSupported == true
        }
        return false
    }

    fun requestPinSearchWidget(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
            if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                val provider = ComponentName(context, SearchWidgetProvider::class.java)

                val callbackIntent = Intent(context, SearchWidgetPinnedReceiver::class.java)
                val successCallback = PendingIntent.getBroadcast(
                    context,
                    0,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                try {
                    val pinned = appWidgetManager.requestPinAppWidget(provider, null, successCallback)
                    if (!pinned) {
                        Toast.makeText(context, R.string.search_widget_pin_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "${context.getString(R.string.search_widget_pin_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
        Toast.makeText(context, R.string.search_widget_manual_add_guide, Toast.LENGTH_LONG).show()
    }
}
