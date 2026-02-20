package com.crispy.rewrite.domain.watch

data class ContinueWatchingCandidate(
    val contentType: String,
    val contentId: String,
    val episodeKey: String? = null,
    val progressPercent: Double,
    val lastUpdatedMs: Long,
    val isUpNextPlaceholder: Boolean = false
)

data class ContinueWatchingPlanItem(
    val contentType: String,
    val contentId: String,
    val episodeKey: String?,
    val progressPercent: Double,
    val lastUpdatedMs: Long,
    val isUpNextPlaceholder: Boolean
)

fun planContinueWatching(
    candidates: List<ContinueWatchingCandidate>,
    nowMs: Long,
    maxItems: Int = 20,
    minProgressPercent: Double = 2.0,
    completionPercent: Double = 85.0,
    staleWindowMs: Long = 30L * 24L * 60L * 60L * 1000L
): List<ContinueWatchingPlanItem> {
    if (candidates.isEmpty() || maxItems <= 0) {
        return emptyList()
    }

    val staleCutoff = nowMs - staleWindowMs
    val filtered =
        candidates
            .mapNotNull { candidate -> normalizeCandidate(candidate) }
            .filter { candidate ->
                if (candidate.lastUpdatedMs < staleCutoff) {
                    return@filter false
                }
                if (candidate.isUpNextPlaceholder) {
                    return@filter candidate.progressPercent <= 0.0
                }
                candidate.progressPercent >= minProgressPercent && candidate.progressPercent < completionPercent
            }

    val deduped = linkedMapOf<String, ContinueWatchingCandidate>()
    filtered.forEach { candidate ->
        val key = "${candidate.contentType}:${candidate.contentId}"
        val current = deduped[key]
        deduped[key] = if (current == null) candidate else choosePreferred(current, candidate)
    }

    return deduped
        .values
        .sortedWith(
            compareByDescending<ContinueWatchingCandidate> { it.progressPercent > 0.0 }
                .thenByDescending { it.lastUpdatedMs }
        )
        .take(maxItems)
        .map { candidate ->
            ContinueWatchingPlanItem(
                contentType = candidate.contentType,
                contentId = candidate.contentId,
                episodeKey = candidate.episodeKey,
                progressPercent = candidate.progressPercent,
                lastUpdatedMs = candidate.lastUpdatedMs,
                isUpNextPlaceholder = candidate.isUpNextPlaceholder
            )
        }
}

private fun normalizeCandidate(candidate: ContinueWatchingCandidate): ContinueWatchingCandidate? {
    val normalizedType = candidate.contentType.trim().lowercase()
    val normalizedId = candidate.contentId.trim()
    if (normalizedType.isEmpty() || normalizedId.isEmpty()) {
        return null
    }

    return candidate.copy(
        contentType = normalizedType,
        contentId = normalizedId,
        episodeKey = candidate.episodeKey?.trim()?.ifEmpty { null },
        progressPercent = candidate.progressPercent.coerceIn(0.0, 100.0)
    )
}

private fun choosePreferred(
    current: ContinueWatchingCandidate,
    incoming: ContinueWatchingCandidate
): ContinueWatchingCandidate {
    val sameEpisode =
        current.contentType == "movie" ||
            (current.episodeKey != null && current.episodeKey == incoming.episodeKey)

    // Mirror Nuvio's behavior: for the same episode/movie, only let progress win when it is
    // meaningfully ahead; otherwise, fall back to recency to avoid flip-flopping on tiny deltas.
    if (sameEpisode) {
        val preferProgressDelta = 0.5
        if (incoming.progressPercent > current.progressPercent + preferProgressDelta) {
            return incoming
        }
        if (current.progressPercent > incoming.progressPercent + preferProgressDelta) {
            return current
        }
    }

    if (incoming.lastUpdatedMs != current.lastUpdatedMs) {
        return if (incoming.lastUpdatedMs > current.lastUpdatedMs) incoming else current
    }

    return if (incoming.progressPercent > current.progressPercent) incoming else current
}
