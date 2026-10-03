package com.crispy.tv.sync

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.addons.registry.CloudAddonRow
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.backend.AddonDto
import com.crispy.tv.platform.AppLogger

/**
 * Reconciles the household's addon rows between this device's registry and the
 * Crispy backend, in both directions.
 *
 * **What held this file in `androidMain` was one import, and it was a port.** The
 * eleven `android.util.Log` calls were the only forbidden token in the file, and
 * `AppLogger` -- `:platform-core`'s `debug`/`info`/`warn`/`error`, already on
 * `commonMain` -- has the same shape, so the pin was a constructor parameter rather
 * than a wall. Nothing here needed a rewrite.
 *
 * The other three collaborators were **already** in `commonMain` when this moved and
 * are named without difficulty: `AccountApi` and `ActiveProfileStore` are `:backend`,
 * and `MetadataAddonRegistry`/`CloudAddonRow` are `:addons`, which finished porting in
 * `2190d6fc`. For two landings this file was unreachable precisely because those two
 * types were declared in another module's `androidMain` **with no import** -- a forward
 * import scan cannot see a sibling-package declaration in a different source set, and
 * the attempt failed with `Unresolved reference` on the very package the line imported.
 *
 * **`backend` is declared as [BackendApi], the interface `CrispyBackendClient` already
 * implements, and that is what makes this class testable from `commonTest`.** A
 * `class` collaborator cannot be stood in for, so naming the concrete client here would
 * have left the file in `commonMain` and untested -- and this file's whole decision
 * surface is the *diff* between two row sets, which is exactly what a double can hold
 * open. This is the same shape as `AddonStreamsLoader`: read what the consumer calls and
 * type the slot to the interface, not to the implementation that happens to be wired.
 */
class HouseholdAddonsCloudSync(
    private val supabase: AccountApi,
    private val backend: BackendApi,
    private val addonRegistry: MetadataAddonRegistry,
    private val activeProfileStore: ActiveProfileStore,
    private val logger: AppLogger,
    private val pluginSyncBridge: PluginAddonsSyncBridge? = null,
) {
    suspend fun pullToLocal(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                logger.warn(LOG_TAG, "pull aborted: session refresh failed: ${t.message}")
                return Result.failure(t)
            }
        if (session == null) {
            logger.info(LOG_TAG, "pull skipped: no active session")
            return Result.success(Unit)
        }

        return try {
            val dtos = backend.listAddons(session.accessToken)
            logger.info(LOG_TAG, "pull: ${dtos.size} server addon row(s)")
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
            logger.warn(LOG_TAG, "pull failed: ${t.message}")
            Result.failure(t)
        }
    }

    suspend fun pushFromLocal(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                logger.warn(LOG_TAG, "push aborted: session refresh failed: ${t.message}")
                return Result.failure(t)
            }
        if (session == null) {
            logger.info(LOG_TAG, "push skipped: no active session")
            return Result.success(Unit)
        }

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            logger.warn(LOG_TAG, "push: no active profile id; server will reject installs")
        }

        return try {
            val serverAddons = backend.listAddons(session.accessToken)
            logger.info(LOG_TAG, "push: ${serverAddons.size} server addon row(s), profile=${profileId.ifBlank { "<missing>" }}")
            val localRows = addonRegistry.exportCloudAddons()

            // Only reconcile addons this client knows about. Rows of other or
            // unknown types (e.g. jsplugin) must never be touched here.
            val knownStremio = serverAddons.filter { it.type == ADDON_TYPE_STREMIO }

            // The four comparisons below are a **case-insensitive identity**
            // test on a URL, so the key is the URL in root case. They used to
            // ask for `en_US` explicitly, which is a real answer and the wrong
            // one: a locale is a *rendering* context, and rendering a URL in
            // Turkish lowercases `I` to a dotless `ı`, so the same manifest
            // would key differently on a Turkish device and every sync would
            // install and uninstall the same addon. `String.lowercase()` with
            // no argument is locale-invariant, which is the same contract
            // `Locale.ROOT` states -- and the search history store's dedupe key
            // now uses that same spelling, so these are one rule rather than two
            // that agree by coincidence.
            val serverByUrl = knownStremio.associateBy { it.manifestUrl.lowercase() }
            val localByUrl = localRows.associateBy { it.manifestUrl.lowercase() }

            localRows.forEach { local ->
                if (local.manifestUrl.lowercase() !in serverByUrl) {
                    backend.installAddon(session.accessToken, profileId, local.manifestUrl)
                }
            }

            knownStremio.forEach { server ->
                if (server.manifestUrl.lowercase() !in localByUrl) {
                    backend.uninstallAddon(session.accessToken, profileId, server.id)
                }
            }

            val result = merge(pluginSyncBridge?.reconcilePush(session.accessToken, profileId, serverAddons))
            logOutcome("push", result)
            result
        } catch (t: Throwable) {
            logger.warn(LOG_TAG, "push failed: ${t.message}")
            Result.failure(t)
        }
    }

    private fun logOutcome(operation: String, result: Result<Unit>) {
        result
            .onSuccess { logger.info(LOG_TAG, "$operation completed") }
            .onFailure { logger.warn(LOG_TAG, "$operation failed: ${it.message.orEmpty()}") }
    }

    private fun merge(pluginResult: Result<Unit>?): Result<Unit> = when {
        pluginResult == null || pluginResult.isSuccess -> Result.success(Unit)
        else -> pluginResult
    }

    private fun toLocalRow(dto: AddonDto): CloudAddonRow? {
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
