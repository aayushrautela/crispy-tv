package com.crispy.tv.domain.home

import kotlin.random.Random

/**
 * Minimum rating, on the 0-10 scale the backend formats to, for an item to be
 * eligible for a random pick. Items with no usable rating are never eligible.
 */
const val HOME_RANDOM_MIN_RATING: Double = 6.0

/** Largest pool any one random-pick chip will spin. */
const val HOME_RANDOM_POOL_CAP: Int = 50

/** How many genre chips the random-pick overlay offers alongside `All`. */
const val HOME_RANDOM_GENRE_LIMIT: Int = 3

/**
 * One item the random-pick wheel can land on.
 *
 * [type] is the backend's media type, passed through unchanged, because it is
 * navigation input. Turning it into a display label is the UI's decision, not
 * this type's — see the overlay's type-label helper.
 */
data class HomeRandomCandidate(
    val itemId: String,
    val title: String,
    val type: String,
    val genre: String?,
    val rating: Double?,
    val artworkUrl: String?,
)

/**
 * A genre and how many eligible candidates it holds.
 *
 * [genre] is the first spelling seen for the genre, so a chip reads `Sci-Fi`
 * rather than whichever list happened to be scanned first. Ordering does not
 * depend on it: genres are compared on their normalised key.
 */
data class HomeRandomGenre(val genre: String, val count: Int)

/**
 * Flattens home list items into random-pick candidates.
 *
 * Drops items with a blank id or title, and keeps only the first occurrence of
 * each id. Home data overlaps deliberately — a header pill, a rail and the hero
 * can all carry the same title — so deduplication is the normal case rather than
 * an edge case, and first-occurrence-wins is what keeps the curated ordering
 * that the snapshot was built with.
 *
 * A rating that is absent, blank or not a number becomes `null` rather than
 * throwing, because it arrives as display text from an add-on we do not control.
 * Text that *does* parse but is not a rating — `NaN`, `Infinity` — is kept here
 * and rejected by the eligibility test, so parsing and judging stay separate.
 */
fun List<HomeCatalogItem>.toRandomCandidates(): List<HomeRandomCandidate> {
    val seenItemIds = HashSet<String>()
    val candidates = ArrayList<HomeRandomCandidate>(size)
    for (item in this) {
        val itemId = item.itemId.trim()
        if (itemId.isEmpty()) continue
        val title = item.title.trim()
        if (title.isEmpty()) continue
        if (!seenItemIds.add(itemId)) continue
        candidates += HomeRandomCandidate(
            itemId = itemId,
            title = title,
            type = item.type.trim(),
            genre = item.genre?.trim()?.takeIf { it.isNotEmpty() },
            rating = item.rating?.trim()?.toDoubleOrNull(),
            artworkUrl = item.artworkUrl?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
    return candidates
}

/**
 * Ranks genres by how many eligible candidates they hold, most plentiful first.
 *
 * Only candidates at or above [minRating] with a genre are counted, because a
 * genre with no eligible items is a chip whose pool is always empty. Genres are
 * grouped by a trimmed, lowercased key so `Sci-Fi` and `sci-fi` are one chip, and
 * equal counts are broken by that key so the same home data always produces the
 * same chips in the same order.
 */
fun List<HomeRandomCandidate>.topGenres(minRating: Double, limit: Int): List<HomeRandomGenre> {
    val labels = LinkedHashMap<String, String>()
    val counts = LinkedHashMap<String, Int>()
    for (candidate in this) {
        val rating = candidate.rating
        if (rating == null || !rating.isEligibleAt(minRating)) continue
        val genre = candidate.genre?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val key = genre.lowercase()
        if (key in labels) {
            counts[key] = (counts[key] ?: 0) + 1
        } else {
            labels[key] = genre
            counts[key] = 1
        }
    }
    return labels.keys
        .sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
        .take(limit.coerceAtLeast(0))
        .map { key -> HomeRandomGenre(genre = labels.getValue(key), count = counts[key] ?: 0) }
}

/**
 * Whether a parsed rating is good enough to pick from.
 *
 * `isFinite` is not decoration: an add-on can send the text `NaN`, and every
 * comparison against `NaN` is false, so a plain `rating < minRating` test lets
 * an unreadable rating through while a plain `rating >= minRating` test drops
 * every good one. Only asking the two questions separately is correct.
 */
private fun Double.isEligibleAt(minRating: Double): Boolean = isFinite() && this >= minRating

/**
 * Picks the pool a chip spins, shuffled and cut to [cap].
 *
 * A null or blank [genre] means every genre, which is what the `All` chip asks
 * for; otherwise the genre is matched on the same trimmed, lowercased key
 * [topGenres] groups by. [random] is a parameter so a test can pin the order —
 * a pool that cannot be reproduced is a wheel that cannot be asserted on.
 */
fun List<HomeRandomCandidate>.randomPool(
    genre: String?,
    minRating: Double,
    cap: Int,
    random: Random,
): List<HomeRandomCandidate> {
    val key = genre?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    return filter { candidate ->
        val rating = candidate.rating
        if (rating == null || !rating.isEligibleAt(minRating)) return@filter false
        key == null || candidate.genre?.trim()?.lowercase() == key
    }.shuffled(random).take(cap.coerceAtLeast(0))
}