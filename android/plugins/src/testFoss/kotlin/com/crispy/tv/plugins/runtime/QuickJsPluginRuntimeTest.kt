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

        override fun hmacHex(pluginId: String, algorithm: String, keyHex: String, dataHex: String): String =
            CryptoBridge.hmacHex(algorithm, keyHex, dataHex)

        override fun aesEncryptHex(pluginId: String, mode: String, keyHex: String, ivHex: String, dataHex: String): String =
            CryptoBridge.aesEncryptHex(mode, keyHex, ivHex, dataHex)

        override fun aesDecryptHex(pluginId: String, mode: String, keyHex: String, ivHex: String, dataHex: String): String =
            CryptoBridge.aesDecryptHex(mode, keyHex, ivHex, dataHex)

        override fun randomHex(pluginId: String, byteCount: Int): String {
            val bytes = ByteArray(byteCount.coerceIn(0, 64))
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        override fun pbkdf2Hex(
            pluginId: String,
            passwordHex: String,
            saltHex: String,
            iterations: Int,
            keySizeBits: Int,
            hash: String,
        ): String = CryptoBridge.pbkdf2Hex(passwordHex, saltHex, iterations, keySizeBits, hash)

        override fun utf8ToHex(pluginId: String, text: String): String = CryptoBridge.utf8ToHex(text)

        override fun utf8BytesJson(pluginId: String, text: String): String = CryptoBridge.utf8BytesJson(text)

        override fun hexToUtf8(pluginId: String, hex: String): String = CryptoBridge.hexToUtf8(hex)

        override fun base64EncodeText(pluginId: String, text: String): String = CryptoBridge.base64EncodeText(text)

        override fun base64DecodeText(pluginId: String, base64: String): String = CryptoBridge.base64DecodeText(base64)

        override fun base64EncodeHex(pluginId: String, hex: String): String = CryptoBridge.base64EncodeHex(hex)

        override fun base64DecodeHex(pluginId: String, base64: String): String = CryptoBridge.base64DecodeHex(base64)

        override fun parseUrl(pluginId: String, urlString: String): String = UrlBridge.parseJson(urlString)

        override fun resolveUrl(pluginId: String, base: String, relative: String): String =
            UrlBridge.resolve(base, relative)

        override fun domLoad(pluginId: String, html: String): String = domBridge.load(html)

        override fun domSelect(pluginId: String, documentId: String, selector: String): String =
            domBridge.select(documentId, selector)

        override fun domFind(pluginId: String, documentId: String, elementId: String, selector: String): String =
            domBridge.find(documentId, elementId, selector)

        override fun domText(pluginId: String, documentId: String, elementIdsJson: String): String =
            domBridge.text(documentId, parseIds(elementIdsJson))

        override fun domAttr(pluginId: String, documentId: String, elementId: String, name: String): String =
            domBridge.attr(documentId, elementId, name)

        override fun domHtml(pluginId: String, documentId: String, elementId: String): String =
            domBridge.html(documentId, elementId)

        private val domBridge = DomBridge()

        private fun parseIds(json: String): List<String> {
            val array = org.json.JSONArray(json)
            return buildList {
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }
    }

    private fun input(
        tmdbId: String = "42",
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ) = PluginStreamInput(
        tmdbId = tmdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
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
            async function getStreams(tmdbId, mediaType, season, episode) {
              console.log('running ' + tmdbId + ':' + mediaType + ':' + season + ':' + episode);
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
        assertTrue(bridges.logs.any { it.contains("running 42:movie:undefined:undefined") })
    }

    @Test
    fun `getStreams receives positional args with numbers for season episode`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams(tmdbId, mediaType, season, episode) {
              const types = [typeof tmdbId, typeof mediaType, typeof season, typeof episode].join(',');
              return [{ name: tmdbId + '|' + mediaType + '|' + season + 'x' + episode + '|' + types, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams(
            "example",
            code,
            input(tmdbId = "603", mediaType = "tv", season = 1, episode = 2),
        )
        assertEquals(1, result.streams.size)
        assertEquals("603|tv|1x2|string,string,number,number", result.streams.first().name)
    }

    @Test
    fun `module exports getStreams is resolved`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            module.exports.getStreams = async function(tmdbId) {
              return [{ name: 'exported:' + tmdbId, url: 'https://example.com/s' }];
            };
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("exported:42", result.streams.first().name)
    }

    @Test
    fun `scraper id is a plain string`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
              return [{ name: typeof SCRAPER_ID + ':' + SCRAPER_ID, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("string:example", result.streams.first().name)
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
        val result = runtime.getStreams("example", "async function getStreams() { while (true) {} }", input())
        assertTrue(result.streams.isEmpty())
    }

    @Test
    fun `storage bridge round trips values`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
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
    fun `crypto facade aes passphrase round trips with openssl format`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
              const enc = CryptoJS.AES.encrypt('hello world', 'secret-password').toString();
              const dec = CryptoJS.AES.decrypt(enc, 'secret-password').toString(CryptoJS.enc.Utf8);
              const salted = enc.indexOf('U2FsdGVkX1') === 0 ? 'salted' : 'raw';
              return [{ name: salted + ':' + dec, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("salted:hello world", result.streams.first().name)
    }

    @Test
    fun `crypto facade pbkdf2 sha384 and base64url`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
              const key = CryptoJS.PBKDF2('password', 'salt', { iterations: 1, keySize: 5, hasher: 'SHA1' });
              const sha = CryptoJS.SHA384('hello').toString(CryptoJS.enc.Hex);
              const b64u = CryptoJS.enc.Base64url.stringify(CryptoJS.enc.Utf8.parse('hello?'));
              const back = CryptoJS.enc.Base64url.parse(b64u).toString(CryptoJS.enc.Utf8);
              const rnd = CryptoJS.lib.WordArray.random(8).sigBytes;
              return [{ name: key.toString(CryptoJS.enc.Hex) + '|' + sha.length + '|' + b64u + '|' + back + '|' + rnd, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6|96|aGVsbG8_|hello?|8", result.streams.first().name)
    }

    @Test
    fun `cheerio facade supports filter children get and context`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
              const ${'$'} = cheerio.load('<div class="row"><a class="x" href="/one">First</a><a href="/two">Second</a></div>');
              const filtered = ${'$'}('.row a').filter(function() { return this.attr('href') === '/two'; }).text();
              const kids = ${'$'}('.row').children('a').length;
              const second = ${'$'}('.row a').get(1).text();
              const scoped = ${'$'}('a', ${'$'}('.row')).length;
              return [{ name: filtered + ':' + kids + ':' + second + ':' + scoped, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("Second:2:Second:2", result.streams.first().name)
    }

    @Test
    fun `fetch facade passes manual redirect and reads status text`() = runBlocking {
        val bridges = FakeBridges()
        var seenFollowRedirects: Boolean? = null
        bridges.fetchHandler = { requestJson ->
            seenFollowRedirects = org.json.JSONObject(requestJson).optBoolean("followRedirects", true)
            org.json.JSONObject()
                .put("status", 301)
                .put("statusText", "Moved Permanently")
                .put("url", "https://example.com/final")
                .put("headers", org.json.JSONObject())
                .put("bodyBase64", "")
                .put("body", "")
                .toString()
        }
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
              const response = await fetch('https://example.com/api', { redirect: 'manual' });
              return [{ name: response.status + ':' + response.statusText + ':' + response.url + ':' + response.ok, url: 'https://example.com/s' }];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(1, result.streams.size)
        assertEquals("301:Moved Permanently:https://example.com/final:false", result.streams.first().name)
        assertEquals(false, seenFollowRedirects)
    }

    @Test
    fun `crypto facade computes digest and base64`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
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
            async function getStreams() {
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
            async function getStreams() {
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
            async function getStreams() {
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
    fun `nuvio shaped rows map fully`() = runBlocking {
        val bridges = FakeBridges()
        val runtime = QuickJsPluginRuntime(bridges, executionTimeoutMs = 15_000)
        val code = """
            async function getStreams() {
              return [
                {
                  title: 'Nuvio Title',
                  name: 'Nuvio Name',
                  url: { url: 'https://example.com/nested.m3u8' },
                  quality: '1080p',
                  size: '1.2 GB',
                  language: 'English',
                  seeders: 5,
                  peers: 1,
                  subtitles: [{ url: 'https://example.com/s.vtt', language: 'English', name: 'Eng' }],
                },
                { name: 'torrent-only', infoHash: 'ABCDEF1234567890ABCDEF1234567890ABCDEF12' },
                { name: 'dropped' },
              ];
            }
        """.trimIndent()
        val result = runtime.getStreams("example", code, input())
        assertEquals(2, result.streams.size)
        val first = result.streams[0]
        assertEquals("Nuvio Name", first.name)
        assertEquals("Nuvio Title", first.title)
        assertEquals("https://example.com/nested.m3u8", first.url)
        assertEquals("1.2 GB", first.sizeLabel)
        assertEquals("English", first.language)
        assertEquals(5, first.seeders)
        assertEquals(1, first.peers)
        assertEquals(1, first.subtitles.size)
        assertEquals("English", first.subtitles.first().lang)
        assertEquals("Eng", first.subtitles.first().name)
        val torrent = result.streams[1]
        assertEquals("torrent-only", torrent.name)
        assertEquals("ABCDEF1234567890ABCDEF1234567890ABCDEF12", torrent.infoHash)
    }

    @Test
    fun `call args quote strings and use undefined for missing season episode`() = runBlocking {
        val args = com.crispy.tv.plugins.PluginJsArgs.callArguments(
            input(tmdbId = "6\"03", mediaType = "tv", season = 1, episode = null),
        )
        assertEquals("\"6\\\"03\", \"tv\", 1, undefined", args)
    }
}
