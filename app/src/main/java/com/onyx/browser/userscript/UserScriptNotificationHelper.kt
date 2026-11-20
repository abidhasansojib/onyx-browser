package com.onyx.browser.userscript

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles posting authentic Android status bar notifications for userscripts calling
 * `GM_notification` or `GM.notification`, with graceful fallback to Toast when notifications
 * are disabled or permissions are not granted.
 */
object UserScriptNotificationHelper {

    const val CHANNEL_USERSCRIPT = "onyx_userscript_alerts"
    private val notificationSeq = AtomicInteger(8000)

    fun initChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_USERSCRIPT,
                context.getString(R.string.userscripts_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.userscripts_notification_channel_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun showNotification(
        context: Context,
        scriptName: String,
        title: String,
        text: String,
        origin: String = ""
    ) {
        val displayTitle = title.ifBlank { scriptName.ifBlank { "Userscript Alert" } }
        val displayText = text.ifBlank { displayTitle }

        try {
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                initChannel(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null) {
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        (System.currentTimeMillis() % 10000).toInt(),
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val builder = NotificationCompat.Builder(context, CHANNEL_USERSCRIPT)
                        .setSmallIcon(R.drawable.ic_code)
                        .setContentTitle(displayTitle)
                        .setContentText(displayText)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)

                    if (origin.isNotBlank()) {
                        builder.setSubText(origin)
                    } else if (scriptName.isNotBlank() && scriptName != displayTitle) {
                        builder.setSubText(scriptName)
                    }

                    val notifId = notificationSeq.getAndUpdate { curr -> if (curr >= 9000) 8000 else curr + 1 }
                    nm.notify(notifId, builder.build())
                    return
                }
            }
        } catch (_: Exception) {}

        // Fallback to Toast if notifications disabled or failed
        val toastMsg = if (text.isNotBlank() && displayTitle != text) "$displayTitle: $text" else displayTitle
        Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show()
    }
}
