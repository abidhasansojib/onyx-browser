package com.onyx.browser.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import java.util.concurrent.TimeUnit

/**
 * Checks for a new app release every 12 hours in the background and posts a notification,
 * so users learn about updates without opening About.
 */
class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val info = AppUpdateManager.checkSilentlyIfDue(applicationContext, force = true)
            if (info != null) notifyIfNew(applicationContext, info)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Background app update check failed", t)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun notifyIfNew(context: Context, info: UpdateInfo) {
        val prefs = BrowserPreferences.getInstance(context)
        if (prefs.notifiedUpdateTag == info.tagName) return
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
            prefs.notifiedUpdateTag = info.tagName
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val TAG = "AppUpdateWorker"
        const val WORK_NAME = "OnyxAppUpdateCheck"
        private const val CHANNEL_ID = "onyx_app_updates"
        private const val NOTIFICATION_ID = 7001

        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to schedule app update worker", t)
            }
        }
    }
}
