package com.onyx.browser.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences

/** Posts the "new version available" notification once per release tag. */
object AppUpdateNotifier {
    private const val CHANNEL_ID = "onyx_app_updates"
    private const val NOTIFICATION_ID = 7001

    fun notifyIfNew(context: Context, info: UpdateInfo) {
        val prefs = BrowserPreferences.getInstance(context)
        if (prefs.updates.notifiedUpdateTag == info.tagName) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_snackbar_msg, info.tagName))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
            prefs.updates.notifiedUpdateTag = info.tagName
        } catch (_: SecurityException) {
        }
    }
}
