package com.onyx.browser.data.filter

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

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
    }
}
