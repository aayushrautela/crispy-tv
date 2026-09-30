package com.crispy.tv.platform.desktop

import com.crispy.tv.platform.SecretFormat
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DesktopSecretStore] against a temporary key file.
 *
 * The store this pins is untestable on a JVM by construction -- the Android one
 * raises `KeyStoreException: AndroidKeyStore not found` in its constructor
 * before any assertion runs -- so the format and the three operations are only
 * covered from this side until an OS-keychain implementation exists.
 */
class DesktopSecretStoreTest {

    private val keyFile: File = createTempDirectory("crispy-keys").toFile().resolve("token.key")

    private fun store() = DesktopSecretStore(keyFile)

    @Test
    fun `a secret survives a round trip`() {
        val store = store()
        assertEquals("hunter2", store.decrypt(store.encrypt("hunter2")))
    }

    @Test
    fun `a secret with non-ascii text survives a round trip`() {
        val store = store()
        val secret = "café — 日本語 — 🔐"
        assertEquals(secret, store.decrypt(store.encrypt(secret)))
    }

    @Test
    fun `an empty string is a value, not an absent one`() {
        val store = store()
        assertEquals("", store.decrypt(store.encrypt("")))
    }

    /**
     * The format is the contract shared with `SecureTokenStore`, so it is pinned
     * literally rather than through a round trip. A round trip would pass for a
     * format that had drifted, as long as both halves drifted together.
     */
    @Test
    fun `the stored form is the documented format`() {
        val stored = store().encrypt("x")
        assertTrue(stored.startsWith("enc_v1:"), "prefix missing from $stored")

        val parts = stored.removePrefix("enc_v1:").split(":")
        assertEquals(2, parts.size)
        // A 96-bit IV and a GCM ciphertext carrying its own 128-bit tag.
        assertEquals(12, Base64.getDecoder().decode(parts[0]).size)
        assertEquals("x".toByteArray().size + 16, Base64.getDecoder().decode(parts[1]).size)
    }

    @Test
    fun `the same plaintext encrypts differently each time`() {
        val store = store()
        assertNotEquals(store.encrypt("same"), store.encrypt("same"))
    }

    @Test
    fun `isEncrypted recognises only the current format's prefix`() {
        val store = store()
        assertTrue(store.isEncrypted(store.encrypt("x")))
        assertTrue(SecretFormat.isEncrypted("enc_v1:anything"))
        assertFalse(store.isEncrypted("plain text"))
        assertFalse(store.isEncrypted(""))
        assertFalse(store.isEncrypted("ENC_V1:upper"))
    }

    /**
     * `decrypt` answers null for anything it cannot read, which is the only
     * safe answer: guessing produces plaintext that is wrong and looks right.
     */
    @Test
    fun `decrypt returns null rather than guessing at unreadable input`() {
        val store = store()
        assertNull(store.decrypt("not encrypted at all"))
        assertNull(store.decrypt("enc_v1:"))
        assertNull(store.decrypt("enc_v1:only-one-part"))
        assertNull(store.decrypt("enc_v1:too:many:parts"))
        assertNull(store.decrypt("enc_v1:!!!not base64!!!:also not base64"))
    }

    @Test
    fun `a value tampered with fails authentication instead of decrypting`() {
        val store = store()
        val stored = store.encrypt("x")
        // Flip the last character of the ciphertext; GCM must reject it.
        val tampered = stored.dropLast(1) + if (stored.last() == 'A') 'B' else 'A'
        assertNull(store.decrypt(tampered))
    }

    @Test
    fun `a secret written by one store is read by another with the same key file`() {
        val encrypted = store().encrypt("persisted")
        assertEquals("persisted", DesktopSecretStore(keyFile).decrypt(encrypted))
    }

    @Test
    fun `the key is generated once and then reused`() {
        val store = store()
        store.encrypt("first")
        val keyAfterFirstUse = keyFile.readText()
        store.encrypt("second")
        assertEquals(keyAfterFirstUse, keyFile.readText())
    }

    @Test
    fun `a secret cannot be read with a different key`() {
        val encrypted = store().encrypt("secret")
        val otherKey = createTempDirectory("crispy-other").toFile().resolve("token.key")
        assertNull(DesktopSecretStore(otherKey).decrypt(encrypted))
    }

    @Test
    fun `a value with no prefix but a valid payload still decrypts`() {
        // `decrypt` strips the prefix when present rather than requiring it, so
        // a value that lost its marker is still recoverable.
        val store = store()
        val payload = store.encrypt("x").removePrefix(SecretFormat.PREFIX)
        assertNotNull(store.decrypt(payload))
    }

    @Test
    fun `the key file is created on first use rather than at construction`() {
        val absent = createTempDirectory("crispy-lazy").toFile().resolve("lazy.key")
        val store = DesktopSecretStore(absent)
        assertFalse(absent.exists(), "key written before anything needed encrypting")

        store.encrypt("x")
        assertTrue(absent.isFile)
    }
}
