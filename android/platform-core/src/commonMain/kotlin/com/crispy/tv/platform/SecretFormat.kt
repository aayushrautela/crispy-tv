package com.crispy.tv.platform

/**
 * The on-disk format of an encrypted secret, shared by every [SecretStore].
 *
 * ## Why these are here and not in an implementation
 *
 * They started as `private` constants in the Android `SecureTokenStore`, which
 * meant a second implementation had to reproduce the format by reading that
 * file's body and hoping. That is a coupling with no compiler in it: change the
 * prefix there and the other side keeps producing values the first side cannot
 * read, and the failure is a silent `null` from `decrypt` rather than a compile
 * error.
 *
 * So the format is a *contract* -- the same kind of thing the
 * `contracts/SPEC.md` suites pin -- and it lives beside the interface that
 * defines the operations. A store still chooses its own key material and its own
 * key storage; it does not get to choose a format.
 *
 * ## The format
 *
 * ```
 * enc_v1:<base64 iv>:<base64 ciphertext>
 * ```
 *
 * `base64` is the standard alphabet with no line wrapping, so the value stays on
 * one line in a properties file, a JSON document or a `SharedPreferences` entry.
 * The IV is stored alongside the ciphertext because GCM requires a distinct IV
 * per encryption under the same key, and a fresh random one per call is the
 * only safe way to generate it.
 *
 * The version is in the prefix rather than implied, so a future format can be
 * introduced and [SecretStore.decrypt] can return null for what it cannot read
 * instead of misreading it. **Any change to this format needs a new prefix**, not
 * an edit to these constants: a store holding values written under the old
 * prefix has to keep being able to read them.
 */
object SecretFormat {

    /** Marks a value as produced by [SecretStore.encrypt] and safe to pass to [SecretStore.decrypt]. */
    const val PREFIX: String = "enc_v1:"

    /**
     * Separates the IV from the ciphertext.
     *
     * Base64's standard alphabet contains no `:`, so a payload cannot grow an
     * extra field by accident -- which is what makes "exactly two parts" a
     * sufficient validity check rather than a hopeful one.
     */
    const val IV_SEPARATOR: String = ":"

    /**
     * The GCM authentication tag length, in bits.
     *
     * 128 is the full tag and is what the JDK's AES/GCM produces by default, so
     * a store that accepts a shorter tag would be accepting values the other
     * side never wrote.
     */
    const val GCM_TAG_LENGTH_BITS: Int = 128

    /**
     * Whether [value] carries this format's prefix.
     *
     * This is the test [SecretStore.isEncrypted] is defined in terms of, and it
     * is deliberately a prefix test and nothing more: recognising a value is not
     * the same as being able to read it, and a caller that needs certainty calls
     * [SecretStore.decrypt] and checks for null.
     */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
