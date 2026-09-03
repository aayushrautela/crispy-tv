package com.crispy.tv.sync

import android.content.Context
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.network.AppHttp
import com.crispy.tv.plugins.repo.PluginRepoClient

internal class FossPluginAddonsSyncBridge(
    private val repoClient: PluginRepoClient,
    private val backend: CrispyBackendClient,
) : PluginAddonsSyncBridge {

    override suspend fun reconcilePull(serverAddons: List<CrispyBackendClient.AddonDto>): Result<Unit> = runCatching {
        val enabledKeys = serverAddons
            .filter { it.type == ADDON_TYPE_JSPLUGIN }
            .map { providerKey(it.manifestUrl, it.payload[KEY_PROVIDER_ID]) }
            .toSet()
        repoClient.repos().forEach { repo ->
            repo.scrapers.forEach { scraper ->
                val shouldEnable = providerKey(repo.url, scraper.id) in enabledKeys
                if (scraper.enabled != shouldEnable) {
                    repoClient.setScraperEnabled(repo.url, scraper.id, shouldEnable)
                        .onFailure { throw it }
                }
            }
        }
    }

    override suspend fun reconcilePush(
        accessToken: String,
        profileId: String,
        serverAddons: List<CrispyBackendClient.AddonDto>,
    ): Result<Unit> = runCatching {
        val serverPlugins = serverAddons.filter { it.type == ADDON_TYPE_JSPLUGIN }
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
            val stillEnabled = localEnabled.any { local ->
                local.repoUrl.equals(dto.manifestUrl, ignoreCase = true) && local.providerId == providerId
            }
            if (!stillEnabled) {
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

    private fun providerKey(repoUrl: String, providerId: String?): String =
        "${repoUrl.lowercase()}#${providerId.orEmpty()}"

    companion object {
        const val ADDON_TYPE_JSPLUGIN = "jsplugin"
        const val KEY_PROVIDER_ID = "providerId"
        const val KEY_NAME = "name"
        const val KEY_VERSION = "version"

        fun create(appContext: Context, backend: CrispyBackendClient): FossPluginAddonsSyncBridge =
            FossPluginAddonsSyncBridge(
                repoClient = PluginRepoClient(appContext, AppHttp.okHttp(appContext)),
                backend = backend,
            )
    }
}
