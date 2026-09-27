package com.crispy.tv.domain.watch

/**
 * Pure rules for reporting player progress to our backend. Mirrors the robust
 * model where transient sub-second engine positions (0s during seeks/buffering)
 * are ignored, and a completed item is pinned to its full duration so a trailing
 * 0 from the engine cannot wipe the resume point.
 */
object PlaybackProgressPolicy {
    const val MIN_PROGRESS_POSITION_MS = 1000L
    const val COMPLETION_PERCENT = 85.0

    data class ResolvedWrite(
        val storedPositionMs: Long,
        val eventPositionMs: Long,
        val isCompleted: Boolean,
    )

    fun resolveProgressWrite(positionMs: Long, durationMs: Long): ResolvedWrite? {
        val currentSeconds = positionMs.coerceAtLeast(0L) / 1000.0
        val durationSeconds = if (durationMs > 0L) durationMs / 1000.0 else 0.0
        val isCompleted = durationSeconds > 0.0 &&
            (currentSeconds / durationSeconds) * 100.0 >= COMPLETION_PERCENT
        if (!isCompleted && positionMs < MIN_PROGRESS_POSITION_MS) return null
        val pinnedMs = if (isCompleted && durationMs > 0L) durationMs else positionMs.coerceAtLeast(0L)
        return ResolvedWrite(
            storedPositionMs = pinnedMs,
            eventPositionMs = pinnedMs,
            isCompleted = isCompleted,
        )
    }
}
