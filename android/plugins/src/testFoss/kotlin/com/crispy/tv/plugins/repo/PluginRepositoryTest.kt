package com.crispy.tv.plugins.repo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PluginRepositoryTest {

    private val validManifest = """
        {
          "name": "Example Repo",
          "version": "1.0.0",
          "description": "demo",
          "scrapers": [
            { "id": "yt", "name": "YouTube", "version": "1.0", "filename": "yt.js", "supportedTypes": ["movie", "tv"], "enabled": true },
            { "id": "tg", "name": "TorrentGalaxy", "version": "1.0", "filename": "tg.js", "enabled": false }
          ]
        }
    """.trimIndent()

    @Test
    fun `manifest parser accepts valid manifest`() {
        val manifest = PluginManifestParser.parse(validManifest)
        assertEquals("Example Repo", manifest.name)
        assertEquals(2, manifest.scrapers.size)
        assertEquals("yt", manifest.scrapers[0].id)
        assertTrue(manifest.scrapers[0].supportedTypes.contains("tv"))
    }

    @Test
    fun `manifest parser rejects missing name`() {
        assertThrows(PluginRepositoryException::class.java) {
            PluginManifestParser.parse("""{"version":"1.0","scrapers":[{"id":"a","name":"A","version":"1.0","filename":"a.js"}]}""")
        }
    }

    @Test
    fun `manifest parser rejects missing version`() {
        assertThrows(PluginRepositoryException::class.java) {
            PluginManifestParser.parse("""{"name":"Repo","scrapers":[{"id":"a","name":"A","version":"1.0","filename":"a.js"}]}""")
        }
    }

    @Test
    fun `manifest parser rejects empty scrapers`() {
        assertThrows(PluginRepositoryException::class.java) {
            PluginManifestParser.parse("""{"name":"Repo","version":"1.0","scrapers":[]}""")
        }
    }

    @Test
    fun `store installs and exposes enabled scrapers`() = runBlockingTest {
        val dir = tempDir()
        val store = PluginRepositoryStore(dir)
        store.installRepository(PluginManifestParser.parse(validManifest), "https://example.com/plugins.json", nowEpochMs = 1000L)
        val enabled = store.getEnabledScrapers()
        assertEquals(1, enabled.size)
        assertEquals("yt", enabled[0].scraperId)
        assertFalse(store.isRefreshDue("https://example.com/plugins.json", nowEpochMs = 1000L))
        assertTrue(
            store.isRefreshDue(
                "https://example.com/plugins.json",
                nowEpochMs = 1000L + REPOSITORY_REFRESH_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `store toggles scraper enabled state and persists`() = runBlockingTest {
        val dir = tempDir()
        val store = PluginRepositoryStore(dir)
        store.installRepository(PluginManifestParser.parse(validManifest), "https://example.com/plugins.json", nowEpochMs = 1000L)
        store.setEnabled("https://example.com/plugins.json", "tg", enabled = true)

        val enabled = store.getEnabledScrapers()
        assertEquals(2, enabled.size)

        // Re-open from disk to verify persistence
        val reopened = PluginRepositoryStore(dir)
        assertEquals(2, reopened.getEnabledScrapers().size)
    }

    @Test
    fun `store refresh updates manifest and preserves enabled flags`() = runBlockingTest {
        val dir = tempDir()
        val store = PluginRepositoryStore(dir)
        store.installRepository(PluginManifestParser.parse(validManifest), "https://example.com/plugins.json", nowEpochMs = 1000L)
        store.setEnabled("https://example.com/plugins.json", "yt", enabled = false)
        store.setEnabled("https://example.com/plugins.json", "tg", enabled = true)

        val updatedManifest = PluginManifestParser.parse(
            """
            {
              "name": "Example Repo",
              "version": "2.0.0",
              "scrapers": [
                { "id": "yt", "name": "YouTube", "version": "2.0", "filename": "yt.js" },
                { "id": "tg", "name": "TorrentGalaxy", "version": "2.0", "filename": "tg.js" }
              ]
            }
            """.trimIndent(),
        )
        store.markRefreshed("https://example.com/plugins.json", updatedManifest, nowEpochMs = 2000L)

        val reopened = PluginRepositoryStore(dir)
        assertEquals("2.0.0", reopened.getStoredRepos().first().version)
        val yt = reopened.getEnabledScrapers().firstOrNull { it.scraperId == "yt" }
        assertNull(yt, "yt was disabled before refresh and should stay disabled")
        assertTrue(reopened.getEnabledScrapers().any { it.scraperId == "tg" })
        assertFalse(reopened.isRefreshDue("https://example.com/plugins.json", nowEpochMs = 2000L))
    }

    @Test
    fun `store removal deletes repo record`() = runBlockingTest {
        val dir = tempDir()
        val store = PluginRepositoryStore(dir)
        store.installRepository(PluginManifestParser.parse(validManifest), "https://example.com/plugins.json", nowEpochMs = 1000L)
        store.removeRepository("https://example.com/plugins.json")
        assertTrue(store.getStoredRepos().isEmpty())
    }

    @Test
    fun `relative scraper url resolves against manifest url`() {
        val client = PluginManifestClient(okhttp3.OkHttpClient())
        assertEquals(
            "https://example.com/scrapers/yt.js",
            client.resolveScraperUrl("https://example.com/plugins.json", "scrapers/yt.js"),
        )
        assertEquals(
            "https://cdn.example.org/yt.js",
            client.resolveScraperUrl("https://example.com/plugins.json", "https://cdn.example.org/yt.js"),
        )
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "crispy-plugin-test-${System.nanoTime()}")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }
}

private fun runBlockingTest(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
