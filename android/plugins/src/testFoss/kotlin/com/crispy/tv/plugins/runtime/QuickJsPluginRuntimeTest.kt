package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.PluginStreamInput
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickJsPluginRuntimeTest {

    private class FakeBridges : PluginBridges {
        val logs = mutableListOf<String>()
        val storage = linkedMapOf<String, String>()

        override fun log(pluginId: String, message: String) {
            logs += "[$pluginId] $message"
        }

        override suspend fun fetch(requestJson: String): String {
            throw UnsupportedOperationException("fetch bridge is not wired in this test")
        }

        override fun storageGet(pluginId: String, key: String): String? = storage["$pluginId:$key"]

        override fun storageSet(pluginId: String, key: String, value: String) {
            storage["$pluginId:$key"] = value
        }
    }

    private fun input(
        tmdbId: Int = 42,
        mediaType: String = "movie",
        title: String = "Test \"Movie\"",
    ) = PluginStreamInput(
        tmdbId = tmdbId,
        imdbId = null,
        mediaType = mediaType,
        season = null,
        episode = null,
        title = title,
        year = null,
    )

    @Test
    fun `evaluate arithmetic through engine`() = runBlocking {
        val result = withTimeout(10_000) {
            com.dokar.quickjs.quickJs {
                evaluate<Int>("1 + 1")
            }
        }
        assertEquals(2, result)
    }

    @Test
    fun `getStreams result is mapped`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams(input) {
              __crispyHost.log('running ' + input.title);
              return [
                {
                  name: 'Example',
                  quality: '1080p',
                  url: 'https://example.com/stream.m3u8',
                  headers: { 'User-Agent': 'test' },
                  referer: 'https://example.com',
                  subtitles: [{ url: 'https://example.com/sub.vtt', lang: 'en' }],
                  size: 1234,
                },
              ];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        val stream = result.streams.first()
        assertEquals("Example", stream.name)
        assertEquals("1080p", stream.quality)
        assertEquals("https://example.com/stream.m3u8", stream.url)
        assertEquals(mapOf("User-Agent" to "test"), stream.headers)
        assertEquals("https://example.com", stream.referer)
        assertEquals(1, stream.subtitles.size)
        assertEquals("en", stream.subtitles.first().lang)
        assertEquals(1234L, stream.sizeBytes)
        assertTrue(bridges.logs.any { it.contains("running Test \"Movie\"") })
    }

    @Test
    fun `missing getStreams yields empty result`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val result = runtime.getStreams("example", "const unused = 1;", input())
        assertTrue(result.streams.isEmpty())
        assertTrue(bridges.logs.isNotEmpty())
    }

    @Test
    fun `runaway loop is interrupted and yields empty result`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 1_000)
        val result = runtime.getStreams("example", "async function getStreams(input) { while (true) {} }", input())
        assertTrue(result.streams.isEmpty())
    }

    @Test
    fun `storage bridge round trips values`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams(input) {
              __crispyHost.storageSet('seen', 'yes');
              const seen = __crispyHost.storageGet('seen');
              return [{ name: seen, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("yes", result.streams.first().name)
        assertEquals("yes", bridges.storage["example:seen"])
    }

    @Test
    fun `input json escapes quotes`() {
        val json = com.crispy.tv.plugins.PluginInputJson.encode(input(title = "Test \"Movie\""))
        assertTrue(json.contains("\"title\":\"Test \\\"Movie\\\"\""))
    }
}
