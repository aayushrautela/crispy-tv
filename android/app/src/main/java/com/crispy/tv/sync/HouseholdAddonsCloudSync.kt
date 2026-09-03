package com.crispy.tv.sync

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.addons.registry.CloudAddonRow
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import java.util.Locale

internal class HouseholdAddonsCloudSync(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
    private val addonRegistry: MetadataAddonRegistry,
    private val activeProfileStore: ActiveProfileStore,
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
            val localRows = dtos.mapIndexedNotNull { index, dto ->
                toLocalRow(dto)?.let { row ->
                    CloudAddonRow(
                        manifestUrl = row.manifestUrl,
                        sortOrder = index,
                    )
                }
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

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()

        return try {
            val serverAddons = backend.listAddons(session.accessToken)
            val localRows = addonRegistry.exportCloudAddons()

            // Only reconcile addons this client knows about. Rows of other or
            // unknown types (e.g. jsplugin) must never be touched here.
            val knownStremio = serverAddons.filter { it.type == ADDON_TYPE_STREMIO }

            val serverByUrl = knownStremio.associateBy { it.manifestUrl.lowercase(Locale.US) }
            val localByUrl = localRows.associateBy { it.manifestUrl.lowercase(Locale.US) }

            localRows.forEach { local ->
                if (local.manifestUrl.lowercase(Locale.US) !in serverByUrl) {
                    backend.installAddon(session.accessToken, profileId, local.manifestUrl)
                }
            }

            knownStremio.forEach { server ->
                if (server.manifestUrl.lowercase(Locale.US) !in localByUrl) {
                    backend.uninstallAddon(session.accessToken, profileId, server.id)
                }
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun toLocalRow(dto: CrispyBackendClient.AddonDto): CloudAddonRow? {
        if (dto.type != ADDON_TYPE_STREMIO) {
            return null
        }
        if (dto.manifestUrl.isBlank()) {
            return null
        }
        return CloudAddonRow(
            manifestUrl = dto.manifestUrl,
            sortOrder = 0,
        )
    }

    private companion object {
        const val ADDON_TYPE_STREMIO = "stremio"
    }
}
