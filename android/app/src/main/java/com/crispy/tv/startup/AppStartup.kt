package com.crispy.tv.startup

import android.content.Context
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

        ProgressSyncScheduler.ensureScheduled(context)
    }
}
