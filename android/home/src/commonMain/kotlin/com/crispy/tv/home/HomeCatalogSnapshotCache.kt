package com.crispy.tv.home

import com.crispy.tv.domain.home.HomeCatalogSnapshot

/**
 * A cached home snapshot, with the server expiry it was stored alongside.
 *
 * [expiresAtIso] is the raw server string rather than epoch millis on purpose:
 * `parseIso8601InstantToEpochMillis` is `core-domain` code, so the shared side
 * parses and the platform side only stores what it was handed.
 */
data class CachedHomeCatalogSnapshot(
    val snapshot: HomeCatalogSnapshot,
    val expiresAtIso: String?,
)

/**
 * Where [CachingHomeCatalogService] keeps the last home snapshot.
 *
 * Two members rather than three, because the expiry travels with the snapshot:
 * `cachedHomeExpiresAtMs()` used to walk the same cache keys a second time and
 * re-parse the file purely to read `expires_at`, and the writer always wrote one
 * payload under both keys, so the two answers could not disagree. Folding the
 * read removes the second walk rather than changing an answer.
 *
 * **A `null` [profileId] is not an error** — it is the signed-out reader, which
 * falls back to the global key holding the last snapshot of any profile so a
 * restored app still paints something.
 */
interface HomeCatalogSnapshotCache {
    suspend fun read(profileId: String?): CachedHomeCatalogSnapshot?

    suspend fun write(
        profileId: String?,
        snapshot: HomeCatalogSnapshot,
        expiresAtIso: String?,
    )
}

/**
 * The cache for a platform with no snapshot storage configured.
 *
 * Not a stub for a shipped feature: it is the honest answer for a build that has
 * nowhere to write, and it makes the cost visible — `loadCachedPrimaryHomeFeed`
 * answers `null` and `cachedHomeExpiresAtMs()` answers `null`, so the home feed
 * is whatever the live fetch returns with nothing to fall back on.
 */
object NoHomeCatalogSnapshotCache : HomeCatalogSnapshotCache {
    override suspend fun read(profileId: String?): CachedHomeCatalogSnapshot? = null

    override suspend fun write(
        profileId: String?,
        snapshot: HomeCatalogSnapshot,
        expiresAtIso: String?,
    ) = Unit
}