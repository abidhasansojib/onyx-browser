package com.onyx.browser.data.filter

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.onyx.browser.data.preferences.BrowserPreferences
import java.util.concurrent.TimeUnit

class FilterUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting periodic background filter update worker...")
            val success = FilterListManager.checkAndAutoUpdateFilters(applicationContext, force = true)
            Log.i(TAG, "Periodic filter update completed with success=$success")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Periodic filter update failed, will retry later", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FilterUpdateWorker"
        const val WORK_NAME = "OnyxFilterListUpdate"

        /**
         * Schedules or reschedules the periodic background filter update worker with the
         * user's configured interval and active network constraints (both Wi-Fi & Cellular).
         */
        fun schedule(context: Context, forceReschedule: Boolean = false) {
            try {
                val appContext = context.applicationContext
                val prefs = BrowserPreferences.getInstance(appContext)
                val workManager = WorkManager.getInstance(appContext)

                if (!prefs.isFilterAutoUpdateEnabled) {
                    Log.i(TAG, "Filter auto-update is disabled; cancelling scheduled background worker")
                    workManager.cancelUniqueWork(WORK_NAME)
                    return
                }

                val intervalHours = maxOf(1L, prefs.filterAutoUpdateIntervalHours.toLong())
                Log.i(TAG, "Scheduling filter update worker every ${intervalHours}h (forceReschedule=$forceReschedule)")

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<FilterUpdateWorker>(
                    intervalHours, TimeUnit.HOURS
                )
                    .setConstraints(constraints)
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
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to schedule filter update work", t)
            }
        }
    }
}
