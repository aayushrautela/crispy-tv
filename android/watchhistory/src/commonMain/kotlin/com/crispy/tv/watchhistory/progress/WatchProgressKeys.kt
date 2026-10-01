package com.crispy.tv.watchhistory.progress

/**
 * The storage-key shapes [WatchProgressStore] reads and writes, and the two
 * pure functions that turn a content identity into one.
 *
 * ## Why these are top-level and not `private` members of the store
 *
 * Four of them were `private fun` members, which makes them unnameable from a
 * test **in this module as well as any other** -- `private` is a property of
 * the class, not of the file. The repo's own rule is that a private decision is
 * an untestable decision, and the two precedents are `:tv`'s
 * `CrispyTvDarkColors` (`private` -> `internal`) and the four `org.json`
 * accessors lifted out of `:app`'s two stores.
 *
 * Lifting rather than widening is what makes them testable without the
 * `KeyValueStore` and the `TimeSource` the store's constructor demands, and
 * without a `Context` -- which is not what blocks *this* file from `commonMain`
 * anyway, so the lift is about reachability, not portability.
 *
 * ## Why the two prefixes moved with them
 *
 * `getWatchProgressPrefKey` and `getContentDurationPrefKey` read
 * `WATCH_PROGRESS_KEY_PREFIX` and `CONTENT_DURATION_KEY_PREFIX`, and a
 * top-level function cannot see a `private companion` member. Leaving the
 * constants in the companion would have been worse than failing: **a companion
 * member shadows a same-named top-level declaration inside the class body**, so
 * the store would have kept reading its own copy while the lifted functions
 * read this one -- two strings that must agree and nothing that makes them.
 * `getAllWatchProgress` filters with `startsWith(WATCH_PROGRESS_KEY_PREFIX)`
 * and `removeAllWatchProgressForContent` rebuilds keys with
 * `getWatchProgressPrefKey`, so the prefix is a contract between a writer and a
 * reader, not a local detail.
 *
 * Deleting the companion's copies made every existing reference resolve to the
 * top-level declaration without a single call site changing. That is the
 * recorded shadowing rule, and this is the direction of it that adds a
 * declaration rather than removing one.
 */
internal const val WATCH_PROGRESS_KEY_PREFIX = "@watch_progress:"

internal const val CONTENT_DURATION_KEY_PREFIX = "@content_duration:"

internal fun getWatchProgressPrefKey(id: String, type: String, episodeId: String?): String {
    val base = "$WATCH_PROGRESS_KEY_PREFIX$type:$id"
    return if (episodeId.isNullOrBlank()) base else "$base:$episodeId"
}

internal fun getContentDurationPrefKey(id: String, type: String, episodeId: String?): String {
    val base = "$CONTENT_DURATION_KEY_PREFIX$type:$id"
    return if (episodeId.isNullOrBlank()) base else "$base:$episodeId"
}

internal fun buildWpKeyString(id: String, type: String, episodeId: String? = null): String {
    val base = "$type:$id"
    return if (episodeId.isNullOrBlank()) base else "$base:$episodeId"
}

/**
 * Reduce a provider episode id to the single key the continue-watching removal
 * is recorded under, trying three shapes in order.
 *
 * The order is the rule, and two of the three only make sense in it:
 *
 * 1. `"<n>:<n>"` on the tail, so `"series:2:5"` and `"tt1::2:5"` both reduce to
 *    `"<id>:2:5"`.
 * 2. An `s<n>e<n>` anywhere in it, case-insensitively, so `"Series1S02E05"`
 *    reduces to `"<id>:2:5"`.
 * 3. **Anything already prefixed with the id, returned verbatim** -- which is
 *    what makes the function idempotent. Without this a caller that passes an
 *    already-normalised id would get `"<id>:<id>:2:5"`, and the removal would
 *    be recorded under a key nothing ever clears.
 * 4. Otherwise `"<id>:<episodeId>"` unchanged.
 *
 * A blank episode id returns `""` and no removal is recorded, so a caller
 * cannot accidentally tombstone the whole content by passing an empty string.
 */
internal fun normalizeContinueWatchingEpisodeRemoveId(id: String, episodeId: String): String {
    val trimmedEpisodeId = episodeId.trim()
    if (trimmedEpisodeId.isBlank()) return ""

    val colonParts = trimmedEpisodeId.split(':')
    if (colonParts.size >= 2) {
        val season = colonParts[colonParts.size - 2].toIntOrNull()
        val episode = colonParts[colonParts.size - 1].toIntOrNull()
        if (season != null && episode != null) {
            return "$id:$season:$episode"
        }
    }

    val match = Regex("s(\\d+)e(\\d+)", RegexOption.IGNORE_CASE).find(trimmedEpisodeId)
    if (match != null) {
        val season = match.groupValues.getOrNull(1)?.toIntOrNull()
        val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
        if (season != null && episode != null) {
            return "$id:$season:$episode"
        }
    }

    if (trimmedEpisodeId.startsWith("$id:")) return trimmedEpisodeId
    return "$id:$trimmedEpisodeId"
}
