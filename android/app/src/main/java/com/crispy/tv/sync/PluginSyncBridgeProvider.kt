package com.crispy.tv.sync

import android.content.Context
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.distribution.AppDistribution

internal object PluginSyncBridgeProvider {
    fun create(context: Context, backend: CrispyBackendClient): PluginAddonsSyncBridge? {
        return AppDistribution.current.pluginSyncBridge(context.applicationContext, backend)
    }
}
