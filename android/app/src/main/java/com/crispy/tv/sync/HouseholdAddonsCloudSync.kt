package com.crispy.tv.sync

import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.addons.registry.CloudAddonRow
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import java.util.Locale

internal class HouseholdAddonsCloudSync(
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
            val dtos = backend.listAddons(session.accessToken)
            val localRows = dtos.mapIndexed { index, dto ->
                CloudAddonRow(
                    manifestUrl = dto.manifestUrl,
                    sortOrder = index,
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
            val serverAddons = backend.listAddons(session.accessToken)
            val localRows = addonRegistry.exportCloudAddons()

            val serverByUrl = serverAddons.associateBy { it.manifestUrl.lowercase(Locale.US) }
            val localByUrl = localRows.associateBy { it.manifestUrl.lowercase(Locale.US) }

            localRows.forEach { local ->
                if (local.manifestUrl.lowercase(Locale.US) !in serverByUrl) {
                    backend.installAddon(session.accessToken, local.manifestUrl)
                }
            }

            serverAddons.forEach { server ->
                if (server.manifestUrl.lowercase(Locale.US) !in localByUrl) {
                    backend.uninstallAddon(session.accessToken, server.id)
                }
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
