package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.nativeengine.playback.externalSubtitleTrackId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The routing decision behind every external-subtitle tap:
 * [decideExternalSubtitleSelection].
 *
 * Why the decision is tested and not the caller: `PlayerSessionViewModel`
 * builds its own collaborators from the app graph, so nothing in this source
 * set can construct one. The decision is the part that broke -- an
 * "attached" answer read from engine state that never updated meant every tap
 * of a row took the re-attach path and downloaded the same file again. The
 * engine half of that fix (re-reading the track list on every sid change) is
 * what makes `attached` trustworthy; this suite pins the routing that consumes
 * it, including the catalog-miss fall-through.
 *
 * What `externalSubtitleTrackId` does with the url (fragment stripped, trimmed)
 * is covered in `:core-domain`'s `ExternalSubtitleTrackIdTest`, so no case
 * repeats it here.
 */
class SubtitleTrackSelectionTest {

    @Test
    fun anAttachedPickSelectsWhatIsAlreadyThere() {
        val subtitle = addonSubtitle(url = "https://subs.example/pl.srt")
        val trackId = externalSubtitleTrackId(subtitle.url)

        val decision = decideExternalSubtitleSelection(
            trackId = trackId,
            attached = true,
            catalog = listOf(subtitle),
        )

        assertNull(decision, "an already-attached pick must not re-attach anything")
    }

    @Test
    fun anUnattachedPickAttachesTheMatchingCatalogEntry() {
        val subtitle = addonSubtitle(url = "https://subs.example/pl.srt")
        val trackId = externalSubtitleTrackId(subtitle.url)

        val decision = decideExternalSubtitleSelection(
            trackId = trackId,
            attached = false,
            catalog = listOf(addonSubtitle(url = "https://subs.example/other.srt"), subtitle),
        )

        assertEquals(
            subtitle,
            decision,
            "an unattached pick must attach its own catalog entry and not the first row",
        )
    }

    @Test
    fun anUnattachedUnknownPickFallsThroughToSelect() {
        val decision = decideExternalSubtitleSelection(
            trackId = "ext:deadbeef",
            attached = false,
            catalog = listOf(addonSubtitle(url = "https://subs.example/pl.srt")),
        )

        assertNull(decision, "a pick with no catalog entry belongs to the plain select path")
    }

    @Test
    fun pendingPickWinsWhileTheEngineStillReportsThePreviousTrack() {
        val pending = PendingSubtitleSelection(trackId = "fr", ticksLeft = 2)

        assertEquals(
            "fr",
            resolveSubtitleSelectionForUi(snapshotId = "en", pending = pending),
            "the latest tap must stay checked while its attach is pending",
        )
    }

    @Test
    fun engineAgreementSettlesTheOptimisticPick() {
        val pending = PendingSubtitleSelection(trackId = "fr", ticksLeft = 2)

        assertEquals("fr", resolveSubtitleSelectionForUi(snapshotId = "fr", pending = pending))
        assertNull(nextPendingSubtitleSelection(pending, snapshotId = "fr"))
    }

    @Test
    fun pendingPickExpiresBackToEngineTruth() {
        val pending = PendingSubtitleSelection(trackId = "fr", ticksLeft = 1)

        val next = nextPendingSubtitleSelection(pending, snapshotId = "en")

        assertNull(next, "the last disagreeing poll exhausts the optimistic budget")
        assertEquals(
            "en",
            resolveSubtitleSelectionForUi(snapshotId = "en", pending = next),
            "an attach that never settles must return to the engine's selected track",
        )
    }

    @Test
    fun eachDisagreeingPollConsumesOneTick() {
        val pending = PendingSubtitleSelection(trackId = "fr", ticksLeft = 3)

        assertEquals(
            PendingSubtitleSelection(trackId = "fr", ticksLeft = 2),
            nextPendingSubtitleSelection(pending, snapshotId = "en"),
        )
    }

    @Test
    fun aNewPickReplacesTheSupersededPendingPick() {
        val latestPick = PendingSubtitleSelection(trackId = "de", ticksLeft = 3)

        assertEquals(
            "de",
            resolveSubtitleSelectionForUi(snapshotId = "en", pending = latestPick),
            "the resolver must display the newest pending selection, not an earlier tap",
        )
    }

    private fun addonSubtitle(url: String) =
        AddonSubtitle(id = url, url = url, language = "pol", display = "Polish")
}
