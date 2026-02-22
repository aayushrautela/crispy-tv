package com.crispy.rewrite.startup

import android.content.Context
import com.crispy.rewrite.oauth.OAuthCompletionScheduler
import com.crispy.rewrite.oauth.PendingOAuthStore
import com.crispy.rewrite.sync.ProviderSyncScheduler
import java.util.concurrent.atomic.AtomicBoolean

object AppStartup {
    private val ran = AtomicBoolean(false)

    fun run(context: Context) {
        if (ran.getAndSet(true)) {
            return
        }

        ProviderSyncScheduler.ensureScheduled(context)
        ProviderSyncScheduler.enqueueNow(context)

        val pendingOAuthStore = PendingOAuthStore(context)
        if (pendingOAuthStore.hasPendingOAuth()) {
            OAuthCompletionScheduler.enqueue(context)
        }
    }
}
