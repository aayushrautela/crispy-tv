package com.crispy.tv.sync

import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.metadata.CloudAddonRow
import com.crispy.tv.metadata.MetadataAddonRegistry

class HouseholdAddonsCloudSync(
    private val supabase: SupabaseAccountClient,
    private val addonRegistry: MetadataAddonRegistry,
) {
    suspend fun pullToLocal(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                return Result.failure(t)
            }
        if (session == null) return Result.success(Unit)

        return try {
            val addons = supabase.getHouseholdAddons(session.accessToken).filter { it.enabled }
            val rows =
                addons.mapIndexed { index, addon ->
                    CloudAddonRow(manifestUrl = addon.url, sortOrder = index)
                }
            addonRegistry.reconcileCloudAddons(rows)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun pushFromLocal(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                return Result.failure(t)
            }
        if (session == null) return Result.success(Unit)

        return try {
            val localRows = addonRegistry.exportCloudAddons()
            val addons =
                localRows
                    .sortedBy { it.sortOrder }
                    .map {
                        SupabaseAccountClient.HouseholdAddon(
                            url = it.manifestUrl,
                            enabled = true,
                            name = null
                        )
                    }

            supabase.replaceHouseholdAddons(session.accessToken, addons)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
