package com.crispy.tv.backend

/**
 * The request and response payloads of [BackendApi].
 *
 * ## Why these live here and not next to the client
 *
 * Every one of these is pure data: `String`, `Int`, `Double` and
 * `Map<String, Any?>`. None of them needs a JVM type, so `commonMain` code on
 * every target can name them.
 *
 * They used to be declared at the bottom of `CrispyBackendClient.kt`, in
 * `androidMain`, which made [BackendApi] impossible to declare in `commonMain`:
 * four of its fifty methods take or return one of these, so the interface would
 * have had to name a symbol that does not exist off Android. That is the only
 * reason they moved, and moving them changed no behaviour and no import
 * anywhere -- they already shared this package.
 *
 * The transport stays in `androidMain`. These types are what makes it possible
 * for the *interface* to be portable while the *implementation* is not.
 */
data class ItemLookupInput(
    val itemId: String? = null,
)

data class WatchMutationInput(
    val itemId: String,
    val occurredAt: String? = null,
    val rating: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class PlaybackEventInput(
    val clientEventId: String,
    val eventType: String,
    val itemId: String,
    val positionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val occurredAt: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class ImportJobsResponse(
    val jobs: List<ImportJob>,
)
