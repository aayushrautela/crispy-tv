package com.crispy.tv.oauth

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.settings.HomeScreenSettingsStore
import com.crispy.tv.sync.ProviderSyncScheduler
import java.util.concurrent.TimeUnit

internal object OAuthCompletionScheduler {
    private const val WORK_NAME = "oauth-completion"

    fun enqueue(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val workRequest =
            OneTimeWorkRequestBuilder<OAuthCompletionWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
    }
}

class OAuthCompletionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = PendingOAuthStore(applicationContext)
        val pending = store.loadPending() ?: return Result.success()
        val watchHistory = PlaybackDependencies.watchHistoryServiceFactory(applicationContext)

        val completionResult =
            try {
                when (pending.provider) {
                    WatchProvider.TRAKT -> watchHistory.completeTraktOAuth(pending.callbackUri)
                    WatchProvider.SIMKL -> watchHistory.completeSimklOAuth(pending.callbackUri)
                    WatchProvider.LOCAL -> {
                        store.clearPending()
                        store.setPendingErrorMessage("Invalid pending OAuth provider: local")
                        return Result.failure()
                    }
                }
            } catch (error: Exception) {
                val message = error.message?.trim().orEmpty().ifBlank { error::class.java.simpleName }
                store.setPendingErrorMessage("OAuth completion failed: $message")
                Log.w(TAG, "OAuth completion failed with exception", error)
                return Result.retry()
            }

        Log.i(
            TAG,
            "OAuth completion finished provider=${pending.provider.name.lowercase()} success=${completionResult.success} message=${completionResult.statusMessage}"
        )

        if (completionResult.success) {
            HomeScreenSettingsStore(applicationContext).setWatchDataSource(pending.provider)
            store.setLastCompletedCallbackId(PendingOAuthStore.callbackIdForUri(pending.callbackUri))
            store.clearPending()
            store.setPendingErrorMessage(null)
            ProviderSyncScheduler.enqueueNow(applicationContext)
            return Result.success()
        }

        val statusMessage = completionResult.statusMessage.trim().ifBlank { "OAuth completion failed." }
        store.setPendingErrorMessage(statusMessage)

        if (isTransientFailure(statusMessage) && canRetryTransientFailure(pending)) {
            return Result.retry()
        }

        store.clearPending()
        return Result.failure()
    }

    private fun isTransientFailure(statusMessage: String): Boolean {
        val normalized = statusMessage.lowercase()
        return normalized.contains("temporarily unavailable") ||
            normalized.contains("token exchange failed") ||
            normalized.contains("network") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out")
    }

    private fun canRetryTransientFailure(pending: PendingOAuthRecord): Boolean {
        if (pending.createdAtEpochMs <= 0L) {
            return false
        }

        val ageMs = (System.currentTimeMillis() - pending.createdAtEpochMs).coerceAtLeast(0L)
        return ageMs <= MAX_TRANSIENT_RETRY_WINDOW_MS
    }

    private companion object {
        private const val TAG = "OAuthCompletionWorker"
        private val MAX_TRANSIENT_RETRY_WINDOW_MS = TimeUnit.HOURS.toMillis(1)
    }
}
