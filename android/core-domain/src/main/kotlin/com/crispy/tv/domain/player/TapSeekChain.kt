package com.crispy.tv.domain.player

enum class TapZone { LEFT, CENTER, RIGHT }

sealed interface TapSeekEvent {
    data object ToggleControls : TapSeekEvent
    data object RevertControls : TapSeekEvent
    data class ChainStarted(val side: TapZone, val count: Int, val pendingDeltaMs: Long) : TapSeekEvent
    data class ChainExtended(val side: TapZone, val count: Int, val pendingDeltaMs: Long) : TapSeekEvent
    data class ChainCommitted(val side: TapZone, val totalDeltaMs: Long, val targetMs: Long) : TapSeekEvent
}

class TapSeekChain(
    private val seekStepMs: Long = DEFAULT_SEEK_STEP_MS,
    private val doubleTapWindowMs: Long = DEFAULT_DOUBLE_TAP_WINDOW_MS,
    val repeatWindowMs: Long = DEFAULT_REPEAT_WINDOW_MS,
) {
    private var lastStandaloneTapMs: Long? = null
    private var pending: Pending? = null

    private class Pending(
        val side: TapZone,
        val anchorMs: Long,
        var count: Int,
        var lastTapMs: Long,
    )

    fun onTap(zone: TapZone, positionMs: Long, durationMs: Long, nowMs: Long): List<TapSeekEvent> {
        val events = mutableListOf<TapSeekEvent>()
        val active = pending
        if (active != null && nowMs - active.lastTapMs > repeatWindowMs) {
            events += commit(durationMs)
        }
        events += if (pending != null) {
            onTapDuringChain(zone, durationMs, nowMs)
        } else {
            listOfNotNull(onTapOutsideChain(zone, positionMs, nowMs))
        }
        return events
    }

    fun commit(durationMs: Long): List<TapSeekEvent> = listOfNotNull(commitPending(durationMs))

    fun reset() {
        lastStandaloneTapMs = null
        pending = null
    }

    private fun onTapDuringChain(zone: TapZone, durationMs: Long, nowMs: Long): List<TapSeekEvent> {
        val chain = pending ?: return emptyList()
        return when {
            zone == chain.side -> {
                chain.count += 1
                chain.lastTapMs = nowMs
                listOf(TapSeekEvent.ChainExtended(chain.side, chain.count, pendingDeltaMs(chain)))
            }
            zone != TapZone.CENTER -> {
                val committed = commitPending(durationMs) ?: return emptyList()
                val next = Pending(zone, committed.targetMs, 1, nowMs)
                pending = next
                listOf(committed, TapSeekEvent.ChainStarted(zone, 1, pendingDeltaMs(next)))
            }
            else -> emptyList()
        }
    }

    private fun commitPending(durationMs: Long): TapSeekEvent.ChainCommitted? {
        val chain = pending ?: return null
        pending = null
        lastStandaloneTapMs = null
        val totalDeltaMs = pendingDeltaMs(chain)
        val targetMs = clampTarget(chain.anchorMs + totalDeltaMs, durationMs)
        return TapSeekEvent.ChainCommitted(chain.side, totalDeltaMs, targetMs)
    }

    private fun onTapOutsideChain(zone: TapZone, positionMs: Long, nowMs: Long): TapSeekEvent? {
        val primedAt = lastStandaloneTapMs
        return if (primedAt != null && nowMs - primedAt <= doubleTapWindowMs) {
            lastStandaloneTapMs = null
            if (zone == TapZone.CENTER) {
                TapSeekEvent.RevertControls
            } else {
                pending = Pending(zone, positionMs, 1, nowMs)
                TapSeekEvent.ChainStarted(zone, 1, pendingDeltaMs(pending!!))
            }
        } else {
            lastStandaloneTapMs = nowMs
            TapSeekEvent.ToggleControls
        }
    }

    private fun pendingDeltaMs(chain: Pending): Long {
        val sign = if (chain.side == TapZone.LEFT) -1L else 1L
        return sign * chain.count * seekStepMs
    }

    private fun clampTarget(targetMs: Long, durationMs: Long): Long {
        val clamped = targetMs.coerceAtLeast(0L)
        return if (durationMs > 0L) clamped.coerceAtMost(durationMs) else clamped
    }

    companion object {
        const val DEFAULT_SEEK_STEP_MS = 10_000L
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS = 300L
        const val DEFAULT_REPEAT_WINDOW_MS = 700L
    }
}
