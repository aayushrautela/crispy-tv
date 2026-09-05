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
     * Pull applies the server's enablement only for repositories the server actually
     * tracks. Repos with no server rows are device-local installs (or never pushed);
     * treating their absence as "disabled" clobbered local state on every pull when
     * the server had no jsplugin rows yet. Mirrors the Nuvio empty-remote guard.
     */
    override suspend fun reconcilePull(serverAddons: List<CrispyBackendClient.AddonDto>): Result<Unit> = runCatching {
        val serverRowsByRepo = serverAddons
            .filter { it.type == ADDON_TYPE_JSPLUGIN }
            .groupBy({ it.manifestUrl.lowercase(Locale.US) }) { it.payload[KEY_PROVIDER_ID].orEmpty() }
        var changed = 0
        var preservedRepos = 0
        repoClient.repos().forEach { repo ->
            val serverProviderIds = serverRowsByRepo[repo.url.lowercase(Locale.US)]
            if (serverProviderIds == null) {
                preservedRepos++
                return@forEach
            }
            repo.scrapers.forEach { scraper ->
                val shouldEnable = scraper.id in serverProviderIds
                if (scraper.enabled != shouldEnable) {
                    repoClient.setScraperEnabled(repo.url, scraper.id, shouldEnable)
                        .onFailure { throw it }
                    changed++
                }
            }
        }
        Log.i(
            LOG_TAG,
            "sync pull: ${serverRowsByRepo.size} server repo(s), $preservedRepos local repo(s) preserved, $changed scraper(s) reconciled",
        )
    }

    /**
     * Push only registers local enabled scrapers server-side and disables server rows
     * whose scraper is locally disabled. Rows belonging to repositories this device
     * has never installed are left alone: another device may own them.
     */
    override suspend fun reconcilePush(
        accessToken: String,
        profileId: String,
        serverAddons: List<CrispyBackendClient.AddonDto>,
    ): Result<Unit> = runCatching {
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
            }
        }

        serverPlugins.forEach { dto ->
            val providerId = dto.payload[KEY_PROVIDER_ID].orEmpty()
            val repoKnownLocally = dto.manifestUrl.lowercase(Locale.US) in installedRepoUrls
            val stillEnabled = localEnabled.any { local ->
                local.repoUrl.equals(dto.manifestUrl, ignoreCase = true) && local.providerId == providerId
            }
            if (repoKnownLocally && !stillEnabled) {
                backend.uninstallAddon(accessToken, profileId, dto.id)
            }
        }
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
