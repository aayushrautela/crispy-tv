package com.crispy.tv.plugins.repo

internal class PluginRepositoryManager(
    private val manifestClient: PluginManifestClient,
    private val codeStore: PluginCodeStore,
    private val store: PluginRepositoryStore,
) {

    suspend fun install(url: String, nowEpochMs: Long): List<StoredScraper> {
        val manifest = manifestClient.fetchManifest(url)
        manifest.scrapers
            .filter { it.enabled }
            .forEach { scraper ->
                val code = manifestClient.fetchCode(manifestClient.resolveScraperUrl(url, scraper.filename))
                codeStore.writeCode(url, scraper.id, code)
            }
        store.installRepository(manifest, url, nowEpochMs)
        return store.getStoredRepos().firstOrNull { it.url == url }?.scrapers ?: emptyList()
    }

    suspend fun enableScraper(url: String, scraperId: String, enabled: Boolean, nowEpochMs: Long) {
        if (enabled) {
            ensureCodeCached(url, scraperId, nowEpochMs)
        }
        store.setEnabled(url, scraperId, enabled)
    }

    suspend fun refreshDueRepositories(nowEpochMs: Long): List<String> {
        store.getStoredRepos()
            .filter { isRefreshDue(it.lastRefreshedEpochMs, nowEpochMs) }
            .forEach { repo -> refresh(repo.url, nowEpochMs) }
        return store.getStoredRepos().map { it.url }
    }

    suspend fun refresh(url: String, nowEpochMs: Long) {
        val manifest = manifestClient.fetchManifest(url)
        val known = store.getStoredRepos().firstOrNull { it.url == url }
        val enabledIds = known?.scrapers?.filter { it.enabled }?.map { it.id }?.toSet()
            ?: manifest.scrapers.filter { it.enabled }.map { it.id }.toSet()
        manifest.scrapers
            .filter { it.id in enabledIds }
            .forEach { scraper ->
                val code = manifestClient.fetchCode(manifestClient.resolveScraperUrl(url, scraper.filename))
                codeStore.writeCode(url, scraper.id, code)
            }
        store.markRefreshed(url, manifest, nowEpochMs)
    }

    fun getEnabledScrapers(): List<PluginScraperDescriptor> {
        return store.getEnabledScrapers().mapNotNull { descriptor ->
            codeStore.readCode(descriptor.repoUrl, descriptor.scraperId)?.let { code ->
                descriptor.copy(code = code)
            }
        }
    }

    suspend fun removeRepository(url: String) {
        store.removeRepository(url)
        codeStore.deleteRepo(url)
    }

    private suspend fun ensureCodeCached(url: String, scraperId: String, nowEpochMs: Long) {
        if (codeStore.readCode(url, scraperId) != null) return
        val manifest = runCatching { manifestClient.fetchManifest(url) }.getOrNull() ?: return
        val scraper = manifest.scrapers.firstOrNull { it.id == scraperId } ?: return
        runCatching {
            val code = manifestClient.fetchCode(manifestClient.resolveScraperUrl(url, scraper.filename))
            codeStore.writeCode(url, scraperId, code)
        }
    }
}
