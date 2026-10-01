package com.crispy.tv.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encrypted-secret format, pinned where it is defined.
 *
 * ## Why this file exists at all
 *
 * The constants lived here already, and that was an improvement over their having
 * been `private` in one implementation. But the *shape* -- joining two halves and
 * splitting them again -- was still written out in every implementation, so a new
 * platform could read this file, learn that `PREFIX` and `IV_SEPARATOR` exist, and
 * still join them differently. The agreement between two stores was a promise in
 * prose rather than a call to one function.
 *
 * So the cases below are not about the constants, which a compile already pins. They
 * are about the four rules that the duplicated code each had to get right:
 *
 * - an unprefixed value is still read, for values written before the prefix existed;
 * - the wrong number of fields is `null`, not a guess;
 * - `isEncrypted` is a prefix test, so a plaintext token that happens to start with
 *   `enc_v1:` is reported as encrypted -- and that is the format's one sharp edge;
 * - the format does not validate its own fields, which looks like a hole and is not.
 *
 * The first two were duplicated in both implementations before this suite existed,
 * and neither implementation's own suite pinned them: the Android store cannot be
 * constructed on a JVM at all (`AndroidKeyStore` in its constructor), so there is
 * no test that could have caught them on either side.
 */
class SecretFormatTest {

    @Test
    fun encodeJoinsTheHalvesWithThePrefixAndTheSeparator() {
        val encoded = SecretFormat.encode("aXY=", "Y2lwaGVydGV4dA==")

        assertEquals(
            SecretFormat.PREFIX + "aXY=" + SecretFormat.IV_SEPARATOR + "Y2lwaGVydGV4dA==",
            encoded,
            "encode must produce exactly prefix + iv + separator + ciphertext",
        )
        assertTrue(SecretFormat.isEncrypted(encoded), "anything encode produced carries the prefix")
    }

    @Test
    fun aRoundTripReturnsBothHalvesExactly() {
        val encoded = SecretFormat.encode("aXY=", "Y2lwaGVydGV4dA==")

        val decoded = SecretFormat.decode(encoded)

        assertEquals("aXY=", decoded?.ivBase64, "the IV must survive the round trip byte for byte")
        assertEquals("Y2lwaGVydGV4dA==", decoded?.ciphertextBase64, "so must the ciphertext")
    }

    @Test
    fun aValueWrittenBeforeThePrefixExistedIsStillRead() {
        val encoded = SecretFormat.encode("aXY=", "Y2lwaGVydGV4dA==")
        val legacy = encoded.removePrefix(SecretFormat.PREFIX)

        val decoded = SecretFormat.decode(legacy)

        assertEquals(
            SecretFormat.EncodedSecret("aXY=", "Y2lwaGVydGV4dA=="),
            decoded,
            "a store that shipped before the prefix existed holds values without it, and " +
                "rejecting those would lock those users out of their own sessions on upgrade",
        )
    }

    @Test
    fun aValueMissingItsCiphertextIsNotThisFormat() {
        assertNull(SecretFormat.decode(SecretFormat.PREFIX + "aXY="), "one field is a missing half")
        assertNull(SecretFormat.decode("aXY="), "and the same without the prefix")
    }

    @Test
    fun aValueCarryingAnExtraFieldIsNotThisFormat() {
        assertNull(
            SecretFormat.decode(SecretFormat.PREFIX + "aXY=" + SecretFormat.IV_SEPARATOR + "Y2lwaGVy" + SecretFormat.IV_SEPARATOR + "ZXh0cmE="),
            "three fields is a future format, not a torn one, and misreading it would be worse than refusing it",
        )
    }

    @Test
    fun isEncryptedIsAPrefixTestAndNothingMore() {
        assertTrue(SecretFormat.isEncrypted(SecretFormat.PREFIX + "anything"), "the prefix is present")
        assertFalse(SecretFormat.isEncrypted("a plain token"), "a plain token carries no prefix")

        // The sharp edge. Recognising a value is not the same as being able to read
        // it, which is what SecretStore.isEncrypted's own note says -- and this is
        // what makes it true rather than merely asserted. A token whose plaintext
        // happens to start with the prefix is reported as encrypted, so a caller that
        // trusts isEncrypted alone would try to decrypt text it already holds. The
        // answer has to come from decrypt returning null, and the prefix is stripped
        // before the halves are even looked at, so it cannot come back intact.
        val plaintextThatLooksEncrypted = SecretFormat.PREFIX + "not-really-encrypted"
        assertTrue(
            SecretFormat.isEncrypted(plaintextThatLooksEncrypted),
            "a prefix test cannot tell a real value from plaintext that starts the same way",
        )
        assertNull(
            SecretFormat.decode(plaintextThatLooksEncrypted),
            "and the prefix is consumed before the halves are read, so there is nothing left to decrypt",
        )
    }

    @Test
    fun theFormatDoesNotValidateItsOwnFields() {
        val encoded = SecretFormat.encode("", "")

        assertEquals(
            SecretFormat.EncodedSecret("", ""),
            SecretFormat.decode(encoded),
            "encode must never reject its own input: the caller is the only party that " +
                "knows what a valid IV looks like, so a bad IV is a crypto failure to report",
        )
    }

    @Test
    fun theFormatCannotTellTheIvFromTheCiphertext() {
        val swapped = SecretFormat.encode("Y2lwaGVydGV4dA==", "aXY=")

        assertEquals(
            SecretFormat.EncodedSecret(ivBase64 = "Y2lwaGVydGV4dA==", ciphertextBase64 = "aXY="),
            SecretFormat.decode(swapped),
            "the halves round-trip in whatever order they arrive, so an IV/ciphertext swap is " +
                "caught by GCM's authentication tag rather than here -- and that is correct, " +
                "because only the crypto layer knows which half is which",
        )
    }
}