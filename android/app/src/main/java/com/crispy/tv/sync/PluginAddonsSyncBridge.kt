package com.crispy.tv.sync

import com.crispy.tv.backend.CrispyBackendClient

/**
 * Flavor-injected hook that reconciles jsplugin addon records between the server
 * and the local plugin repository store. foss builds install a real bridge; play
 * builds pass null so plugin records are never touched.
 */
internal interface PluginAddonsSyncBridge {
    suspend fun reconcilePull(serverAddons: List<CrispyBackendClient.AddonDto>): Result<Unit>

    suspend fun reconcilePush(
        accessToken: String,
        profileId: String,
        serverAddons: List<CrispyBackendClient.AddonDto>,
    ): Result<Unit>
}
