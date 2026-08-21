package com.crispy.tv.domain.optimistic

/**
 * Pure, deterministic model for optimistic user-mutation state.
 *
 * Local intent (a [UserMutation]) is applied on top of the last known server
 * truth ([UserStateSnapshot]) via [deriveUserState]. The in-flight outbox is
 * scheduled by [planOutbox]. Nothing here touches IO, Android types, or the
 * system clock, so it can be mirrored 1:1 by the Swift ContractRunner and
 * covered by contract fixtures.
 */

enum class MutationKind {
    WATCHLIST,
    TITLE_WATCHED,
    EPISODE_WATCHED,
    SEASON_WATCHED,
    RATING,
}

/**
 * Pure copy of the content-type taxonomy the watch-history backend needs, kept
 * in [core-domain] so mutations stay free of `player`/Android types. The app
 * maps to/from [com.crispy.tv.player.MetadataLabMediaType] at the boundary.
 */
enum class MediaContentType {
    MOVIE,
    SERIES,
    ANIME,
}

/**
 * Lifecycle of a single mutation. [Inflight] is transient (never persisted);
 * on reload it is coerced back to [Pending] so a crashed write is retried.
 * A successfully synced mutation is removed entirely from the store.
 */
sealed interface MutationStatus {
    data object Pending : MutationStatus

    data object Inflight : MutationStatus

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : MutationStatus

    data class Conflict(
        val serverValue: String?,
    ) : MutationStatus
}

/**
 * A single user intent. `id` is a client-generated nonce used for idempotency
 * on the server and as the dedupe key for retries. `entityId` groups mutations
 * that target the same logical target so rapid toggles coalesce; `titleItemId`
 * lets the UI observe every pending mutation for one title at once.
 */
sealed interface UserMutation {
    val id: String
    val titleItemId: String
    val entityId: String
    val kind: MutationKind
    val createdAtMs: Long
    val attempt: Int
    val status: MutationStatus
    val nextAttemptAtMs: Long
}

data class WatchlistMutation(
    override val id: String,
    override val titleItemId: String,
    override val entityId: String,
    override val createdAtMs: Long,
    override val attempt: Int,
    override val status: MutationStatus,
    override val nextAttemptAtMs: Long,
    val desired: Boolean,
) : UserMutation {
    override val kind: MutationKind = MutationKind.WATCHLIST
}

data class TitleWatchedMutation(
    override val id: String,
    override val titleItemId: String,
    override val entityId: String,
    override val createdAtMs: Long,
    override val attempt: Int,
    override val status: MutationStatus,
    override val nextAttemptAtMs: Long,
    val contentType: MediaContentType,
    val desired: Boolean,
) : UserMutation {
    override val kind: MutationKind = MutationKind.TITLE_WATCHED
}

data class RatingMutation(
    override val id: String,
    override val titleItemId: String,
    override val entityId: String,
    override val createdAtMs: Long,
    override val attempt: Int,
    override val status: MutationStatus,
    override val nextAttemptAtMs: Long,
    /** `null` means "remove rating". */
    val desired: Int?,
) : UserMutation {
    override val kind: MutationKind = MutationKind.RATING
}

data class EpisodeWatchedMutation(
    override val id: String,
    override val titleItemId: String,
    override val entityId: String,
    override val createdAtMs: Long,
    override val attempt: Int,
    override val status: MutationStatus,
    override val nextAttemptAtMs: Long,
    val itemId: String,
    val season: Int,
    val episode: Int?,
    val videoId: String,
    val desired: Boolean,
) : UserMutation {
    override val kind: MutationKind = MutationKind.EPISODE_WATCHED
}

data class SeasonWatchedMutation(
    override val id: String,
    override val titleItemId: String,
    override val entityId: String,
    override val createdAtMs: Long,
    override val attempt: Int,
    override val status: MutationStatus,
    override val nextAttemptAtMs: Long,
    val seasonItemId: String,
    val seasonNumber: Int,
    val desired: Boolean,
) : UserMutation {
    override val kind: MutationKind = MutationKind.SEASON_WATCHED
}

/**
 * Last known server truth for a title. Fields that have not been loaded yet
 * should be represented as absent (`false`/`null`) rather than guessed; the
 * merge only overrides values for which a pending mutation exists.
 */
data class UserStateSnapshot(
    val isInWatchlist: Boolean = false,
    val isWatched: Boolean = false,
    val isRated: Boolean = false,
    val userRating: Int? = null,
    val episodeWatched: Map<String, Boolean> = emptyMap(),
    val seasonWatched: Map<Int, Boolean> = emptyMap(),
)

enum class FieldSync {
    IDLE,
    SYNCING,
    ERROR,
}

data class MutationSyncView(
    val status: FieldSync,
    val errorMessage: String? = null,
)

data class DerivedUserState(
    /** `value` is the rating (null == unrated); `isRated` is derived from it. */
    val watchlist: Pair<Boolean, MutationSyncView>,
    val titleWatched: Pair<Boolean, MutationSyncView>,
    val rating: Pair<Int?, MutationSyncView>,
    val episodeWatched: Map<String, Pair<Boolean, MutationSyncView>>,
    val seasonWatched: Map<Int, Pair<Boolean, MutationSyncView>>,
)

data class RetryPolicy(
    val baseDelayMs: Long = 1000,
    val maxDelayMs: Long = 60_000,
    val maxAttempts: Int = 8,
)

data class OutboxAction(
    val mutationId: String,
    val kind: MutationKind,
)

/**
 * Deterministic backoff: `base * 2^(attempt-1)`, capped. Pure so the contract
 * fixture and the runtime processor agree.
 */
fun nextBackoffDelayMs(
    attempt: Int,
    policy: RetryPolicy,
): Long {
    if (attempt <= 0) return policy.baseDelayMs
    val exponent = (attempt - 1).coerceAtMost(30)
    val multiplied =
        try {
            policy.baseDelayMs * (1L shl exponent)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    return multiplied.coerceAtMost(policy.maxDelayMs).coerceAtLeast(0)
}

/**
 * Keep at most one active mutation per (kind, entityId), choosing the most
 * recently created. Older intents for the same target are superseded — this is
 * what makes rapid double-toggles collapse into a single final write.
 */
fun coalesceMutations(mutations: List<UserMutation>): List<UserMutation> {
    if (mutations.isEmpty()) return emptyList()
    val byKey = mutableMapOf<String, UserMutation>()
    for (mutation in mutations) {
        val key = mutation.kind.name + "#" + mutation.entityId
        val existing = byKey[key]
        if (existing == null || mutation.createdAtMs >= existing.createdAtMs) {
            byKey[key] = mutation
        }
    }
    return byKey.values.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
}

private fun latestOf(
    group: List<UserMutation>,
): UserMutation? =
    group.maxWithOrNull(compareBy({ it.createdAtMs }, { it.id }))

/**
 * Apply pending/local intent on top of server truth.
 *
 * Rules (deterministic, latest-created wins within a kind+entity):
 * - [MutationStatus.Pending] / [MutationStatus.Inflight]: the local `desired`
 *   value is shown and the field is marked [FieldSync.SYNCING].
 * - [MutationStatus.Failed] / [MutationStatus.Conflict]: fall back to server
 *   truth and mark [FieldSync.ERROR] so the UI can offer a retry.
 * - No active mutation: server truth, [FieldSync.IDLE].
 */
fun deriveUserState(
    snapshot: UserStateSnapshot,
    mutations: List<UserMutation>,
): DerivedUserState {
    val grouped = mutations.groupBy { it.kind }

    fun active(kind: MutationKind): UserMutation? = latestOf(grouped[kind].orEmpty())

    fun <T> reduce(
        server: T,
        mutation: UserMutation?,
        desiredOf: (UserMutation) -> T,
    ): Pair<T, MutationSyncView> {
        if (mutation == null) {
            return server to MutationSyncView(FieldSync.IDLE)
        }
        return when (val status = mutation.status) {
            MutationStatus.Pending,
            MutationStatus.Inflight,
            -> desiredOf(mutation) to MutationSyncView(FieldSync.SYNCING)

            is MutationStatus.Failed ->
                server to
                    MutationSyncView(
                        FieldSync.ERROR,
                        status.reason.takeIf { it.isNotBlank() } ?: "Update failed.",
                    )

            is MutationStatus.Conflict ->
                server to
                    MutationSyncView(
                        FieldSync.ERROR,
                        status.serverValue?.let { "Server has a different value: $it" }
                            ?: "Server rejected the change.",
                    )
        }
    }

    val watchlist = reduce(snapshot.isInWatchlist, active(MutationKind.WATCHLIST)) { (it as WatchlistMutation).desired }
    val titleWatched = reduce(snapshot.isWatched, active(MutationKind.TITLE_WATCHED)) { (it as TitleWatchedMutation).desired }
    val rating = reduce(snapshot.userRating, active(MutationKind.RATING)) { (it as RatingMutation).desired }

    val episodeWatched = snapshot.episodeWatched.toMutableMap()
    val episodeSync = mutableMapOf<String, MutationSyncView>()
    for (mutation in grouped[MutationKind.EPISODE_WATCHED].orEmpty()) {
        mutation as EpisodeWatchedMutation
        val derived = reduce(episodeWatched[mutation.videoId] ?: false, mutation) { mutation.desired }
        episodeWatched[mutation.videoId] = derived.first
        episodeSync[mutation.videoId] = derived.second
    }

    val seasonWatched = snapshot.seasonWatched.toMutableMap()
    val seasonSync = mutableMapOf<Int, MutationSyncView>()
    for (mutation in grouped[MutationKind.SEASON_WATCHED].orEmpty()) {
        mutation as SeasonWatchedMutation
        val derived = reduce(seasonWatched[mutation.seasonNumber] ?: false, mutation) { mutation.desired }
        seasonWatched[mutation.seasonNumber] = derived.first
        seasonSync[mutation.seasonNumber] = derived.second
    }

    return DerivedUserState(
        watchlist = watchlist,
        titleWatched = titleWatched,
        rating = rating,
        episodeWatched =
            episodeWatched
                .mapValues { (key, value) ->
                    value to (episodeSync[key] ?: MutationSyncView(FieldSync.IDLE))
                },
        seasonWatched =
            seasonWatched
                .mapValues { (key, value) ->
                    value to (seasonSync[key] ?: MutationSyncView(FieldSync.IDLE))
                },
    )
}

/**
 * Select due mutations to execute now. Only [MutationStatus.Pending] entries
 * whose `nextAttemptAtMs` has passed are eligible; results are ordered
 * deterministically so the processor and contract fixture agree.
 */
fun planOutbox(
    mutations: List<UserMutation>,
    nowMs: Long,
): List<OutboxAction> =
    mutations
        .filter { it.status == MutationStatus.Pending && it.nextAttemptAtMs <= nowMs }
        .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        .map { OutboxAction(mutationId = it.id, kind = it.kind) }
