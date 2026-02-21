package com.crispy.rewrite.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ProviderSyncScheduler {
    private const val PERIODIC_WORK_NAME = "provider-sync-periodic"
    private const val ONE_TIME_WORK_NAME = "provider-sync-now"

    fun ensureScheduled(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val periodic =
            PeriodicWorkRequestBuilder<ProviderSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
    }

    fun enqueueNow(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val oneOff =
            OneTimeWorkRequestBuilder<ProviderSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                oneOff,
            )
    }
}
