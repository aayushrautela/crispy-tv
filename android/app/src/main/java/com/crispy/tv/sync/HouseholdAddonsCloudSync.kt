package com.crispy.tv.sync

import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.metadata.MetadataAddonRegistry

internal class HouseholdAddonsCloudSync(
    @Suppress("UNUSED_PARAMETER") supabase: SupabaseAccountClient,
    @Suppress("UNUSED_PARAMETER") addonRegistry: MetadataAddonRegistry,
) {
    suspend fun pullToLocal(): Result<Unit> {
        return Result.failure(
            IllegalStateException("Addon sync is temporarily disabled until backend addon endpoints exist.")
        )
    }

    suspend fun pushFromLocal(): Result<Unit> {
        return Result.failure(
            IllegalStateException("Addon sync is temporarily disabled until backend addon endpoints exist.")
        )
    }
}
