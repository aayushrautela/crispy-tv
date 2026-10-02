package com.crispy.tv.startup

import android.content.Context
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.crispy.tv.addons.registry.metadataAddonRegistry

object AppStartup {
    private val ran = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run(context: Context) {
        if (ran.getAndSet(true)) return
        val appContext = context.applicationContext
        scope.launch {
            val registry = metadataAddonRegistry(appContext)
            val sync = SupabaseServicesProvider.createHouseholdAddonsCloudSync(appContext, registry)
            sync.pullToLocal()
                .onFailure {
                    android.util.Log.w("CrispyStartup", "addon pull failed: ${it.message.orEmpty()}")
                }
        }
    }
}
