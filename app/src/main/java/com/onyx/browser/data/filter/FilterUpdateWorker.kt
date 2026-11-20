package com.onyx.browser.data.filter

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
import com.onyx.browser.data.preferences.BrowserPreferences
import java.util.concurrent.TimeUnit

class FilterUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting periodic background filter update worker (attempt $runAttemptCount)...")
            val success = FilterListManager.checkAndAutoUpdateFilters(applicationContext, force = true)
            if (success) {
                Log.i(TAG, "Periodic filter update completed with success=true")
                Result.success()
            } else if (runAttemptCount < MAX_RETRIES) {
                Log.w(TAG, "Periodic filter update returned false (attempt $runAttemptCount), scheduling retry")
                Result.retry()
            } else {
                Log.w(TAG, "Periodic filter update failed after $MAX_RETRIES attempts; will resume on next cycle")
                Result.success()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Periodic filter update threw exception (attempt $runAttemptCount)", t)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "FilterUpdateWorker"
        const val WORK_NAME = "OnyxFilterListUpdate"
        private const val MAX_RETRIES = 3

        /**
         * Schedules or reschedules the periodic background filter update worker with the
         * user's configured interval and active network constraints (both Wi-Fi & Cellular).
         */
        fun schedule(context: Context, forceReschedule: Boolean = false) {
            try {
                val appContext = context.applicationContext
                val prefs = BrowserPreferences.getInstance(appContext)
                val workManager = WorkManager.getInstance(appContext)

                if (!prefs.isFilterAutoUpdateEnabled || prefs.filterAutoUpdateIntervalHours <= 0) {
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
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .build()

                // UPDATE keeps the existing run timing while applying the current interval
                val policy = ExistingPeriodicWorkPolicy.UPDATE

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
