package com.crispy.tv.sync

import android.content.Context
import android.util.Log
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.network.AppHttp
import com.crispy.tv.plugins.repo.PluginRepoClient
import java.util.Locale

internal class FossPluginAddonsSyncBridge(
    private val repoClient: PluginRepoClient,
    private val backend: CrispyBackendClient,
) : PluginAddonsSyncBridge {

    /**
     * Pull applies the server's enablement for repositories the server tracks,
     * installs server-tracked repos missing locally (skipping repos the user
     * explicitly removed here), and removes local repos that were previously
     * installed by sync but the server no longer tracks. Repos with no server
     * rows that were installed manually on this device are never touched:
     * another device may still own them. Mirrors the Nuvio empty-remote guard.
     */
    override suspend fun reconcilePull(serverAddons: List<CrispyBackendClient.AddonDto>): Result<Unit> {
        val errors = mutableListOf<String>()
        val serverRowsByRepo = serverAddons
            .filter { it.type == ADDON_TYPE_JSPLUGIN }
            .groupBy({ it.manifestUrl.trim() }) { it.payload[KEY_PROVIDER_ID].orEmpty() }

        serverRowsByRepo.keys.forEach { repoUrl ->
            val installed = repoClient.repos().any { it.url.equals(repoUrl, ignoreCase = true) }
            if (!installed && !repoClient.isRemoved(repoUrl)) {
                repoClient.installSynced(repoUrl)
                    .onFailure { errors.add("install $repoUrl: ${it.message.orEmpty()}") }
                    .onSuccess { Log.i(LOG_TAG, "sync pull: installed repo $repoUrl") }
            }
        }

        val localRepos = repoClient.repos()
        var changed = 0
        var preservedRepos = 0
        localRepos.forEach { repo ->
            val serverProviderIds = serverRowsByRepo[repo.url]
                ?: serverRowsByRepo.entries.firstOrNull { it.key.equals(repo.url, ignoreCase = true) }?.value
            if (serverProviderIds == null) {
                if (repo.syncedFromServer) {
                    repoClient.removeSynced(repo.url)
                        .onFailure { errors.add("remove ${repo.url}: ${it.message.orEmpty()}") }
                        .onSuccess { Log.i(LOG_TAG, "sync pull: removed repo no longer tracked by server: ${repo.url}") }
                } else {
                    preservedRepos++
                }
                return@forEach
            }
            repo.scrapers.forEach { scraper ->
                val shouldEnable = scraper.id in serverProviderIds
                if (scraper.enabled != shouldEnable) {
                    repoClient.setScraperEnabled(repo.url, scraper.id, shouldEnable)
                        .onFailure { errors.add("enable ${scraper.id}: ${it.message.orEmpty()}") }
                        .onSuccess { changed++ }
                }
            }
        }
        Log.i(
            LOG_TAG,
            "sync pull: ${serverRowsByRepo.size} server repo(s), $preservedRepos local repo(s) preserved, $changed scraper(s) reconciled",
        )
        return aggregate(errors)
    }

    /**
     * Push only registers local enabled scrapers server-side and uninstalls
     * server rows whose scraper is locally disabled or whose repo the user
     * removed here (tombstoned). Rows belonging to repositories this device
     * has never installed are left alone: another device may own them.
     */
    override suspend fun reconcilePush(
        accessToken: String,
        profileId: String,
        serverAddons: List<CrispyBackendClient.AddonDto>,
    ): Result<Unit> {
        val errors = mutableListOf<String>()
        val serverPlugins = serverAddons.filter { it.type == ADDON_TYPE_JSPLUGIN }
        val installedRepoUrls = repoClient.repos().mapTo(mutableSetOf()) { it.url.lowercase(Locale.US) }
        val localEnabled = repoClient.repos().flatMap { repo ->
            repo.scrapers.filter { it.enabled }.map { scraper ->
                PluginProviderRecord(
                    repoUrl = repo.url,
                    providerId = scraper.id,
                    name = scraper.name,
                    version = scraper.version,
                )
            }
        }

        localEnabled.forEach { local ->
            val exists = serverPlugins.any { dto ->
                dto.manifestUrl.equals(local.repoUrl, ignoreCase = true) &&
                    dto.payload[KEY_PROVIDER_ID] == local.providerId
            }
            if (!exists) {
                runCatching {
                    backend.installAddon(
                        accessToken = accessToken,
                        profileId = profileId,
                        manifestUrl = local.repoUrl,
                        type = ADDON_TYPE_JSPLUGIN,
                        payload = mapOf(
                            KEY_PROVIDER_ID to local.providerId,
                            KEY_NAME to local.name,
                            KEY_VERSION to local.version,
                        ),
                    )
                }.onFailure { errors.add("install ${local.providerId}: ${it.message.orEmpty()}") }
            }
        }

        serverPlugins.forEach { dto ->
            val providerId = dto.payload[KEY_PROVIDER_ID].orEmpty()
            val repoTombstoned = repoClient.isRemoved(dto.manifestUrl)
            val repoKnownLocally = dto.manifestUrl.lowercase(Locale.US) in installedRepoUrls
            val stillEnabled = localEnabled.any { local ->
                local.repoUrl.equals(dto.manifestUrl, ignoreCase = true) && local.providerId == providerId
            }
            if (repoTombstoned || (repoKnownLocally && !stillEnabled)) {
                val removed = runCatching {
                    backend.uninstallAddon(accessToken, profileId, dto.id)
                }
                removed
                    .onFailure { errors.add("uninstall $providerId: ${it.message.orEmpty()}") }
                    .onSuccess { deleted ->
                        if (deleted) {
                            Log.i(LOG_TAG, "sync push: uninstalled ${if (repoTombstoned) "tombstoned" else "disabled"} row $providerId")
                        } else {
                            errors.add("uninstall $providerId: server did not delete the row")
                        }
                    }
            }
        }
        return aggregate(errors)
    }

    private fun aggregate(errors: List<String>): Result<Unit> =
        if (errors.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(errors.joinToString("; ")))
        }

    private data class PluginProviderRecord(
        val repoUrl: String,
        val providerId: String,
        val name: String,
        val version: String,
    )

    companion object {
        const val ADDON_TYPE_JSPLUGIN = "jsplugin"
        const val KEY_PROVIDER_ID = "providerId"
        const val KEY_NAME = "name"
        const val KEY_VERSION = "version"

        private const val LOG_TAG = "CrispyPlugins"

        fun create(appContext: Context, backend: CrispyBackendClient): FossPluginAddonsSyncBridge =
            FossPluginAddonsSyncBridge(
                repoClient = PluginRepoClient(appContext, AppHttp.okHttp(appContext)),
                backend = backend,
            )
    }
}
