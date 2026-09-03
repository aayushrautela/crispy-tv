package com.crispy.tv.sync

import android.content.Context
import com.crispy.tv.backend.CrispyBackendClient

object PluginSyncBridgeProvider {
    fun create(context: Context, backend: CrispyBackendClient): PluginAddonsSyncBridge? {
        return createPluginSyncBridge(context.applicationContext, backend)
    }
}
