package com.crispy.tv.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.player.WatchProvider

class ProviderSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val watchHistory = PlaybackDependencies.watchHistoryServiceFactory(applicationContext)

        // Pull cloud profile data (including provider auth) so this device has
        // the latest Trakt/Simkl tokens before checking auth state.
        try {
            val cloudSync =
                SupabaseServicesProvider.createProfileDataCloudSync(
                    context = applicationContext,
                    watchHistoryService = watchHistory,
                )
            cloudSync.pullForActiveProfile()
        } catch (e: Exception) {
            Log.w(TAG, "Profile data pull failed, continuing with local auth", e)
        }

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
