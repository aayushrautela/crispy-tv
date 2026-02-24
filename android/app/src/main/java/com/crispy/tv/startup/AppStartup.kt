package com.crispy.tv.startup

import android.content.Context
import com.crispy.tv.oauth.OAuthCompletionScheduler
import com.crispy.tv.oauth.PendingOAuthStore
import com.crispy.tv.sync.ProgressSyncScheduler
import com.crispy.tv.sync.ProviderSyncScheduler
import java.util.concurrent.atomic.AtomicBoolean

object AppStartup {
    private val ran = AtomicBoolean(false)

    fun run(context: Context) {
        if (ran.getAndSet(true)) {
            return
        }

        ProviderSyncScheduler.ensureScheduled(context)
        ProviderSyncScheduler.enqueueNow(context)

        ProgressSyncScheduler.ensureScheduled(context)
        ProgressSyncScheduler.enqueueNow(context)

        val pendingOAuthStore = PendingOAuthStore(context)
        if (pendingOAuthStore.hasPendingOAuth()) {
            OAuthCompletionScheduler.enqueue(context)
        }
    }
}
