package com.crispy.tv.optimistic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the **stored key format** of [newUserMutationId], and nothing else.
 *
 * These strings are written to disk by the outbox and compared by it, so the format is a
 * compatibility surface in the same way a cache key is — see the composite-key rule: *a
 * separator in a composite key is a claim that the field cannot contain it, and nothing
 * states that claim anywhere.* `newUserMutationId` moved from `androidMain` to `commonMain`
 * by swapping `java.util.UUID.randomUUID()` for the stdlib's `kotlin.uuid.Uuid.random()`,
 * and this suite is what makes that swap a measured claim rather than a promise in a KDoc:
 * **if the two produce different text, one of these cases fails.**
 *
 * The regex is deliberately stricter than "looks like a UUID". It pins the lowercase hex
 * digits, the four hyphens at their canonical offsets, **the version nibble `4` in the
 * 13th character** and **the RFC 4122 variant nibble `[89ab]` in the 17th**. A suite that
 * only counted hyphens would pass on an uppercased string and on a v7 id, and those are
 * exactly the two changes that would silently re-key a user's stored mutations.
 */
class UserMutationIdsTest {

    private val canonicalV4 = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )

    @Test
    fun aMintedIdIsTheCanonicalLowercaseHyphenatedV4Form() {
        val id = newUserMutationId()
        assertTrue(
            canonicalV4.matches(id),
            "expected the canonical lowercase 8-4-4-4-12 version-4 form but was <$id>",
        )
    }

    @Test
    fun aMintedIdIsThirtySixCharacters() {
        // Separate from the regex on purpose: the regex is what pins the *shape* and this
        // is what fails first on a truncated or unhyphenated variant, which is the mistake
        // a "just use the raw hex" port would make.
        assertEquals(36, newUserMutationId().length)
    }

    @Test
    fun theThirteenthCharacterIsTheVersionNibbleAndTheSeventeenthIsTheVariantNibble() {
        val id = newUserMutationId()
        assertEquals(
            '4',
            id[14],
            "the version nibble is the first character of the third group, so index 14 " +
                "and not 13 -- and it was <${id[14]}> in <$id>",
        )
        assertTrue(
            id[19] in "89ab",
            "the variant nibble must be one of 8/9/a/b and was <${id[19]}> in <$id>",
        )
    }

    @Test
    fun consecutiveMintsAreDistinct() {
        // A generator that returned a constant would satisfy every case above, so this is
        // the only case here that is about the function rather than the format. 500 is
        // well clear of a birthday collision for 122 random bits.
        val ids = List(500) { newUserMutationId() }
        assertEquals(500, ids.toSet().size, "expected 500 distinct ids")
    }

    @Test
    fun everyMintedIdSatisfiesTheSameFormat() {
        // The format case reads one sample. If one draw could differ -- a v7 id on some
        // code path, say -- a single sample would call the format pinned. This is the
        // difference between "the regex matches what I drew" and "the regex matches what
        // this function produces".
        val offenders = List(200) { newUserMutationId() }.filterNot { canonicalV4.matches(it) }
        assertEquals(
            emptyList(),
            offenders,
            "every id must match the canonical form; these ${offenders.size} did not",
        )
    }
}
