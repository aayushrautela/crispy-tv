package com.crispy.tv.plugins.repo

import android.content.Context
import android.net.Uri
import com.crispy.tv.plugins.bridge.PluginHttpClient
import okhttp3.OkHttpClient

internal object PluginRepoHolder {
    @Volatile
    private var manager: PluginRepositoryManager? = null

    fun obtain(appContext: Context, okHttpClient: OkHttpClient): PluginRepositoryManager {
        manager?.let { return it }
        return synchronized(this) {
            manager?.let { it }
                ?: PluginRepositoryManager(
                    manifestClient = PluginManifestClient(PluginHttpClient.configure(okHttpClient)),
                    codeStore = PluginCodeStore(appContext.filesDir),
                    store = PluginRepositoryStore(appContext.filesDir),
                ).also { manager = it }
        }
    }
}

data class PluginScraperInfo(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val supportedTypes: List<String>,
)

data class PluginRepoInfo(
    val url: String,
    val name: String,
    val version: String?,
    val scrapers: List<PluginScraperInfo>,
)

/**
 * Public, UI-facing client for installed plugin repositories. Every instance shares the
 * process-wide [PluginRepositoryManager] so toggles and installs stay consistent with the
 * active [com.crispy.tv.plugins.streams.PluginStreamsService].
 */
class PluginRepoClient(
    appContext: Context,
    okHttpClient: OkHttpClient,
) {
    private val manager = PluginRepoHolder.obtain(appContext.applicationContext, okHttpClient)

    fun repos(): List<PluginRepoInfo> = manager.getStoredRepos().map { repo -> repo.toInfo() }

    suspend fun install(url: String): Result<List<PluginScraperInfo>> = runCatching {
        val repoUrl = url.trim()
        val scheme = runCatching { Uri.parse(repoUrl).scheme }.getOrNull()
        require(scheme == "http" || scheme == "https") { "Enter a valid repository URL." }
        manager.install(repoUrl, System.currentTimeMillis())
        manager.getStoredRepos().firstOrNull { it.url == repoUrl }
            ?.scrapers
            ?.map { scraper -> scraper.toInfo() }
            ?: emptyList()
    }

    suspend fun remove(url: String): Result<Unit> = runCatching {
        manager.removeRepository(url.trim())
    }

    suspend fun setScraperEnabled(url: String, scraperId: String, enabled: Boolean): Result<Unit> = runCatching {
        manager.enableScraper(url.trim(), scraperId, enabled, System.currentTimeMillis())
    }

    suspend fun refresh(url: String): Result<Unit> = runCatching {
        manager.refresh(url.trim(), System.currentTimeMillis())
    }
}

private fun StoredRepo.toInfo(): PluginRepoInfo =
    PluginRepoInfo(
        url = url,
        name = name,
        version = version,
        scrapers = scrapers.map { it.toInfo() },
    )

private fun StoredScraper.toInfo(): PluginScraperInfo =
    PluginScraperInfo(
        id = id,
        name = name,
        version = version,
        enabled = enabled,
        supportedTypes = supportedTypes,
    )
