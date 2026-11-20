package com.onyx.browser.update

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.userscript.UpdateResult
import com.onyx.browser.userscript.UserScriptManager
import com.onyx.browser.userscript.UserScriptNotificationHelper
import java.util.concurrent.TimeUnit

/**
 * A periodic background job. Each task defines whether it is enabled, its interval,
 * when it last ran, and the work itself; [UpdateScheduler] handles everything else.
 */
enum class UpdateTask(val workName: String) {
    APP("OnyxAppUpdateCheck"),
    FILTERS("OnyxFilterListUpdate"),
    USERSCRIPTS("OnyxUserScriptUpdate");

    fun isEnabled(prefs: BrowserPreferences): Boolean = when (this) {
        APP -> true
        FILTERS -> prefs.updates.isFilterAutoUpdateEnabled
        USERSCRIPTS -> prefs.updates.isUserScriptAutoUpdateEnabled
    }

    fun intervalHours(prefs: BrowserPreferences): Int = when (this) {
        APP -> 12
        FILTERS -> prefs.updates.filterAutoUpdateIntervalHours
        USERSCRIPTS -> prefs.updates.userScriptAutoUpdateIntervalHours
    }

    fun lastRunTime(prefs: BrowserPreferences): Long = when (this) {
        APP -> prefs.updates.lastAppUpdateCheckTime
        FILTERS -> prefs.updates.filterLastUpdatedTime
        USERSCRIPTS -> prefs.updates.userScriptLastUpdateCheckTime
    }

    /** Runs the task; returns false when it failed and should be retried. */
    suspend fun run(context: Context): Boolean = when (this) {
        APP -> {
            AppUpdateManager.checkSilentlyIfDue(context, force = true)
                ?.let { AppUpdateNotifier.notifyIfNew(context, it) }
            true
        }
        FILTERS -> FilterListManager.checkAndAutoUpdateFilters(context, force = true)
        USERSCRIPTS -> {
            val updated = UserScriptManager.getInstance(context).checkAndUpdateAll()
                .filter { it.second is UpdateResult.Updated }
            if (updated.isNotEmpty()) {
                val first = updated.first()
                val text = if (updated.size == 1) {
                    val r = first.second as UpdateResult.Updated
                    "${first.first.name}: v${r.from} → v${r.to}"
                } else {
                    updated.joinToString(", ") { it.first.name }
                }
                UserScriptNotificationHelper.showNotification(
                    context = context,
                    scriptName = "Userscript Updater",
                    title = if (updated.size == 1) "Userscript Updated" else "${updated.size} Userscripts Updated",
                    text = text,
                    origin = ""
                )
            }
            true
        }
    }
}

object UpdateScheduler {
    private const val TAG = "UpdateScheduler"
    private const val KEY_TASK = "task"
    private const val MAX_RETRIES = 3

    // Work names used before the shared scheduler existed share the same unique names,
    // so existing schedules are replaced in place (UPDATE policy).

    /** Enqueues or cancels every task according to current preferences. */
    fun scheduleAll(context: Context) = UpdateTask.values().forEach { schedule(context, it) }

    fun schedule(context: Context, task: UpdateTask) {
        try {
            val appContext = context.applicationContext
            val prefs = BrowserPreferences.getInstance(appContext)
            val workManager = WorkManager.getInstance(appContext)
            val hours = task.intervalHours(prefs)
            if (!task.isEnabled(prefs) || hours <= 0) {
                workManager.cancelUniqueWork(task.workName)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
                .setInputData(Data.Builder().putString(KEY_TASK, task.name).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(task != UpdateTask.APP)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            // UPDATE keeps the existing run timing while applying the current interval
            workManager.enqueueUniquePeriodicWork(task.workName, ExistingPeriodicWorkPolicy.UPDATE, request)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to schedule ${task.name}", t)
        }
    }

    /** Foreground catch-up for tasks whose interval elapsed while background work was deferred. */
    suspend fun runDueTasks(context: Context, vararg tasks: UpdateTask) {
        val prefs = BrowserPreferences.getInstance(context)
        val now = System.currentTimeMillis()
        for (task in tasks) {
            try {
                val hours = task.intervalHours(prefs)
                if (!task.isEnabled(prefs) || hours <= 0) continue
                if (now - task.lastRunTime(prefs) >= hours * 3_600_000L) task.run(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Catch-up for ${task.name} failed", t)
            }
        }
    }

    class UpdateWorker(
        appContext: Context,
        params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val task = inputData.getString(KEY_TASK)
                ?.let { name -> UpdateTask.values().firstOrNull { it.name == name } }
                ?: return Result.success()
            return try {
                if (task.run(applicationContext) || runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
            } catch (t: Throwable) {
                Log.w(TAG, "${task.name} update failed (attempt $runAttemptCount)", t)
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            }
        }
    }
}
