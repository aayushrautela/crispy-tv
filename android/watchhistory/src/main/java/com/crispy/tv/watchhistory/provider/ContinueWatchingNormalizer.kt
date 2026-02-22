package com.crispy.tv.watchhistory.provider

import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_COMPLETION_PERCENT
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_MIN_PROGRESS_PERCENT
import com.crispy.tv.watchhistory.STALE_PLAYBACK_WINDOW_MS
import java.util.Locale
import kotlin.math.abs

internal class ContinueWatchingNormalizer {
    fun normalize(entries: List<ContinueWatchingEntry>, nowMs: Long, limit: Int): List<ContinueWatchingEntry> {
        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        val deduped = linkedMapOf<String, ContinueWatchingEntry>()
        for (entry in entries) {
            val normalized =
                entry.copy(
                    progressPercent = entry.progressPercent.coerceIn(0.0, 100.0),
                    title = entry.title.trim().ifEmpty { entry.contentId },
                    contentId = entry.contentId.trim(),
                )
            if (normalized.contentId.isEmpty()) continue
            if (normalized.lastUpdatedEpochMs < staleCutoff) continue

            if (normalized.isUpNextPlaceholder) {
                if (normalized.progressPercent > 0.0) continue
            } else {
                if (normalized.progressPercent < CONTINUE_WATCHING_MIN_PROGRESS_PERCENT) continue
                if (normalized.progressPercent >= CONTINUE_WATCHING_COMPLETION_PERCENT) continue
            }

            val key = "${normalized.contentType.name}:${normalized.contentId}".lowercase(Locale.US)
            val existing = deduped[key]
            deduped[key] = if (existing == null) normalized else choosePreferred(existing, normalized)
        }

        return deduped.values
            .sortedWith(
                compareByDescending<ContinueWatchingEntry> { it.progressPercent > 0.0 }
                    .thenByDescending { it.lastUpdatedEpochMs },
            ).take(limit.coerceAtLeast(1))
    }

    private fun choosePreferred(current: ContinueWatchingEntry, incoming: ContinueWatchingEntry): ContinueWatchingEntry {
        val sameEpisode =
            current.contentType == incoming.contentType &&
                current.contentId.equals(incoming.contentId, ignoreCase = true) &&
                current.season == incoming.season &&
                current.episode == incoming.episode &&
                current.isUpNextPlaceholder == incoming.isUpNextPlaceholder

        return if (sameEpisode) {
            val progressDelta = incoming.progressPercent - current.progressPercent
            when {
                abs(progressDelta) > 0.5 -> if (progressDelta > 0) incoming else current
                incoming.lastUpdatedEpochMs > current.lastUpdatedEpochMs -> incoming
                else -> current
            }
        } else {
            when {
                incoming.lastUpdatedEpochMs > current.lastUpdatedEpochMs -> incoming
                incoming.lastUpdatedEpochMs < current.lastUpdatedEpochMs -> current
                incoming.progressPercent > current.progressPercent -> incoming
                else -> current
            }
        }
    }
}
