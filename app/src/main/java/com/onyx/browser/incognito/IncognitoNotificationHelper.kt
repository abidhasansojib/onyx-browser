package com.onyx.browser.incognito

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.onyx.browser.R

object IncognitoNotificationHelper {

    const val CHANNEL_INCOGNITO = "onyx_incognito_tabs"
    const val NOTIFICATION_ID = 4041
    const val ACTION_CLOSE_ALL_INCOGNITO = "com.onyx.browser.action.CLOSE_ALL_INCOGNITO"
    private const val REQUEST_CODE_CLOSE_INCOGNITO = 2024

    fun initChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_INCOGNITO,
                context.getString(R.string.incognito_tabs_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when incognito tabs are open and allows closing them"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun updateNotification(context: Context, count: Int) {
        if (count <= 0) {
            dismissNotification(context)
            return
        }
        showNotification(context, count)
    }

    fun showNotification(context: Context, count: Int) {
        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            initChannel(context)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val closeIntent = Intent(context, IncognitoNotificationReceiver::class.java).apply {
                action = ACTION_CLOSE_ALL_INCOGNITO
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_CLOSE_INCOGNITO,
                closeIntent,
                flags
            )

            val title = context.getString(R.string.incognito_tabs_notification_title)
            val contentText = context.getString(R.string.close_all_incognito_tabs)
            val subText = if (count == 1) {
                context.getString(R.string.incognito_tabs_open_single)
            } else {
                context.getString(R.string.incognito_tabs_open_multi, count)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_INCOGNITO)
                .setSmallIcon(R.drawable.ic_incognito)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSubText(subText)
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_tab_close,
                    context.getString(R.string.close_all_incognito_tabs),
                    pendingIntent
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun dismissNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
