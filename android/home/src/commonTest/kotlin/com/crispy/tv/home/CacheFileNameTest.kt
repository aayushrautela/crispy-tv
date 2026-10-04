package com.crispy.tv.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [cacheFileName]: the one decision in the disk cache whose failure mode nothing would report.
 *
 * **The goldens below were computed with Python's `hashlib.sha256`, not read out of the
 * implementation** — a golden produced by running the code it pins proves that the code does
 * what it did when the golden was written. SHA-256's hex digest is a specification, so any
 * reader can recompute these five by hand (`sha256sum`) and be looking at the same answer
 * this suite asserts.
 *
 * ## Why this is worth a suite at all
 *
 * The name is the only thing connecting an entry on disk to the lookup that finds it. The
 * old code spelled the digest out by hand — `MessageDigest.getInstance("SHA-256")` and a
 * loop appending `(b ushr 4 and 0x0F).toString(16)` — and this is okio's
 * `ByteString.sha256().hex()`. Two implementations of the same specification agree, and
 * *`:app`'s `LibraryDiskCacheFileNameTest` already measured that swap for its own store*,
 * but "it was measured in another module for another store" is an argument by association:
 * a digest that came out one digit short would orphan every cached home feed on every
 * installed device, and the running app says nothing when that happens.
 *
 * The property assertion is the load-bearing one, and it is the one that cannot be got
 * wrong quietly: **the name is a digest, so it cannot contain a path separator.** A cache
 * key is caller-supplied data, and the last case is the one that proves the property rather
 * than restating it — `../../etc/passwd` hashes to a name that stays inside the cache
 * directory.
 */
class CacheFileNameTest {
    @Test
    fun `the empty key hashes to the well-known empty digest`() {
        // The empty SHA-256 is the one digest in this suite a reader can check without a
        // hash function at all; it is the published value for zero bytes.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.json",
            cacheFileName(""),
        )
    }

    @Test
    fun `the keys this cache actually writes hash to their documented digests`() {
        assertEquals(
            "81d43cac9237da8ed309aef9d5782ae75186da2201c88d9eb19cebd10f93a181.json",
            cacheFileName(GLOBAL_CACHE_KEY),
        )
        assertEquals(
            "6cdb8de4d79ed4aed3d1cfc8d42025f6e9d3019270bddeab9f4ecc24fc95cf70.json",
            cacheFileName(homeCacheKey("profile-1")),
        )
    }

    @Test
    fun `a non-ASCII key is hashed as UTF-8 rather than as its characters`() {
        // `encodeUtf8()` is the decision under test here. A per-character hash would be a
        // different digest for the same text depending on the reader's charset, and the
        // file this names would be unreachable from any other run.
        assertEquals(
            "ce3720cd1c43a00eceb6a9a38d64b350eaaf3cae57614f336b524d09b7eaa075.json",
            cacheFileName("naïve-ключ-🎬"),
        )
    }

    @Test
    fun `the name is a hex digest plus the json suffix for every key`() {
        val names = listOf("", "a", GLOBAL_CACHE_KEY, "home_snapshot:profile-1", "naïve-ключ-🎬", "../../etc/passwd")
        for (key in names) {
            assertTrue(
                cacheFileName(key).matches(Regex("[0-9a-f]{64}\\.json")),
                "cacheFileName(\"$key\") = ${cacheFileName(key)} is not 64 lowercase hex digits plus .json",
            )
        }
    }

    @Test
    fun `a key that looks like a path cannot escape the cache directory`() {
        // The point of hashing rather than sanitising: there is no separator left to strip,
        // so there is no normalisation rule to get subtly wrong. `3754d6cb…` is
        // sha256("../../etc/passwd").
        assertEquals(
            "3754d6cb3a38e1185e5b382d5f3ef3f118af75bf4bf0254d1fdb8437f51423e0.json",
            cacheFileName("../../etc/passwd"),
        )
    }
}