package com.crispy.rewrite.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.crispy.rewrite.PlaybackLabDependencies
import com.crispy.rewrite.player.WatchProvider

class ProviderSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val watchHistory = PlaybackLabDependencies.watchHistoryServiceFactory(applicationContext)

        val auth = runCatching { watchHistory.authState() }
            .onFailure { Log.w(TAG, "Provider sync: authState failed", it) }
            .getOrNull()
            ?: return Result.retry()

        val shouldSyncTrakt = auth.traktAuthenticated
        val shouldSyncSimkl = auth.simklAuthenticated

        if (!shouldSyncTrakt && !shouldSyncSimkl) {
            return Result.success()
        }

        if (shouldSyncTrakt) {
            val cw = watchHistory.listContinueWatching(limit = 40, source = WatchProvider.TRAKT)
            val lib = watchHistory.listProviderLibrary(limitPerFolder = 250, source = WatchProvider.TRAKT)
            if (cw.statusMessage.startsWith("Trakt temporarily unavailable") ||
                lib.statusMessage.startsWith("Trakt temporarily unavailable")
            ) {
                return Result.retry()
            }
        }

        if (shouldSyncSimkl) {
            val cw = watchHistory.listContinueWatching(limit = 40, source = WatchProvider.SIMKL)
            val lib = watchHistory.listProviderLibrary(limitPerFolder = 250, source = WatchProvider.SIMKL)
            if (cw.statusMessage.startsWith("Simkl temporarily unavailable") ||
                lib.statusMessage.startsWith("Simkl temporarily unavailable")
            ) {
                return Result.retry()
            }
        }

        return Result.success()
    }

    private companion object {
        private const val TAG = "ProviderSyncWorker"
    }
}
