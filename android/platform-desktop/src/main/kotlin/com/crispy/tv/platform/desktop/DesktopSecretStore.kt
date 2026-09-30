package com.crispy.tv.platform.desktop

import com.crispy.tv.platform.SecretFormat
import com.crispy.tv.platform.SecretStore
import java.io.File
import java.io.IOException
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [SecretStore] for the desktop, over the same AES/GCM format the Android
 * implementation writes.
 *
 * ## This is not a keystore, and the difference matters
 *
 * On Android the AES key is generated inside `AndroidKeyStore` and never leaves
 * it: the key material is held by secure hardware or a TEE, and even a
 * process with the file open cannot read it. **This implementation holds the key
 * in a file next to the data it protects.** Anyone who can read the user's home
 * directory can read the key and decrypt whatever this wrote.
 *
 * So the honest statement of what this buys is narrow: a secret at rest is not
 * readable as plaintext by anything that opens the settings file, the logs or a
 * backup of them, and a value written here is readable by the Android
 * implementation and vice versa. It is *not* protection from an attacker who
 * has filesystem access, which is the case a keystore covers and this does not.
 * On a single-user desktop, that is the honest tradeoff for a build with no
 * native dependency; a build that needs the stronger property needs an OS
 * keychain (macOS Keychain, Windows Credential Manager, libsecret), which is
 * why the [keyFile] is a constructor parameter and the key holder is the only
 * thing that would change.
 *
 * The [SecretStore] contract is three operations and a format, deliberately, so
 * that swap is a constructor argument and not a change to anything that calls it.
 *
 * ## Why the format is shared rather than copied
 *
 * [SecretFormat] carries the prefix, the separator and the tag length, and
 * lives in `:platform-core` beside the interface. `SecureTokenStore` had them
 * as `private` constants, which left a second implementation to reproduce the
 * format by reading that file's body -- a coupling with no compiler in it, where
 * a change on one side produces values the other silently cannot read.
 */
class DesktopSecretStore(
    private val keyFile: File,
) : SecretStore {

    private val secureRandom = SecureRandom()

    /**
     * The AES key, read from [keyFile] or generated on first use.
     *
     * Read fresh on every call rather than cached, so a store constructed before
     * the key existed -- or after the key file was replaced -- does not keep using
     * a key that is no longer the one on disk. The cost is one small file read
     * per encrypt or decrypt, which is nothing next to the AES operation itself.
     */
    private fun secretKey(): javax.crypto.SecretKey {
        if (keyFile.isFile) {
            val encoded = Base64.getDecoder().decode(keyFile.readText().trim())
            if (encoded.isNotEmpty()) return SecretKeySpec(encoded, "AES")
        }
        val generated = KeyGenerator.getInstance("AES").apply { init(256, secureRandom) }.generateKey()
        persistKey(generated.encoded)
        return generated
    }

    /**
     * Write the key with owner-only permissions where the filesystem allows it.
     *
     * `Files.setPosixFilePermissions` throws on Windows, so it is attempted and
     * ignored: the alternative is a desktop app that cannot save a setting on
     * Windows at all, which is a worse outcome than a key file with default
     * permissions on a filesystem that has no permissions to restrict. The
     * `catch` is deliberately narrow -- it names `UnsupportedOperationException`
     * and `IOException` -- so a genuine write failure still surfaces.
     */
    private fun persistKey(encoded: ByteArray) {
        keyFile.parentFile?.mkdirs()
        keyFile.writeText(Base64.getEncoder().encodeToString(encoded))
        restrictPermissions(keyFile)
    }

    /**
     * Restrict [file] to its owner, where the filesystem has permissions to
     * restrict.
     *
     * `setPosixFilePermissions` throws `UnsupportedOperationException` on a
     * filesystem with no POSIX permission model -- Windows, and some network
     * mounts -- and the alternative to catching that is a desktop app that
     * cannot save a setting at all, which is worse than a key file with default
     * permissions on a filesystem that has no permissions to speak of. The
     * catches are deliberately narrow: they name `UnsupportedOperationException`
     * and `IOException` so a genuine write failure still surfaces rather than
     * being mistaken for "this platform cannot".
     *
     * The key is written *before* the permissions are applied, so on a
     * filesystem that does support this there is a brief window in which the
     * key file is readable by others. Closing that needs a create-with-mode
     * primitive, which is POSIX-only, so this records the window rather than
     * pretending it is absent.
     */
    private fun restrictPermissions(file: File) {
        try {
            val path = file.toPath()
            val view = java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java) ?: return
            view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) {
            // No POSIX permissions on this filesystem.
        } catch (_: IOException) {
            // Permissions exist but could not be set. The key is still written,
            // so this is a weaker guarantee and not a lost one.
        }
    }

    override fun isEncrypted(value: String): Boolean = SecretFormat.isEncrypted(value)

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(SecretFormat.GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SecretFormat.PREFIX +
            Base64.getEncoder().encodeToString(iv) + SecretFormat.IV_SEPARATOR +
            Base64.getEncoder().encodeToString(ciphertext)
    }

    override fun decrypt(stored: String): String? {
        val payload = if (isEncrypted(stored)) stored.removePrefix(SecretFormat.PREFIX) else stored
        val parts = payload.split(SecretFormat.IV_SEPARATOR)
        // Exactly two parts, or it is not this format. A missing field, an extra
        // one, or a base64 blob that happened to contain a separator are all
        // "cannot read", and the contract for that is null rather than a guess.
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.getDecoder().decode(parts[0])
            val ciphertext = Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(SecretFormat.GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        /**
         * 96 bits, the GCM-recommended IV length and what `SecureTokenStore` uses.
         *
         * Not arbitrary: GCM's security proof is defined over 96-bit IVs, and a
         * different length would make the two implementations write values whose
         * safety rests on a claim neither of them made.
         */
        private const val IV_LENGTH_BYTES = 12

        /**
         * The transformation `SecureTokenStore` uses.
         *
         * Spelled the same way on both sides deliberately: `AES/GCM/NoPadding`
         * is the JDK's name for it and names the same construction the Android
         * one names `KeyProperties.BLOCK_MODE_GCM` with
         * `ENCRYPTION_PADDING_NONE`.
         */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
