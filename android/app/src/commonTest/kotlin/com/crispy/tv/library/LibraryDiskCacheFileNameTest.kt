package com.crispy.tv.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The cache file's name, pinned as a **stored-format contract**.
 *
 * [libraryCacheFileName] is the only thing connecting a cache entry on disk to
 * the lookup that will find it, so an implementation that is merely *equivalent*
 * to the old one does not degrade gracefully -- it orphans every cached section on
 * every installed device at the moment of the update, and the visible symptom is a
 * library that has forgotten its first page.
 *
 * **These hex strings are measured values, not derived ones.** They were produced
 * by the original `java.security.MessageDigest` implementation, in a standalone
 * JVM, and then compared against okio's `ByteString.sha256().hex()` in a
 * `commonTest` on the JVM. Both sides produced all of them byte-identically, and
 * the two implementations now in the tree are those two. So this suite is a lock
 * between them rather than a proof of a standard, and the failure message has to
 * say which of the two moved.
 *
 * **The measurement had to be redone once, and the reason is the thing worth
 * reading here.** The first list was hashed from the *shape of the parameters* --
 * a row for a whitespace-only profile holding the digest of the **untrimmed** key
 * `library:  :  ` -- and it failed, because `libraryCacheFileName` trims before it
 * hashes and that row's real key is `library::`. The digest was correct SHA-256 of
 * a string the function never produces. **A probe that takes pre-joined keys
 * cannot see the join**, so a golden has to be measured through the function and
 * not through the format it builds -- the key-format rule one level up from the
 * three places it is already written down, paid for with a red run.
 *
 * The inputs cover the ways a *nibble* hex encoding can go wrong, not the ways a
 * hash can collide. Each exists for one specific reason:
 *
 * - `("abc", "history")` is the plain case every other row is compared against.
 * - `("", "")` and `("  ", "  ")` cover **zero padding and the trims** together: a
 *   byte whose high nibble is `0` renders as a single digit under
 *   `Integer.toString(x, 16)`, so a digest could come out 63 characters long and
 *   the length assertion is the only thing that would notice. The two rows carry
 *   the *same* digest on purpose, and the trims are what make them so.
 * - `("ü", "tv")` covers **non-ASCII input**, because the old code hashed
 *   `toByteArray(StandardCharsets.UTF_8)` and okio hashes `encodeUtf8()`. A
 *   default-charset mismatch would show up here and nowhere else, and it would be
 *   a cross-platform bug invisible on the JVM.
 * - `("a", "b:c")` covers **a separator inside a field**, which is the input the
 *   composite key cannot represent -- and it is the row that exposes the recorded
 *   defect, because `("a:b", "c")` builds the same key. See
 *   `twoProfileSectionPairsThatConflateToTheSameKeyShareOneFile`.
 */
class LibraryDiskCacheFileNameTest {

    @Test
    fun theNameIsTheSha256OfTheTrimmedCompositeKeyPlusAJsonSuffix() {
        assertEquals(
            "b7332388cb211df828750b04efaa809c604e5d140e7d38cb080852fefe527bb4.json",
            libraryCacheFileName("abc", "history"),
        )
    }

    @Test
    fun theDigestIsSixtyFourLowercaseHexCharactersAndTheNameEndsInJson() {
        // The length assertion is the zero-padding check from the class KDoc: a
        // hand-rolled nibble loop that drops a leading zero produces 63
        // characters here, and **a 63-character name is still a legal file name**,
        // so nothing else in the system would notice.
        val name = libraryCacheFileName("abc", "history")
        val digest = name.removeSuffix(".json")

        assertEquals(64, digest.length)
        assertEquals(64, digest.count { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun everyMeasuredInputKeepsItsOldHexString() {
        // The cross-implementation lock. Each case is `(profile, section, old
        // digest)`, and the old digest is what `MessageDigest` produced.
        //
        // **Every `oldDigest` here was measured on the key the function actually
        // builds, which is the trimmed one, and getting that wrong is how the
        // first version of this list was wrong.** The first draft carried a row for
        // `("  ", "  ")` holding the digest of the *untrimmed* key
        // `library:  :  `, and it failed: the trims mean that row's key is
        // `library::`, so it is the same input as the `("", "")` row and gets the
        // same answer. The digest was real, measured, correct SHA-256 -- of a
        // string the production code never produces. **A golden hashed from the
        // shape of the parameters rather than from the function is the key-format
        // mistake one level up**, and a probe that takes pre-joined keys cannot
        // see the join.
        val measured = listOf(
            Triple("abc", "history", "b7332388cb211df828750b04efaa809c604e5d140e7d38cb080852fefe527bb4"),
            Triple("p1", "watchlist", "834b87acdaabe1a65670d08d610e216c95c2875acf6dc1e1fe53d02311972914"),
            Triple("", "", "e1db8a0e30b10f8af4408a97efc4010b28fc7b7a0bc11b459af92fa7315b64ef"),
            // Whitespace-only fields trim to the same key as absent ones, so this
            // row is deliberately a *duplicate* of the one above: the assertion
            // that matters is that they agree, and it is made in
            // `surroundingWhitespaceIsTrimmedBeforeHashing...` where the reason is
            // visible.
            Triple("  ", "  ", "e1db8a0e30b10f8af4408a97efc4010b28fc7b7a0bc11b459af92fa7315b64ef"),
            Triple("ü", "tv", "63dd710803895bf6b882f6e6648bc8af5d46c23d5c3af14cb711eaa3d8afb037"),
            Triple("a/b", "c:history", "35965a52435825a68cf7546256d521b7e9e42c20defcc33fdc5d84171cc429c2"),
            Triple("a", "b:c", "3aff7204f08675def0d2ea5caf94c201c89358a75ca7c00ae9e17acba5620427"),
        )

        measured.forEach { (profile, section, oldDigest) ->
            assertEquals(
                "$oldDigest.json",
                libraryCacheFileName(profile, section),
                "profile=$profile section=$section -- okio's sha256().hex() no longer matches " +
                    "the java.security.MessageDigest output this cache was written with, so every " +
                    "cached section on every installed device is orphaned by the next update",
            )
        }
    }

    @Test
    fun aWhitespaceOnlyKeyHashesToTheSameNameAsAnAbsentOne() {
        // The row above, said directly rather than as a duplicated table entry.
        // **The discriminator is that the two answers had to be made equal, not
        // that they are equal** -- a version of the function without the trims
        // gives `f1d963…` here and still passes every row of the measured table,
        // because the table's whitespace row is itself the trimmed input.
        assertEquals(
            libraryCacheFileName("", ""),
            libraryCacheFileName("  ", "\t\n"),
        )
    }

    @Test
    fun surroundingWhitespaceIsTrimmedBeforeHashingSoOneEntryCannotBecomeTwo() {
        // A profile id is read from a server response and from a local store, and
        // the two do not always agree about trailing spaces. Without the trims this
        // would be a silent cache miss on every load: a different name, a fresh
        // network fetch, and no error anywhere.
        assertEquals(
            libraryCacheFileName("abc", "history"),
            libraryCacheFileName("  abc  ", "\thistory\n"),
        )
    }

    @Test
    fun twoProfileSectionPairsThatConflateToTheSameKeyShareOneFile() {
        // The recorded defect, and the first draft of this test asserted the wrong
        // pair, which is the finding worth keeping. **The key is a plain
        // concatenation with no escaping**, so the two fields cannot be told apart
        // once joined -- and the first attempt used `("a/b", "c:history")` against
        // `("a", "b/c:history")`, whose keys are `library:a/b:c:history` and
        // `library:a:b/c:history`. Those differ: a `/` was mistaken for the `:`.
        //
        // The pair that actually collides puts the separator in the *field
        // boundary*: `("a", "b:c")` and `("a:b", "c")` both build `library:a:b:c`.
        // So one profile's cached first page can be served to another, and no
        // amount of hashing prevents it -- the collision is in the key, and the
        // hash faithfully preserves it.
        //
        // Pinned rather than fixed, because fixing it means changing the stored
        // name -- which is the exact thing the measured table exists to prevent --
        // so it is a product decision about whether an existing on-disk cache is
        // worth more than a collision that needs a profile id or a section id
        // containing a colon. Neither is reachable from a well-formed backend
        // response, which is why this has survived three landings.
        assertEquals(
            libraryCacheFileName("a", "b:c"),
            libraryCacheFileName("a:b", "c"),
        )
    }

    @Test
    fun aSlashIsNotTheSeparatorAndThoseTwoPairsDoNotCollide() {
        // The pair the first draft reached for, kept as its own test because it is
        // the negative case that makes the positive one mean something. **A
        // collision test with no non-collision test beside it is satisfied by a
        // function that hashes only the second field.**
        assertNotEquals(
            libraryCacheFileName("a/b", "c:history"),
            libraryCacheFileName("a", "b/c:history"),
        )
    }

    @Test
    fun differentSectionsAndDifferentProfilesDoNotCollide() {
        // The other direction, and the reason the key is hashed at all rather than
        // used verbatim: a raw `library:<profile>:<section>` name is legal on Linux
        // and illegal on Windows for a profile id containing `:`, and it lets a
        // profile id escape the cache directory with `..`.
        val names = listOf(
            libraryCacheFileName("p1", "history"),
            libraryCacheFileName("p2", "history"),
            libraryCacheFileName("p1", "watchlist"),
            libraryCacheFileName("p1", "history".uppercase()),
        )

        assertEquals(4, names.toSet().size)
        assertEquals(names.size, names.toSet().size)
    }
}
