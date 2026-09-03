package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.PluginStreamInput
import com.crispy.tv.plugins.bridge.CryptoBridge
import com.crispy.tv.plugins.bridge.DomBridge
import com.crispy.tv.plugins.bridge.UrlBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickJsPluginRuntimeTest {

    private class FakeBridges : PluginBridges() {
        val logs = mutableListOf<String>()
        val storage = linkedMapOf<String, String>()
        var fetchHandler: (suspend (String) -> String)? = null

        override fun log(pluginId: String, message: String) {
            logs += "[$pluginId] $message"
        }

        override suspend fun fetch(requestJson: String): String {
            return fetchHandler?.invoke(requestJson)
                ?: throw UnsupportedOperationException("fetch not wired in this test")
        }

        override fun storageGet(pluginId: String, key: String): String? = storage["$pluginId:$key"]

        override fun storageSet(pluginId: String, key: String, value: String) {
            storage["$pluginId:$key"] = value
        }

        override fun digestHex(pluginId: String, algorithm: String, dataHex: String): String =
            CryptoBridge.digestHex(algorithm, dataHex)

        override fun utf8ToHex(pluginId: String, text: String): String = CryptoBridge.utf8ToHex(text)

        override fun utf8BytesJson(pluginId: String, text: String): String = CryptoBridge.utf8BytesJson(text)

        override fun hexToUtf8(pluginId: String, hex: String): String = CryptoBridge.hexToUtf8(hex)

        override fun base64EncodeText(pluginId: String, text: String): String = CryptoBridge.base64EncodeText(text)

        override fun base64DecodeText(pluginId: String, base64: String): String = CryptoBridge.base64DecodeText(base64)

        override fun parseUrl(pluginId: String, urlString: String): String = UrlBridge.parseJson(urlString)

        override fun resolveUrl(pluginId: String, base: String, relative: String): String =
            UrlBridge.resolve(base, relative)

        override fun domLoad(pluginId: String, html: String): String = domBridge.load(html)

        override fun domSelect(pluginId: String, documentId: String, selector: String): String =
            domBridge.select(documentId, selector)

        override fun domText(pluginId: String, documentId: String, elementIdsJson: String): String =
            domBridge.text(documentId, parseIds(elementIdsJson))

        override fun domAttr(pluginId: String, documentId: String, elementId: String, name: String): String =
            domBridge.attr(documentId, elementId, name)

        private val domBridge = DomBridge()

        private fun parseIds(json: String): List<String> {
            val array = org.json.JSONArray(json)
            return buildList {
                for (index in 0 until array.length()) add(array.getString(index))
            }
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
              console.log('running ' + input.title);
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
              crispy.storage.set('seen', 'yes');
              const seen = crispy.storage.get('seen');
              return [{ name: seen, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("yes", result.streams.first().name)
        assertEquals("yes", bridges.storage["example:seen"])
    }

    @Test
    fun `crypto facade computes digest and base64`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams(input) {
              const digest = CryptoJS.SHA256('hello').toString(CryptoJS.enc.Hex);
              const encoded = btoa('hello');
              return [{ name: digest + ':' + encoded, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824:aGVsbG8=", result.streams.first().name)
    }

    @Test
    fun `url facade parses urls`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams(input) {
              const parsed = JSON.parse(__crispyParseUrl('https://example.com:8080/path/list?q=1#frag'));
              const params = new URLSearchParams('?a=1&b=2');
              return [{ name: parsed.hostname + ':' + parsed.port + ':' + params.get('b'), url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("example.com:8080:2", result.streams.first().name)
    }

    @Test
    fun `cheerio facade extracts text and attributes`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams(input) {
              const ${'$'} = cheerio.load('<div class="row"><a href="/one">First</a><a href="/two">Second</a></div>');
              const href = ${'$'}('.row a').first().attr('href');
              const text = ${'$'}('.row a').first().text();
              return [{ name: href + ':' + text, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("/one:First", result.streams.first().name)
    }

    @Test
    fun `fetch facade returns response shape`() = runBlocking {
        val bridges = FakeBridges()
        bridges.fetchHandler = { requestJson ->
            org.json.JSONObject(requestJson).let { request ->
                org.json.JSONObject()
                    .put("status", 200)
                    .put("headers", org.json.JSONObject().put("content-type", "application/json"))
                    .put("bodyBase64", "")
                    .put("body", "{\"token\":\"abc\"}")
                    .toString()
            }
        }
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams(input) {
              const response = await fetch('https://example.com/api');
              const body = await response.json();
              return [{ name: response.status + ':' + body.token, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("200:abc", result.streams.first().name)
    }

    @Test
    fun `input json escapes quotes`() {
        val json = com.crispy.tv.plugins.PluginInputJson.encode(input(title = "Test \"Movie\""))
        assertTrue(json.contains("\"title\":\"Test \\\"Movie\\\"\""))
    }
}
