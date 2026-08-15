package com.crispy.tv.sync

import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.metadata.CloudAddonRow
import com.crispy.tv.metadata.MetadataAddonRegistry

/**
 * Two-way sync of the household addon roster against the account-scoped
 * `addons` setting on the backend. Addons are account-wide (every profile on
 * the household shares them), so they are read/written through the account
 * settings surface rather than per-profile.
 */
class HouseholdAddonsCloudSync(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
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
            val cloudRows = backend.getAddons(session.accessToken)
            val localRows =
                cloudRows.map { cloudRow ->
                    CloudAddonRow(
                        manifestUrl = cloudRow.manifestUrl,
                        sortOrder = cloudRow.sortOrder,
                    )
                }
            addonRegistry.reconcileCloudAddons(localRows)
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
            val cloudRows =
                localRows.map { localRow ->
                    CrispyBackendClient.AddonCloudRow(
                        manifestUrl = localRow.manifestUrl,
                        sortOrder = localRow.sortOrder,
                    )
                }
            backend.putAddons(session.accessToken, cloudRows)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
