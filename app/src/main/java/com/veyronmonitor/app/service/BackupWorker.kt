package com.veyronmonitor.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Backup sampler: every 15 minutes Android runs this even if the phone
 * killed the monitor service, so units keep being counted (a reading every
 * 15 min is still within the 25-min gap the meter accepts).
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            Poller.fetchOnceLocked(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.success() // try again next period, don't pile up retries
        }
    }

    companion object {
        private const val NAME = "veyron_backup_sampler"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
