package com.crispy.tv.sync

import android.util.Log
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
    private val pluginSyncBridge: PluginAddonsSyncBridge? = null,
) {
    suspend fun pullToLocal(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "pull aborted: session refresh failed: ${t.message}")
                return Result.failure(t)
            }
        if (session == null) {
            Log.i(LOG_TAG, "pull skipped: no active session")
            return Result.success(Unit)
        }

        return try {
            val dtos = backend.listAddons(session.accessToken)
            Log.i(LOG_TAG, "pull: ${dtos.size} server addon row(s)")
            val localRows = dtos.mapIndexedNotNull { index, dto ->
                toLocalRow(dto)?.let { row ->
                    CloudAddonRow(
                        manifestUrl = row.manifestUrl,
                        sortOrder = index,
                    )
                }
            }
            addonRegistry.reconcileCloudAddons(localRows)
            val result = merge(pluginSyncBridge?.reconcilePull(dtos))
            logOutcome("pull", result)
            result
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "pull failed: ${t.message}")
            Result.failure(t)
        }
    }

    suspend fun pushFromLocal(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "push aborted: session refresh failed: ${t.message}")
                return Result.failure(t)
            }
        if (session == null) {
            Log.i(LOG_TAG, "push skipped: no active session")
            return Result.success(Unit)
        }

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            Log.w(LOG_TAG, "push: no active profile id; server will reject installs")
        }

        return try {
            val serverAddons = backend.listAddons(session.accessToken)
            Log.i(LOG_TAG, "push: ${serverAddons.size} server addon row(s), profile=${profileId.ifBlank { "<missing>" }}")
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

            val result = merge(pluginSyncBridge?.reconcilePush(session.accessToken, profileId, serverAddons))
            logOutcome("push", result)
            result
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "push failed: ${t.message}")
            Result.failure(t)
        }
    }

    private fun logOutcome(operation: String, result: Result<Unit>) {
        result
            .onSuccess { Log.i(LOG_TAG, "$operation completed") }
            .onFailure { Log.w(LOG_TAG, "$operation failed: ${it.message.orEmpty()}") }
    }

    private fun merge(pluginResult: Result<Unit>?): Result<Unit> = when {
        pluginResult == null || pluginResult.isSuccess -> Result.success(Unit)
        else -> pluginResult
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
        private const val LOG_TAG = "CrispySync"
    }
}
