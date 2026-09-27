package com.crispy.tv.platform

/**
 * Encrypts small secrets (tokens, keys) for at-rest storage.
 *
 * Implementations must round-trip through [encrypt] and [decrypt] and must be
 * safe to call on a device with no hardware-backed keystore, falling back to a
 * value the app can still read.
 */
interface SecretStore {
    /** True when [value] was produced by [encrypt] and is safe to pass to [decrypt]. */
    fun isEncrypted(value: String): Boolean

    fun encrypt(plaintext: String): String

    /** Returns null when [stored] is not in this store's encrypted format. */
    fun decrypt(stored: String): String?
}
