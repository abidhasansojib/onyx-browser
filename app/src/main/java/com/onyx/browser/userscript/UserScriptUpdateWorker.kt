package com.onyx.browser.userscript

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UserScriptUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting periodic background userscript update check (attempt $runAttemptCount)...")
            val manager = UserScriptManager.getInstance(applicationContext)
            val results = manager.checkAndUpdateAll()
            val updatedScripts = results.filter { it.second is UpdateResult.Updated }
            val updatedCount = updatedScripts.size
            Log.i(TAG, "Periodic userscript update check completed; updated $updatedCount scripts")

            // Notify user of which scripts were updated in the background
            if (updatedCount > 0) {
                val names = updatedScripts.joinToString(", ") { it.first.name }
                val title = if (updatedCount == 1) "Userscript Updated" else "$updatedCount Userscripts Updated"
                val text = if (updatedCount == 1) {
                    val r = updatedScripts.first().second as UpdateResult.Updated
                    "${updatedScripts.first().first.name}: v${r.from} → v${r.to}"
                } else {
                    names
                }
                UserScriptNotificationHelper.showNotification(
                    context = applicationContext,
                    scriptName = "Userscript Updater",
                    title = title,
                    text = text,
                    origin = ""
                )
            }

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Periodic userscript update threw exception", t)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "UserScriptUpdateWorker"
        const val WORK_NAME = "OnyxUserScriptUpdate"
        private const val MAX_RETRIES = 3

        /**
         * Schedules the periodic background userscript update worker (default 7 days)
         * with connected network and battery not low constraints.
         */
        fun schedule(context: Context, forceReschedule: Boolean = false) {
            try {
                val appContext = context.applicationContext
                val workManager = WorkManager.getInstance(appContext)

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<UserScriptUpdateWorker>(
                    7, TimeUnit.DAYS
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()

                val policy = if (forceReschedule) {
                    ExistingPeriodicWorkPolicy.UPDATE
                } else {
                    ExistingPeriodicWorkPolicy.KEEP
                }

                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    policy,
                    workRequest
                )
                Log.i(TAG, "Scheduled userscript update worker every 7 days (policy=$policy)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to schedule userscript update work", t)
            }
        }
    }
}
