package com.crispy.tv.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.crispy.tv.PlaybackDependencies

class ProgressSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val watchHistory = PlaybackDependencies.watchHistoryServiceFactory(applicationContext)

        val auth =
            runCatching { watchHistory.authState() }
                .onFailure { Log.w(TAG, "Progress sync: authState failed", it) }
                .getOrNull()
                ?: return Result.retry()

        if (!auth.traktAuthenticated && !auth.simklAuthenticated) {
            return Result.success()
        }

        return runCatching {
            if (auth.traktAuthenticated) {
                watchHistory.fetchAndMergeTraktProgress()
                watchHistory.syncAllTraktProgress()
            }

            if (auth.simklAuthenticated) {
                watchHistory.fetchAndMergeSimklProgress()
                watchHistory.syncAllSimklProgress()
            }

            Result.success()
        }.onFailure {
            Log.w(TAG, "Progress sync failed", it)
        }.getOrElse { Result.retry() }
    }

    private companion object {
        private const val TAG = "ProgressSyncWorker"
    }
}
