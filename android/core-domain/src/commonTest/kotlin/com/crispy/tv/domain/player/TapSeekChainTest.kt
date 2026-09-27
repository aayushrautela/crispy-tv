package com.crispy.tv.domain.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TapSeekChainTest {

    private fun chain() = TapSeekChain(
        seekStepMs = 10_000L,
        doubleTapWindowMs = 300L,
        repeatWindowMs = 700L,
    )

    private fun tap(
        chain: TapSeekChain,
        zone: TapZone,
        positionMs: Long = 60_000L,
        nowMs: Long,
    ): List<TapSeekEvent> = chain.onTap(zone, positionMs, 120_000L, nowMs)

    @Test
    fun firstTapTogglesControls() {
        val events = tap(chain(), TapZone.CENTER, nowMs = 1_000L)
        assertEquals(listOf<TapSeekEvent>(TapSeekEvent.ToggleControls), events)
    }

    @Test
    fun secondQuickTapOnSideStartsChain() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        val events = tap(chain, TapZone.RIGHT, nowMs = 200L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainStarted(TapZone.RIGHT, 1, 10_000L)),
            events,
        )
    }

    @Test
    fun secondTapOutsideWindowTogglesAgain() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        val events = tap(chain, TapZone.RIGHT, nowMs = 400L)
        assertEquals(listOf<TapSeekEvent>(TapSeekEvent.ToggleControls), events)
    }

    @Test
    fun centerSecondTapRevertsToggle() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        val events = tap(chain, TapZone.CENTER, nowMs = 200L)
        assertEquals(listOf<TapSeekEvent>(TapSeekEvent.RevertControls), events)
    }

    @Test
    fun chainExtendsWithRapidTaps() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        tap(chain, TapZone.RIGHT, nowMs = 200L)
        val second = tap(chain, TapZone.RIGHT, nowMs = 600L)
        val third = tap(chain, TapZone.RIGHT, nowMs = 1_200L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainExtended(TapZone.RIGHT, 2, 20_000L)),
            second,
        )
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainExtended(TapZone.RIGHT, 3, 30_000L)),
            third,
        )
    }

    @Test
    fun leftChainProducesNegativeDelta() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        val events = tap(chain, TapZone.LEFT, nowMs = 200L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainStarted(TapZone.LEFT, 1, -10_000L)),
            events,
        )
    }

    @Test
    fun commitProducesAnchoredTarget() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        tap(chain, TapZone.RIGHT, nowMs = 200L)
        tap(chain, TapZone.RIGHT, nowMs = 600L)
        val events = chain.commit(120_000L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainCommitted(TapZone.RIGHT, 20_000L, 80_000L)),
            events,
        )
    }

    @Test
    fun commitClampsTargetToDuration() {
        val chain = chain()
        tap(chain, TapZone.CENTER, positionMs = 115_000L, nowMs = 0L)
        tap(chain, TapZone.RIGHT, positionMs = 115_000L, nowMs = 200L)
        tap(chain, TapZone.RIGHT, nowMs = 600L)
        val events = chain.commit(120_000L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainCommitted(TapZone.RIGHT, 20_000L, 120_000L)),
            events,
        )
    }

    @Test
    fun commitClampsTargetToZero() {
        val chain = chain()
        tap(chain, TapZone.CENTER, positionMs = 5_000L, nowMs = 0L)
        tap(chain, TapZone.LEFT, positionMs = 5_000L, nowMs = 200L)
        tap(chain, TapZone.LEFT, nowMs = 600L)
        val events = chain.commit(120_000L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainCommitted(TapZone.LEFT, -20_000L, 0L)),
            events,
        )
    }

    @Test
    fun commitWithoutPendingChainIsEmpty() {
        assertEquals(emptyList(), chain().commit(120_000L))
    }

    @Test
    fun commitWithUnknownDurationSkipsUpperClamp() {
        val chain = chain()
        chain.onTap(TapZone.CENTER, 115_000L, 0L, 0L)
        chain.onTap(TapZone.RIGHT, 115_000L, 0L, 200L)
        chain.onTap(TapZone.RIGHT, 115_000L, 0L, 600L)
        val events = chain.commit(0L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainCommitted(TapZone.RIGHT, 20_000L, 135_000L)),
            events,
        )
    }

    @Test
    fun sideSwitchCommitsOldChainAndStartsNew() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        tap(chain, TapZone.RIGHT, nowMs = 200L)
        tap(chain, TapZone.RIGHT, nowMs = 600L)
        val events = tap(chain, TapZone.LEFT, nowMs = 1_000L)
        assertEquals(
            listOf(
                TapSeekEvent.ChainCommitted(TapZone.RIGHT, 20_000L, 80_000L),
                TapSeekEvent.ChainStarted(TapZone.LEFT, 1, -10_000L),
            ),
            events,
        )
        val afterSwitch = chain.commit(120_000L)
        assertEquals(
            listOf<TapSeekEvent>(TapSeekEvent.ChainCommitted(TapZone.LEFT, -10_000L, 70_000L)),
            afterSwitch,
        )
    }

    @Test
    fun centerTapDuringChainIsIgnored() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        tap(chain, TapZone.RIGHT, nowMs = 200L)
        val events = tap(chain, TapZone.CENTER, nowMs = 600L)
        assertEquals(emptyList(), events)
    }

    @Test
    fun tapAfterRepeatWindowFlushesPendingChain() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        tap(chain, TapZone.RIGHT, nowMs = 200L)
        tap(chain, TapZone.RIGHT, nowMs = 600L)
        val events = tap(chain, TapZone.CENTER, nowMs = 1_400L)
        assertEquals(
            listOf(
                TapSeekEvent.ChainCommitted(TapZone.RIGHT, 20_000L, 80_000L),
                TapSeekEvent.ToggleControls,
            ),
            events,
        )
    }

    @Test
    fun resetDropsPendingChain() {
        val chain = chain()
        tap(chain, TapZone.CENTER, nowMs = 0L)
        tap(chain, TapZone.RIGHT, nowMs = 200L)
        chain.reset()
        assertEquals(emptyList(), chain.commit(120_000L))
    }
}
