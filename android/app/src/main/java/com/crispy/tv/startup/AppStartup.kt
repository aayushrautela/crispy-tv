package com.crispy.tv.startup

import android.content.Context
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object AppStartup {
    private val ran = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run(context: Context) {
        if (ran.getAndSet(true)) return
        val appContext = context.applicationContext
        scope.launch {
            val registry = MetadataAddonRegistry(appContext)
            val sync = SupabaseServicesProvider.createHouseholdAddonsCloudSync(appContext, registry)
            runCatching { sync.pullToLocal() }
        }
    }
}
