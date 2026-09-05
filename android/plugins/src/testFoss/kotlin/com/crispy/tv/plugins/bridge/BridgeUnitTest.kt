package com.crispy.tv.plugins.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeUnitTest {

    @Test
    fun `hex codec round trips`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte())
        val hex = HexCodec.encode(bytes)
        assertEquals("000f7f80ff", hex)
        assertTrue(HexCodec.decode(hex).contentEquals(bytes))
        assertEquals(0x0f.toByte(), HexCodec.decode("f")[0])
    }

    @Test
    fun `crypto digest matches known vectors`() {
        assertEquals(
            "5d41402abc4b2a76b9719d911017c592",
            CryptoBridge.digestHex("MD5", CryptoBridge.utf8ToHex("hello")),
        )
        assertEquals(
            "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
            CryptoBridge.digestHex("SHA1", CryptoBridge.utf8ToHex("hello")),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            CryptoBridge.digestHex("SHA-256", CryptoBridge.utf8ToHex("hello")),
        )
    }

    @Test
    fun `crypto hmac matches known vector`() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            CryptoBridge.hmacHex(
                "SHA256",
                CryptoBridge.utf8ToHex("Jefe"),
                CryptoBridge.utf8ToHex("what do ya want for nothing?"),
            ),
        )
    }

    @Test
    fun `crypto pbkdf2 matches known vector`() {
        assertEquals(
            "0c60c80f961f0e71f3a9b524af6012062fe037a6",
            CryptoBridge.pbkdf2Hex(
                CryptoBridge.utf8ToHex("password"),
                CryptoBridge.utf8ToHex("salt"),
                1,
                160,
                "SHA1",
            ),
        )
    }

    @Test
    fun `crypto aes cbc round trips with pkcs padding`() {
        val key = CryptoBridge.utf8ToHex("0123456789abcdef")
        val iv = CryptoBridge.utf8ToHex("abcdef9876543210")
        val plaintext = CryptoBridge.utf8ToHex("attack at dawn!!")
        val cipherHex = CryptoBridge.aesEncryptHex("AES-CBC", key, iv, plaintext)
        val recovered = CryptoBridge.aesDecryptHex("AES-CBC", key, iv, cipherHex)
        assertEquals(plaintext, recovered)
    }

    @Test
    fun `crypto aes ecb round trips`() {
        val key = CryptoBridge.utf8ToHex("0123456789abcdef")
        val plaintext = CryptoBridge.utf8ToHex("0123456789abcdef")
        val cipherHex = CryptoBridge.aesEncryptHex("AES-ECB", key, "", plaintext)
        assertEquals(plaintext, CryptoBridge.aesDecryptHex("AES-ECB", key, "", cipherHex))
    }

    @Test
    fun `base64 helpers match vectors`() {
        assertEquals("aGVsbG8=", CryptoBridge.base64EncodeText("hello"))
        assertEquals("hello", CryptoBridge.base64DecodeText("aGVsbG8="))
        assertEquals("aGVsbG8=", CryptoBridge.base64EncodeHex(CryptoBridge.utf8ToHex("hello")))
        assertEquals("68656c6c6f", CryptoBridge.base64DecodeHex("aGVsbG8="))
    }

    @Test
    fun `url bridge parses parts`() {
        val parsed = org.json.JSONObject(
            UrlBridge.parseJson("https://example.com:8080/path/list?q=1&x=y#frag"),
        )
        assertEquals("https:", parsed.getString("protocol"))
        assertEquals("example.com", parsed.getString("hostname"))
        assertEquals("8080", parsed.getString("port"))
        assertEquals("example.com:8080", parsed.getString("host"))
        assertEquals("/path/list", parsed.getString("pathname"))
        assertEquals("?q=1&x=y", parsed.getString("search"))
        assertEquals("#frag", parsed.getString("hash"))
    }

    @Test
    fun `url bridge resolves relative paths`() {
        assertEquals(
            "https://example.com/stream/file.m3u8",
            UrlBridge.resolve("https://example.com/list/index.html", "/stream/file.m3u8"),
        )
        assertEquals(
            "https://example.com/list/file.m3u8",
            UrlBridge.resolve("https://example.com/list/index.html", "file.m3u8"),
        )
    }

    @Test
    fun `dom bridge select text attr`() {
        val dom = DomBridge()
        val docId = dom.load("<html><body><a class=\"link\" href=\"/one\">First</a><a href=\"/two\">Second</a></body></html>")
        val ids = org.json.JSONArray(dom.select(docId, "a.link"))
        assertEquals(1, ids.length())
        val elementId = ids.getString(0)
        assertEquals("/one", dom.attr(docId, elementId, "href"))
        assertEquals("First", dom.text(docId, listOf(elementId)))
        val allLinks = org.json.JSONArray(dom.select(docId, "a"))
        assertEquals(2, allLinks.length())
        assertEquals("Second", dom.text(docId, listOf(allLinks.getString(1))))
        dom.dispose(docId)
    }

    @Test
    fun `request json parser handles defaults`() {
        val request = PluginRequestJson.parse("""{"url":"https://example.com"}""")
        assertEquals("https://example.com", request.url)
        assertEquals("GET", request.method)
        assertEquals(0, request.headers.size)
        assertEquals("", request.bodyText)
        assertTrue(request.followRedirects)
    }

    @Test
    fun `request json parser honors manual redirect`() {
        val request = PluginRequestJson.parse("""{"url":"https://example.com","followRedirects":false}""")
        assertFalse(request.followRedirects)
    }

    @Test
    fun `request json parser rejects missing url`() {
        assertThrows(PluginExecutionBlockedException::class.java) {
            PluginRequestJson.parse("""{"method":"GET"}""")
        }
    }
}
