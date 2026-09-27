package com.crispy.tv.plugins.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

internal const val REPOSITORY_REFRESH_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L

internal fun isRefreshDue(lastRefreshedEpochMs: Long, nowEpochMs: Long): Boolean =
    lastRefreshedEpochMs <= 0L || nowEpochMs - lastRefreshedEpochMs >= REPOSITORY_REFRESH_INTERVAL_MS

@Serializable
internal data class StoredScraper(
    val id: String,
    val name: String,
    val version: String,
    val filename: String,
    val supportedTypes: List<String> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
internal data class StoredRepo(
    val url: String,
    val name: String,
    val version: String? = null,
    val lastRefreshedEpochMs: Long = 0,
    val scrapers: List<StoredScraper> = emptyList(),
    val syncedFromServer: Boolean = false,
)

@Serializable
internal data class PluginRepoState(
    val repos: List<StoredRepo> = emptyList(),
    /**
     * Lowercased repo URLs the user explicitly removed on this device.
     * Tombstones keep pull from re-installing removed repos and let push
     * uninstall their server rows. Sync-driven removals (server deleted the
     * repo) are not tombstoned so a later server re-install re-propagates.
     */
    val removedRepoUrls: List<String> = emptyList(),
)

internal data class PluginScraperDescriptor(
    val repoUrl: String,
    val scraperId: String,
    val displayName: String,
    val version: String,
    val supportedTypes: List<String>,
    val code: String,
)

internal class PluginRepositoryStore(rootDir: File) {

    private val file = File(rootDir, "plugins/repos.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var state: PluginRepoState = load()

    suspend fun installRepository(
        manifest: PluginManifest,
        repoUrl: String,
        nowEpochMs: Long,
        syncedFromServer: Boolean = false,
    ) {
        mutex.withLock {
            val scrapers = manifest.scrapers.map { toStored(it) }
            state = state.copy(
                repos = state.repos.filterNot { it.url == repoUrl } + StoredRepo(
                    url = repoUrl,
                    name = manifest.name,
                    version = manifest.version,
                    lastRefreshedEpochMs = nowEpochMs,
                    scrapers = scrapers,
                    syncedFromServer = syncedFromServer,
                ),
                removedRepoUrls = state.removedRepoUrls.filterNot { it == repoUrl.lowercase(Locale.US) },
            )
            persistLocked()
        }
    }

    fun isRemoved(repoUrl: String): Boolean = repoUrl.lowercase(Locale.US) in state.removedRepoUrls

    suspend fun setEnabled(repoUrl: String, scraperId: String, enabled: Boolean) {
        mutex.withLock {
            state = state.copy(
                repos = state.repos.map { repo ->
                    if (repo.url != repoUrl) return@map repo
                    repo.copy(
                        scrapers = repo.scrapers.map { scraper ->
                            if (scraper.id == scraperId) scraper.copy(enabled = enabled) else scraper
                        },
                    )
                },
            )
            persistLocked()
        }
    }

    suspend fun markRefreshed(repoUrl: String, manifest: PluginManifest, nowEpochMs: Long) {
        mutex.withLock {
            val known = state.repos.firstOrNull { it.url == repoUrl } ?: return@withLock
            val enabledByScraperId = known.scrapers.associate { it.id to it.enabled }
            state = state.copy(
                repos = state.repos.map { repo ->
                    if (repo.url != repoUrl) repo else repo.copy(
                        name = manifest.name,
                        version = manifest.version,
                        lastRefreshedEpochMs = nowEpochMs,
                        scrapers = manifest.scrapers.map { scraper ->
                            toStored(scraper).copy(
                                enabled = enabledByScraperId[scraper.id] ?: scraper.enabled,
                            )
                        },
                    )
                },
            )
            persistLocked()
        }
    }

    suspend fun removeRepository(repoUrl: String) {
        mutex.withLock {
            state = state.copy(
                repos = state.repos.filterNot { it.url == repoUrl },
                removedRepoUrls = (state.removedRepoUrls + repoUrl.lowercase(Locale.US)).distinct(),
            )
            persistLocked()
        }
    }

    /** Sync-driven removal: drop the repo without a tombstone so a future server re-install re-propagates. */
    suspend fun removeSyncedRepository(repoUrl: String) {
        mutex.withLock {
            state = state.copy(
                repos = state.repos.filterNot { it.url == repoUrl },
                removedRepoUrls = state.removedRepoUrls.filterNot { it == repoUrl.lowercase(Locale.US) },
            )
            persistLocked()
        }
    }

    fun getStoredRepos(): List<StoredRepo> = state.repos

    fun isRefreshDue(repoUrl: String, nowEpochMs: Long): Boolean {
        val repo = state.repos.firstOrNull { it.url == repoUrl } ?: return true
        return isRefreshDue(repo.lastRefreshedEpochMs, nowEpochMs)
    }

    fun getEnabledScrapers(): List<PluginScraperDescriptor> {
        return state.repos.flatMap { repo ->
            repo.scrapers.filter { it.enabled }.map { scraper ->
                PluginScraperDescriptor(
                    repoUrl = repo.url,
                    scraperId = scraper.id,
                    displayName = scraper.name,
                    version = scraper.version,
                    supportedTypes = scraper.supportedTypes,
                    code = "",
                )
            }
        }
    }

    private fun toStored(scraper: PluginManifestScraper): StoredScraper = StoredScraper(
        id = scraper.id,
        name = scraper.name,
        version = scraper.version,
        filename = scraper.filename,
        supportedTypes = scraper.supportedTypes,
        enabled = scraper.enabled,
    )

    private fun load(): PluginRepoState {
        val fileContent = runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { android.util.Log.w(LOG_TAG, "repos load failed (${file.path}): ${it.message}") }
            .getOrNull() ?: return PluginRepoState()
        return runCatching { json.decodeFromString<PluginRepoState>(fileContent) }
            .onFailure { android.util.Log.w(LOG_TAG, "repos parse failed (${file.path}): ${it.message}") }
            .getOrElse { PluginRepoState() }
    }

    private fun persistLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(state), Charsets.UTF_8)
        }.onFailure { android.util.Log.w(LOG_TAG, "repos persist failed (${file.path}): ${it.message}") }
    }

    private companion object {
        const val LOG_TAG = "CrispyPlugins"
    }
}
